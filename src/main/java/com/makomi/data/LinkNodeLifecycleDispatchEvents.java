package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
	private static final int TARGET_CHUNK_LOAD_REPLAY_MAX_RETRY = 40;
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
		MinecraftServer server = level.getServer();
		LifecycleState state = getOrCreateState(server);
		if (online) {
			CrossChunkDispatchService.notifyTargetChunkLoaded(server, level.dimension(), chunk.getPos());
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
				if (
					RedstoneLinkConfig.crossChunk().syncTargetChunkLoadReplayEnabled()
						&& LinkNodeSemantics.isAllowedForRole(nodeType, LinkNodeSemantics.Role.TARGET)
				) {
					tryHandleTargetChunkLoadReplay(server, state, level.dimension(), nodeType, serial);
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
		state.pendingTargetChunkLoadReplays.clear();
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
		state.pendingTargetChunkLoadReplays.clear();
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
		consumePendingTargetChunkLoadReplays(server, state);
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

		int budget = Math.max(1, RedstoneLinkConfig.crossChunk().dispatchMaxPerTick());
		List<StartupReplayTask> drainedTasks = drainStartupReplayBatch(state, budget);
		if (drainedTasks.isEmpty()) {
			return;
		}
		LinkedHashMap<StartupReplayKey, StartupReplayTask> retryQueue = new LinkedHashMap<>();
		for (StartupReplayTask task : drainedTasks) {
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
	 * 处理 target chunk load replay。
	 * <p>
	 * 默认先在当前 tick 立即尝试一次；只有目标仍未真正就绪时，才回落到下一 tick 的本地重试队列。
	 * 若配置显式关闭“立即尝试优先”，则保持统一延后一 tick 的保守行为。
	 * </p>
	 */
	private static void tryHandleTargetChunkLoadReplay(
		MinecraftServer server,
		LifecycleState state,
		ResourceKey<Level> dimension,
		LinkNodeType nodeType,
		long serial
	) {
		if (state == null || dimension == null || nodeType == null || serial <= 0L) {
			return;
		}
		TargetChunkLoadReplayTask task = new TargetChunkLoadReplayTask(dimension, nodeType, serial, 0);
		if (!RedstoneLinkConfig.crossChunk().syncTargetChunkLoadReplayImmediateAttemptFirst()) {
			enqueueTargetChunkLoadReplay(state, dimension, nodeType, serial);
			return;
		}
		TargetChunkLoadReplayConsumeResult result = consumeTargetChunkLoadReplayTask(server, task);
		if (result == TargetChunkLoadReplayConsumeResult.DEFERRED) {
			enqueueTargetChunkLoadReplay(state, dimension, nodeType, serial);
		}
	}

	/**
	 * 将 target chunk load replay 延后一 tick 执行，作为“当前 tick 未就绪”的本地兜底。
	 */
	private static void enqueueTargetChunkLoadReplay(
		LifecycleState state,
		ResourceKey<Level> dimension,
		LinkNodeType nodeType,
		long serial
	) {
		if (state == null || dimension == null || nodeType == null || serial <= 0L) {
			return;
		}
		TargetChunkLoadReplayTask task = new TargetChunkLoadReplayTask(dimension, nodeType, serial, 0);
		state.pendingTargetChunkLoadReplays.putIfAbsent(task.key(), task);
	}

	/**
	 * 启动稳定后消费 target chunk load replay 队列。
	 * <p>
	 * 该队列只负责兜底“当前 tick 仍未完全就绪”的目标，不再默认承接所有 `CHUNK_LOAD` replay。
	 * </p>
	 */
	private static void consumePendingTargetChunkLoadReplays(MinecraftServer server, LifecycleState state) {
		if (server == null || state == null || !state.serverStarted) {
			return;
		}
		if (state.pendingTargetChunkLoadReplays.isEmpty()) {
			return;
		}

		int budget = Math.max(1, RedstoneLinkConfig.crossChunk().dispatchMaxPerTick());
		List<TargetChunkLoadReplayTask> drainedTasks = drainTargetChunkLoadReplayBatch(state, budget);
		if (drainedTasks.isEmpty()) {
			return;
		}
		LinkedHashMap<TargetChunkLoadReplayKey, TargetChunkLoadReplayTask> retryQueue = new LinkedHashMap<>();
		for (TargetChunkLoadReplayTask task : drainedTasks) {
			TargetChunkLoadReplayConsumeResult result = consumeTargetChunkLoadReplayTask(server, task);
			if (result == TargetChunkLoadReplayConsumeResult.COMPLETED) {
				continue;
			}
			if (result == TargetChunkLoadReplayConsumeResult.DEFERRED) {
				TargetChunkLoadReplayTask retryTask = task.nextAttempt();
				if (retryTask.attempt() <= TARGET_CHUNK_LOAD_REPLAY_MAX_RETRY) {
					retryQueue.put(retryTask.key(), retryTask);
				}
			}
		}
		if (!retryQueue.isEmpty()) {
			state.pendingTargetChunkLoadReplays.putAll(retryQueue);
		}
	}

	/**
	 * 先从启动回放队列摘出一个批次，再在队列外消费。
	 * <p>
	 * 这样即便消费过程中再次触发新的 lifecycle replay，也只会留到下一 tick，
	 * 不会修改当前正在遍历的 `LinkedHashMap`。
	 * </p>
	 */
	private static List<StartupReplayTask> drainStartupReplayBatch(LifecycleState state, int budget) {
		if (state == null || budget <= 0 || state.pendingStartupChunkLoadReplays.isEmpty()) {
			return List.of();
		}
		List<StartupReplayTask> drainedTasks = new ArrayList<>(Math.min(budget, state.pendingStartupChunkLoadReplays.size()));
		Iterator<Map.Entry<StartupReplayKey, StartupReplayTask>> iterator = state.pendingStartupChunkLoadReplays.entrySet().iterator();
		while (iterator.hasNext() && drainedTasks.size() < budget) {
			Map.Entry<StartupReplayKey, StartupReplayTask> entry = iterator.next();
			drainedTasks.add(entry.getValue());
			iterator.remove();
		}
		return drainedTasks;
	}

	/**
	 * 先从 target chunk load replay 队列摘出一个批次，再在队列外消费。
	 * <p>
	 * 进入地图时，消费某个任务可能继续触发新的 target replay 入队；
	 * 批次摘取后再执行可避免同 tick 的重入写入打断当前迭代。
	 * </p>
	 */
	private static List<TargetChunkLoadReplayTask> drainTargetChunkLoadReplayBatch(LifecycleState state, int budget) {
		if (state == null || budget <= 0 || state.pendingTargetChunkLoadReplays.isEmpty()) {
			return List.of();
		}
		List<TargetChunkLoadReplayTask> drainedTasks = new ArrayList<>(Math.min(budget, state.pendingTargetChunkLoadReplays.size()));
		Iterator<Map.Entry<TargetChunkLoadReplayKey, TargetChunkLoadReplayTask>> iterator = state.pendingTargetChunkLoadReplays
			.entrySet()
			.iterator();
		while (iterator.hasNext() && drainedTasks.size() < budget) {
			Map.Entry<TargetChunkLoadReplayKey, TargetChunkLoadReplayTask> entry = iterator.next();
			drainedTasks.add(entry.getValue());
			iterator.remove();
		}
		return drainedTasks;
	}

	/**
	 * 消费单条 target chunk load replay 任务。
	 */
	private static TargetChunkLoadReplayConsumeResult consumeTargetChunkLoadReplayTask(
		MinecraftServer server,
		TargetChunkLoadReplayTask task
	) {
		if (server == null || task == null) {
			return TargetChunkLoadReplayConsumeResult.DROPPED;
		}
		ServerLevel level = server.getLevel(task.dimension());
		if (level == null) {
			return TargetChunkLoadReplayConsumeResult.DEFERRED;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		Set<Long> linkedPeers = savedData.linkedTargetsViewBySourceType(task.nodeType(), task.serial());
		if (linkedPeers.isEmpty()) {
			return TargetChunkLoadReplayConsumeResult.COMPLETED;
		}
		LinkSavedData.RuntimeOnlineProbeResult probeResult = savedData.probeRuntimeOnlineNodeNonBlocking(
			level,
			task.nodeType(),
			task.serial()
		);
		if (probeResult.retryable()) {
			return TargetChunkLoadReplayConsumeResult.DEFERRED;
		}
		if (!probeResult.ready()) {
			return TargetChunkLoadReplayConsumeResult.DROPPED;
		}
		InternalDispatchDeltaEvents.publishLinkAttachedFromTargetChunkLoad(level, task.nodeType(), task.serial(), linkedPeers);
		return TargetChunkLoadReplayConsumeResult.COMPLETED;
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
		if (!RedstoneLinkConfig.crossChunk().runtimeDiagEnabled()) {
			return;
		}
		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
		long thresholdMs = RedstoneLinkConfig.crossChunk().runtimeDiagWarnThresholdMs();
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
	 * target chunk load replay 任务键（维度 + 节点类型 + 序号）。
	 */
	private record TargetChunkLoadReplayKey(ResourceKey<Level> dimension, LinkNodeType nodeType, long serial) {}

	/**
	 * target chunk load replay 任务。
	 */
	private record TargetChunkLoadReplayTask(ResourceKey<Level> dimension, LinkNodeType nodeType, long serial, int attempt) {
		private TargetChunkLoadReplayKey key() {
			return new TargetChunkLoadReplayKey(dimension, nodeType, serial);
		}

		private TargetChunkLoadReplayTask nextAttempt() {
			return new TargetChunkLoadReplayTask(dimension, nodeType, serial, attempt + 1);
		}
	}

	/**
	 * target chunk load replay 任务消费结果。
	 */
	private enum TargetChunkLoadReplayConsumeResult {
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
		private final LinkedHashMap<TargetChunkLoadReplayKey, TargetChunkLoadReplayTask> pendingTargetChunkLoadReplays =
			new LinkedHashMap<>();
	}
}
