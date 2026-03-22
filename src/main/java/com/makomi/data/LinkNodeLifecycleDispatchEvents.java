package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 节点生命周期与内部 delta 事件桥接器。
 * <p>
 * 负责将“区块加载/卸载”引起的来源/目标上下线统一翻译为内部事件：
 * 1. `CHUNK_LOAD` -> `UPSERT`（按可恢复来源态补发）；
 * 2. `CHUNK_UNLOAD` -> triggerSource 区块卸载失效（仅对来源节点生效）。
 * </p>
 */
public final class LinkNodeLifecycleDispatchEvents {
	private static final int STARTUP_REPLAY_MAX_RETRY = 40;
	private static final Map<MinecraftServer, LifecycleState> STATE_BY_SERVER = new IdentityHashMap<>();

	private LinkNodeLifecycleDispatchEvents() {
	}

	/**
	 * 注册区块生命周期事件。
	 */
	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> publishChunkLifecycle(level, chunk, true));
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> publishChunkLifecycle(level, chunk, false));
		ServerLifecycleEvents.SERVER_STARTING.register(LinkNodeLifecycleDispatchEvents::onServerStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(LinkNodeLifecycleDispatchEvents::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(LinkNodeLifecycleDispatchEvents::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(LinkNodeLifecycleDispatchEvents::onServerStopped);
		ServerTickEvents.START_SERVER_TICK.register(LinkNodeLifecycleDispatchEvents::onStartServerTick);
	}

	/**
	 * 将区块内可配对节点统一投影为“信道建立/失效”事件。
	 */
	private static void publishChunkLifecycle(ServerLevel level, LevelChunk chunk, boolean online) {
		if (level == null || chunk == null) {
			return;
		}
		if (shouldSkipLifecycleReplay(level, chunk, online)) {
			return;
		}
		if (online) {
			CrossChunkDispatchService.notifyTargetChunkLoaded(level.getServer(), level.dimension(), chunk.getPos());
		}
		long startNs = System.nanoTime();
		LinkSavedData savedData = LinkSavedData.get(level);
		EventMeta eventMeta = EventMeta.of(level.getGameTime(), 0, 0L);
		int scannedBlockEntities = chunk.getBlockEntities().size();
		int linkedNodeCount = 0;
		int linkedPeerCount = 0;
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
				continue;
			}
			long serial = pairableNodeBlockEntity.getSerial();
			if (serial <= 0L) {
				continue;
			}
			LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
			Set<Long> linkedPeers = savedData.linkedTargetsViewBySourceType(nodeType, serial);
			if (linkedPeers.isEmpty()) {
				continue;
			}
			linkedNodeCount++;
			linkedPeerCount += linkedPeers.size();
			if (online) {
				if (RedstoneLinkConfig.crossChunkSyncTargetChunkLoadReplayEnabled()) {
					InternalDispatchDeltaEvents.publishLinkAttachedFromTargetChunkLoad(level, nodeType, serial, linkedPeers);
				}
				continue;
			}
			if (!LinkNodeSemantics.isAllowedForRole(nodeType, LinkNodeSemantics.Role.SOURCE)) {
				continue;
			}
			InternalDispatchDeltaEvents.publishLinkChunkUnloaded(level, nodeType, serial, linkedPeers, eventMeta);
		}
		logChunkLifecycleIfSlow(level, chunk, online, scannedBlockEntities, linkedNodeCount, linkedPeerCount, startNs);
	}

	/**
	 * 启动期/停服期生命周期回放隔离门。
	 * <p>
	 * 目的：
	 * 1. SERVER_STARTED 前屏蔽 CHUNK_LOAD 回放，避免启动关键路径执行补发重建；
	 * 2. 启动准备阶段（gameTime<=0）屏蔽剩余 lifecycle 回放；
	 * 3. 停服阶段仅屏蔽 CHUNK_UNLOAD 回放，避免关服边界产生额外写入。
	 * </p>
	 */
	private static boolean shouldSkipLifecycleReplay(ServerLevel level, LevelChunk chunk, boolean online) {
		if (level == null || chunk == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		LifecycleState state = getOrCreateState(server);
		if (online && !state.serverStarted) {
			enqueuePreStartedLoadReplay(state, level, chunk);
			return true;
		}
		if (level.getGameTime() <= 0L && !state.serverStarted) {
			return true;
		}
		if (!online && state.serverStopping) {
			return true;
		}
		return false;
	}

	/**
	 * 服务端进入 starting 阶段时记录启动起点。
	 */
	private static void onServerStarting(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStopping = false;
		state.serverStarted = false;
		state.pendingStartupChunkLoadReplays.clear();
	}

	/**
	 * 服务端启动后重置诊断计数与停服标志。
	 */
	private static void onServerStarted(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStopping = false;
		state.serverStarted = true;
	}

	/**
	 * 服务端进入停服阶段时，标记 unload 回放隔离状态。
	 */
	private static void onServerStopping(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStopping = true;
		state.serverStarted = false;
		state.pendingStartupChunkLoadReplays.clear();
	}

	/**
	 * 服务端完全停止后清理状态缓存，避免跨会话残留。
	 */
	private static void onServerStopped(MinecraftServer server) {
		synchronized (STATE_BY_SERVER) {
			STATE_BY_SERVER.remove(server);
		}
	}

	/**
	 * 服务端 tick 起始阶段消费预启动回放队列。
	 */
	private static void onStartServerTick(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		consumePendingStartupReplays(server, state);
	}

	/**
	 * 将 SERVER_STARTED 前收到的 CHUNK_LOAD 事件入队，等待启动后按预算回放。
	 */
	private static void enqueuePreStartedLoadReplay(LifecycleState state, ServerLevel level, LevelChunk chunk) {
		if (state == null || level == null || chunk == null) {
			return;
		}
		ChunkPos chunkPos = chunk.getPos();
		StartupReplayTask task = new StartupReplayTask(level.dimension(), new ChunkPos(chunkPos.x, chunkPos.z), 0);
		state.pendingStartupChunkLoadReplays.putIfAbsent(task.key(), task);
	}

	/**
	 * 启动后按预算消费预启动 CHUNK_LOAD 回放队列，避免一次性重放阻塞主线程。
	 */
	private static void consumePendingStartupReplays(MinecraftServer server, LifecycleState state) {
		if (server == null || state == null || !state.serverStarted) {
			return;
		}
		if (state.pendingStartupChunkLoadReplays.isEmpty()) {
			return;
		}

		int budget = Math.max(1, RedstoneLinkConfig.crossChunkDispatchMaxPerTick());
		int consumed = 0;
		LinkedHashMap<StartupReplayKey, StartupReplayTask> retryQueue = new LinkedHashMap<>();
		Iterator<Map.Entry<StartupReplayKey, StartupReplayTask>> iterator = state.pendingStartupChunkLoadReplays.entrySet().iterator();
		while (iterator.hasNext() && consumed < budget) {
			Map.Entry<StartupReplayKey, StartupReplayTask> entry = iterator.next();
			StartupReplayTask task = entry.getValue();
			iterator.remove();
			consumed++;
			StartupReplayConsumeResult result = consumeStartupReplayTask(server, task);
			if (result == StartupReplayConsumeResult.COMPLETED) {
				continue;
			}
			if (result == StartupReplayConsumeResult.DEFERRED) {
				StartupReplayTask retryTask = task.nextAttempt();
				if (retryTask.attempt() <= STARTUP_REPLAY_MAX_RETRY) {
					retryQueue.put(retryTask.key(), retryTask);
				}
				continue;
			}
		}
		if (!retryQueue.isEmpty()) {
			state.pendingStartupChunkLoadReplays.putAll(retryQueue);
		}
	}

	/**
	 * 消费单条预启动 CHUNK_LOAD 回放任务。
	 */
	private static StartupReplayConsumeResult consumeStartupReplayTask(MinecraftServer server, StartupReplayTask task) {
		if (server == null || task == null) {
			return StartupReplayConsumeResult.DROPPED;
		}
		ServerLevel level = server.getLevel(task.dimension());
		if (level == null) {
			return StartupReplayConsumeResult.DEFERRED;
		}
		ChunkPos chunkPos = task.chunkPos();
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			return StartupReplayConsumeResult.DEFERRED;
		}
		publishChunkLifecycle(level, chunk, true);
		return StartupReplayConsumeResult.COMPLETED;
	}

	/**
	 * 获取服务器级生命周期状态；按需懒初始化。
	 */
	private static LifecycleState getOrCreateState(MinecraftServer server) {
		synchronized (STATE_BY_SERVER) {
			return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new LifecycleState());
		}
	}

	/**
	 * 记录区块生命周期慢路径耗时，便于定位 TP/重载卡顿热点。
	 */
	private static void logChunkLifecycleIfSlow(
		ServerLevel level,
		LevelChunk chunk,
		boolean online,
		int scannedBlockEntities,
		int linkedNodeCount,
		int linkedPeerCount,
		long startNs
	) {
		if (!RedstoneLinkConfig.runtimeDiagEnabled()) {
			return;
		}
		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
		long thresholdMs = RedstoneLinkConfig.runtimeDiagWarnThresholdMs();
		if (elapsedMs < thresholdMs) {
			return;
		}
		RedstoneLink.LOGGER.warn(
			"[DiagRuntime] chunk_lifecycle_slow event={}, dimension={}, chunk=({}, {}), elapsedMs={}, thresholdMs={}, scannedBlockEntities={}, linkedNodes={}, linkedPeers={}",
			online ? "CHUNK_LOAD" : "CHUNK_UNLOAD",
			level.dimension().location(),
			chunk.getPos().x,
			chunk.getPos().z,
			elapsedMs,
			thresholdMs,
			scannedBlockEntities,
			linkedNodeCount,
			linkedPeerCount
		);
	}

	/**
	 * 预启动 CHUNK_LOAD 回放键（维度 + 区块 long 键）。
	 */
	private record StartupReplayKey(ResourceKey<Level> dimension, long chunkKey) {}

	/**
	 * 预启动 CHUNK_LOAD 回放任务。
	 */
	private record StartupReplayTask(ResourceKey<Level> dimension, ChunkPos chunkPos, int attempt) {
		private StartupReplayKey key() {
			return new StartupReplayKey(dimension, chunkPos.toLong());
		}

		private StartupReplayTask nextAttempt() {
			return new StartupReplayTask(dimension, chunkPos, attempt + 1);
		}
	}

	/**
	 * 预启动回放任务消费结果。
	 */
	private enum StartupReplayConsumeResult {
		COMPLETED,
		DEFERRED,
		DROPPED
	}

	/**
	 * 生命周期状态。
	 */
	private static final class LifecycleState {
		private boolean serverStopping;
		private boolean serverStarted;
		private final LinkedHashMap<StartupReplayKey, StartupReplayTask> pendingStartupChunkLoadReplays = new LinkedHashMap<>();
	}
}
