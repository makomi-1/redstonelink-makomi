package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * core 读档后 blockstate 可见态异步校正服务。
 * <p>
 * 目标：
 * 1. 避免在方块实体加载关键路径里直接 `setBlock(...)`；
 * 2. 仅对确实存在 drift 的 `core` 入队；
 * 3. 消费阶段仅用非阻塞取块，不等待 chunk 就绪。
 * </p>
 */
public final class CoreBlockStateResyncService {
	private static boolean registered;
	private static final Map<MinecraftServer, LinkedHashMap<PendingCoreKey, PendingCoreTask>> PENDING_TASKS_BY_SERVER =
		new IdentityHashMap<>();
	private static final Set<MinecraftServer> STARTED_SERVERS = Collections.newSetFromMap(new IdentityHashMap<>());

	private CoreBlockStateResyncService() {
	}

	/**
	 * 注册 `core` 异步校正钩子。
	 */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerChunkEvents.CHUNK_LOAD.register(CoreBlockStateResyncService::enqueuePendingCoreTasks);
		ServerTickEvents.END_SERVER_TICK.register(CoreBlockStateResyncService::consumePendingTasks);
		ServerLifecycleEvents.SERVER_STARTED.register(CoreBlockStateResyncService::markServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(CoreBlockStateResyncService::clearServerState);
		ServerLifecycleEvents.SERVER_STOPPED.register(CoreBlockStateResyncService::clearServerState);
	}

	private static void markServerStarted(MinecraftServer server) {
		if (server == null) {
			return;
		}
		synchronized (PENDING_TASKS_BY_SERVER) {
			STARTED_SERVERS.add(server);
		}
	}

	/**
	 * 仅把存在可见态 drift 的 `core` 位置入队，避免整区块无差别处理。
	 */
	private static void enqueuePendingCoreTasks(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
				continue;
			}
			if (!targetBlockEntity.hasPendingLoadBlockStateSync()) {
				continue;
			}
			requeueTask(server, new PendingCoreTask(level.dimension(), targetBlockEntity.getBlockPos().immutable(), 0));
		}
	}

	/**
	 * 在服务端稳定后按位置消费队列，chunk 未就绪时非阻塞重试。
	 */
	private static void consumePendingTasks(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int maxRetry = RedstoneLinkConfig.runtimeLoadResyncMaxRetry();
		List<PendingCoreTask> pendingTasks;
		synchronized (PENDING_TASKS_BY_SERVER) {
			if (!STARTED_SERVERS.contains(server)) {
				return;
			}
			LinkedHashMap<PendingCoreKey, PendingCoreTask> pendingTaskMap = PENDING_TASKS_BY_SERVER.get(server);
			if (pendingTaskMap == null || pendingTaskMap.isEmpty()) {
				return;
			}
			pendingTasks = new ArrayList<>(pendingTaskMap.values());
			pendingTaskMap.clear();
		}
		for (PendingCoreTask pendingTask : pendingTasks) {
			if (pendingTask == null) {
				continue;
			}
			if (!consumeTask(server, pendingTask)) {
				if (pendingTask.attempt() < maxRetry) {
					requeueTask(server, pendingTask.nextAttempt());
					continue;
				}
				logGiveUp(pendingTask, maxRetry);
			}
		}
	}

	private static boolean consumeTask(MinecraftServer server, PendingCoreTask pendingTask) {
		ServerLevel level = server.getLevel(pendingTask.dimension());
		if (level == null) {
			return false;
		}
		BlockPos blockPos = pendingTask.blockPos();
		ChunkPos chunkPos = new ChunkPos(blockPos);
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			return false;
		}
		BlockEntity blockEntity = chunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.CHECK);
		if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
			return true;
		}
		if (!targetBlockEntity.hasPendingLoadBlockStateSync()) {
			return true;
		}
		targetBlockEntity.consumePendingLoadBlockStateSync();
		return true;
	}

	private static void requeueTask(MinecraftServer server, PendingCoreTask pendingTask) {
		if (server == null || pendingTask == null) {
			return;
		}
		synchronized (PENDING_TASKS_BY_SERVER) {
			PENDING_TASKS_BY_SERVER
				.computeIfAbsent(server, ignored -> new LinkedHashMap<>())
				.put(pendingTask.key(), pendingTask);
		}
	}

	/**
	 * 清理指定服务端的待处理任务与启动标记。
	 */
	private static void clearServerState(MinecraftServer server) {
		if (server == null) {
			return;
		}
		synchronized (PENDING_TASKS_BY_SERVER) {
			STARTED_SERVERS.remove(server);
			PENDING_TASKS_BY_SERVER.remove(server);
		}
	}

	/**
	 * 超过重试预算后记录一次明确告警，便于排查为何该位置始终未等到可读 chunk。
	 */
	private static void logGiveUp(PendingCoreTask pendingTask, int maxRetry) {
		if (pendingTask == null) {
			return;
		}
		BlockPos blockPos = pendingTask.blockPos();
		RedstoneLink.LOGGER.warn(
			"core load resync gave up after {} retries: dimension={}, pos=({}, {}, {})",
			Math.max(0, maxRetry),
			pendingTask.dimension().location(),
			blockPos.getX(),
			blockPos.getY(),
			blockPos.getZ()
		);
	}

	/**
	 * 待处理 core 键：按维度 + 方块位置去重。
	 */
	private record PendingCoreKey(ResourceKey<Level> dimension, long blockPosLong) {}

	/**
	 * 待处理 core 任务。
	 */
	private record PendingCoreTask(ResourceKey<Level> dimension, BlockPos blockPos, int attempt) {
		private PendingCoreKey key() {
			return new PendingCoreKey(dimension, blockPos.asLong());
		}

		private PendingCoreTask nextAttempt() {
			return new PendingCoreTask(dimension, blockPos, attempt + 1);
		}
	}
}
