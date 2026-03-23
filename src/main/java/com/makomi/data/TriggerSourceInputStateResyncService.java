package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
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
 * triggerSource 输入状态静默重采样服务。
 * <p>
 * 在区块加载后按当前真实输入校正 emitter 的 `POWERED` 与观测缓存，
 * 但不触发 linked targets 派发，避免把启动期校正误当成新的来源事件。
 * </p>
 * <p>
 * 实现约束：
 * 1. `CHUNK_LOAD` 阶段只做预筛选和入队，不直接写世界；
 * 2. 仅在 `SERVER_STARTED` 后于服务端 tick 消费；
 * 3. 消费阶段只用 `getChunkNow(...)` 非阻塞取块，未就绪则有限重试。
 * </p>
 */
public final class TriggerSourceInputStateResyncService {
	private static boolean registered;
	private static final Map<MinecraftServer, LinkedHashMap<PendingChunkKey, PendingChunkTask>> PENDING_TASKS_BY_SERVER =
		new IdentityHashMap<>();
	private static final Set<MinecraftServer> STARTED_SERVERS = Collections.newSetFromMap(new IdentityHashMap<>());

	private TriggerSourceInputStateResyncService() {
	}

	/**
	 * 注册区块加载重采样钩子。
	 */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ServerChunkEvents.CHUNK_LOAD.register(TriggerSourceInputStateResyncService::enqueueChunkResync);
		ServerTickEvents.END_SERVER_TICK.register(TriggerSourceInputStateResyncService::consumePendingTasks);
		ServerLifecycleEvents.SERVER_STARTED.register(TriggerSourceInputStateResyncService::markServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(TriggerSourceInputStateResyncService::clearServerState);
		ServerLifecycleEvents.SERVER_STOPPED.register(TriggerSourceInputStateResyncService::clearServerState);
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
	 * 区块加载时先完成一次精细扫描，仅当区块内确实存在待处理 emitter 时才入队。
	 */
	private static void enqueueChunkResync(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}
		List<BlockPos> targetPositions = collectResyncTargetPositions(chunk);
		if (targetPositions.isEmpty()) {
			return;
		}
		ChunkPos chunkPos = chunk.getPos();
		requeueTask(server, new PendingChunkTask(level.dimension(), new ChunkPos(chunkPos.x, chunkPos.z), targetPositions, 0));
	}

	/**
	 * 扫描当前区块内所有待处理 emitter，直接收集位置列表，避免成功消费时再次全扫区块实体。
	 */
	private static List<BlockPos> collectResyncTargetPositions(LevelChunk chunk) {
		if (chunk == null) {
			return List.of();
		}
		List<BlockPos> targetPositions = new ArrayList<>();
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (!(blockEntity instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity)) {
				continue;
			}
			if (
				triggerSourceBlockEntity.hasPendingLoadInputStateResync()
					&& triggerSourceBlockEntity.getBlockState().getBlock() instanceof LinkSignalEmitterBlock
			) {
				targetPositions.add(triggerSourceBlockEntity.getBlockPos().immutable());
			}
		}
		return targetPositions.isEmpty() ? List.of() : List.copyOf(targetPositions);
	}

	/**
	 * 服务端启动完成后，在 tick 末以非阻塞方式消费待处理区块。
	 */
	private static void consumePendingTasks(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int maxRetry = RedstoneLinkConfig.runtimeLoadResyncMaxRetry();
		List<PendingChunkTask> pendingTasks;
		synchronized (PENDING_TASKS_BY_SERVER) {
			if (!STARTED_SERVERS.contains(server)) {
				return;
			}
			LinkedHashMap<PendingChunkKey, PendingChunkTask> pendingTaskMap = PENDING_TASKS_BY_SERVER.get(server);
			if (pendingTaskMap == null || pendingTaskMap.isEmpty()) {
				return;
			}
			pendingTasks = new ArrayList<>(pendingTaskMap.values());
			pendingTaskMap.clear();
		}
		for (PendingChunkTask pendingTask : pendingTasks) {
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

	private static boolean consumeTask(MinecraftServer server, PendingChunkTask pendingTask) {
		ServerLevel level = server.getLevel(pendingTask.dimension());
		if (level == null) {
			return false;
		}
		ChunkPos chunkPos = pendingTask.chunkPos();
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			return false;
		}
		resyncChunk(level, chunk, pendingTask.targetPositions());
		return true;
	}

	private static void requeueTask(MinecraftServer server, PendingChunkTask pendingTask) {
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
	 * 清理指定服务端的待处理队列与启动标记，避免停服边界残留任务。
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
	 * 对指定区块中的指定 emitter 位置做静默重采样。
	 */
	private static void resyncChunk(ServerLevel level, LevelChunk chunk, List<BlockPos> targetPositions) {
		if (level == null || chunk == null || targetPositions == null || targetPositions.isEmpty()) {
			return;
		}
		for (BlockPos targetPos : targetPositions) {
			if (targetPos == null) {
				continue;
			}
			BlockEntity blockEntity = chunk.getBlockEntity(targetPos, LevelChunk.EntityCreationType.CHECK);
			if (!(blockEntity instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity)) {
				continue;
			}
			if (!triggerSourceBlockEntity.hasPendingLoadInputStateResync()) {
				continue;
			}
			if (!(triggerSourceBlockEntity.getBlockState().getBlock() instanceof LinkSignalEmitterBlock signalEmitterBlock)) {
				continue;
			}
			signalEmitterBlock.resyncPoweredStateFromCurrentInputsWithoutTrigger(
				level,
				triggerSourceBlockEntity.getBlockPos(),
				triggerSourceBlockEntity.getBlockState()
			);
			triggerSourceBlockEntity.clearPendingLoadInputStateResync();
		}
	}

	/**
	 * 超过重试预算后记录一次明确告警，避免任务静默消失。
	 */
	private static void logGiveUp(PendingChunkTask pendingTask, int maxRetry) {
		if (pendingTask == null) {
			return;
		}
		RedstoneLink.LOGGER.warn(
			"triggerSource load resync gave up after {} retries: dimension={}, chunk=({}, {}), targets={}",
			Math.max(0, maxRetry),
			pendingTask.dimension().location(),
			pendingTask.chunkPos().x,
			pendingTask.chunkPos().z,
			pendingTask.targetPositions().size()
		);
	}

	/**
	 * 待处理区块键：用于按维度 + 区块位置去重。
	 */
	private record PendingChunkKey(ResourceKey<Level> dimension, long chunkLongKey) {}

	/**
	 * 待处理区块任务。
	 */
	private record PendingChunkTask(ResourceKey<Level> dimension, ChunkPos chunkPos, List<BlockPos> targetPositions, int attempt) {
		private PendingChunkTask {
			targetPositions = targetPositions == null || targetPositions.isEmpty() ? List.of() : List.copyOf(targetPositions);
		}

		private PendingChunkKey key() {
			return new PendingChunkKey(dimension, chunkPos.toLong());
		}

		private PendingChunkTask nextAttempt() {
			return new PendingChunkTask(dimension, chunkPos, targetPositions, attempt + 1);
		}
	}
}
