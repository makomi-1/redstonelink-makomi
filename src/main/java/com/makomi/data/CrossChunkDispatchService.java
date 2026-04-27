package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 跨区块派发调度服务。
 * <p>
 * 目标区块未加载时，按照配置策略决定是否入队缓存并在后续 tick 派发；
 * 命中强制加载策略时可拉起目标区块，票据到期后自动释放。
 * </p>
 */
public final class CrossChunkDispatchService {
	private static final Map<MinecraftServer, DispatchState> STATE_BY_SERVER = new IdentityHashMap<>();
	static final int TRANSIENT_TICKET_LEVEL = 2;
	static final int RESIDENT_TICKET_LEVEL = 2;
	static final TicketType TRANSIENT_TICKET_TYPE = TicketType.create(
		"redstonelink_transient",
		Comparator.comparingLong(ChunkPos::toLong)
	);
	static final TicketType RESIDENT_TICKET_TYPE = TicketType.create(
		"redstonelink_resident",
		Comparator
			.comparing((ResidentTicketKey key) -> key.role().name())
			.thenComparing(key -> key.type().name())
			.thenComparingLong(ResidentTicketKey::serial)
	);

	private CrossChunkDispatchService() {
	}

	/**
	 * 注册调度事件。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CrossChunkDispatchService::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CrossChunkDispatchService::releaseAllForcedChunksAndClearState);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATE_BY_SERVER.remove(server));
	}

	/**
	 * 记录激活语义（TOGGLE/PULSE）的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNode 目标节点快照
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param activationMode 激活模式
	 * @return 是否已接管该请求（入队或强制加载链路）
	 */
	public static QueueResult queueActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode
	) {
		long enqueueTick = sourceLevel == null ? 0L : sourceLevel.getGameTime();
		return queueActivation(sourceLevel, targetNode, sourceType, sourceSerial, activationMode, enqueueTick, 0);
	}

	/**
	 * 记录激活语义（TOGGLE/PULSE）的延迟派发请求（携带时间键）。
	 */
	public static QueueResult queueActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		if (activationMode == null) {
			return QueueResult.rejected();
		}
		ActivationQueuePolicy queuePolicy = resolveActivationQueuePolicy(activationMode);
		if (queuePolicy == null) {
			return QueueResult.rejected();
		}
		if (queuePolicy.dispatchKind() == CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT) {
			return queueToggleActivation(
				sourceLevel,
				targetNode,
				sourceType,
				sourceSerial,
				activationMode,
				enqueueGameTick,
				enqueueGameSlot,
				queuePolicy
			);
		}
		if (!queuePolicy.ttlRelayEnabled() && !queuePolicy.persistentExperimental()) {
			return QueueResult.rejected();
		}
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			queuePolicy.dispatchKind(),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			activationMode,
			0,
			enqueueGameTick,
			enqueueGameSlot,
			resolveActivationTtlTicks(queuePolicy)
		);
	}

	/**
	 * 记录 SYNC 语义的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNode 目标节点快照
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param signalStrength 同步信号强度（0~15）
	 * @return 是否已接管该请求（入队或强制加载链路）
	 */
	public static QueueResult queueSyncSignal(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		int signalStrength
	) {
		long enqueueTick = sourceLevel == null ? 0L : sourceLevel.getGameTime();
		return queueSyncSignal(sourceLevel, targetNode, sourceType, sourceSerial, signalStrength, enqueueTick, 0);
	}

	/**
	 * 记录 SYNC 语义的延迟派发请求（携带时间键）。
	 */
	public static QueueResult queueSyncSignal(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		int signalStrength,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		long ttlTicks = resolveSyncTtlTicks(normalizedStrength);
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			ActivationMode.TOGGLE,
			normalizedStrength,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	/**
	 * 批量记录激活语义（TOGGLE/PULSE）的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNodes 目标节点快照列表
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param activationMode 激活模式
	 * @return 与输入一一对应的排队结果
	 */
	public static List<QueueResult> queueActivationBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode
	) {
		long enqueueTick = sourceLevel == null ? 0L : sourceLevel.getGameTime();
		return queueActivationBatch(sourceLevel, targetNodes, sourceType, sourceSerial, activationMode, enqueueTick, 0);
	}

	/**
	 * 批量记录激活语义（TOGGLE/PULSE）的延迟派发请求（携带时间键）。
	 */
	public static List<QueueResult> queueActivationBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		if (activationMode == null) {
			return queueRejectedResults(targetNodes);
		}
		ActivationQueuePolicy queuePolicy = resolveActivationQueuePolicy(activationMode);
		if (queuePolicy == null || (!queuePolicy.ttlRelayEnabled() && !queuePolicy.persistentExperimental())) {
			return queueRejectedResults(targetNodes);
		}
		if (queuePolicy.dispatchKind() == CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT) {
			if (targetNodes == null || targetNodes.isEmpty()) {
				return List.of();
			}
			List<QueueResult> queueResults = new ArrayList<>(targetNodes.size());
			for (LinkSavedData.LinkNode targetNode : targetNodes) {
				queueResults.add(
					queueToggleActivation(
						sourceLevel,
						targetNode,
						sourceType,
						sourceSerial,
						activationMode,
						enqueueGameTick,
						enqueueGameSlot,
						queuePolicy
					)
				);
			}
			return List.copyOf(queueResults);
		}
		return queueDispatchBatch(
			sourceLevel,
			targetNodes,
			sourceType,
			sourceSerial,
			queuePolicy.dispatchKind(),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			activationMode,
			0,
			enqueueGameTick,
			enqueueGameSlot,
			resolveActivationTtlTicks(queuePolicy)
		);
	}

	/**
	 * 批量记录 SYNC 语义的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNodes 目标节点快照列表
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param signalStrength 同步信号强度（0~15）
	 * @return 与输入一一对应的排队结果
	 */
	public static List<QueueResult> queueSyncSignalBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		int signalStrength
	) {
		long enqueueTick = sourceLevel == null ? 0L : sourceLevel.getGameTime();
		return queueSyncSignalBatch(sourceLevel, targetNodes, sourceType, sourceSerial, signalStrength, enqueueTick, 0);
	}

	/**
	 * 批量记录 SYNC 语义的延迟派发请求（携带时间键）。
	 */
	public static List<QueueResult> queueSyncSignalBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		int signalStrength,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		long ttlTicks = resolveSyncTtlTicks(normalizedStrength);
		return queueDispatchBatch(
			sourceLevel,
			targetNodes,
			sourceType,
			sourceSerial,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			ActivationMode.TOGGLE,
			normalizedStrength,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	/**
	 * 兼容旧入口：activation 已转为事件语义，离线队列不再接受 REMOVE。
	 * <p>
	 * 调用方应改走 sync/source invalidation 路径，本方法保持拒绝语义避免旧调用误入。
	 * </p>
	 */
	public static QueueResult queueActivationRemove(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		// activation 已改为事件语义，离线队列不再接收 REMOVE。
		return QueueResult.rejected();
	}

	private static QueueResult queueToggleActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		long enqueueGameTick,
		int enqueueGameSlot,
		ActivationQueuePolicy queuePolicy
	) {
		return CrossChunkDispatchQueueSupport.queueToggleActivation(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			activationMode,
			enqueueGameTick,
			enqueueGameSlot,
			queuePolicy
		);
	}

	/**
	 * 记录同步语义来源失效（REMOVE）派发请求。
	 */
	public static QueueResult queueSyncSignalRemove(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		long ttlTicks = RedstoneLinkConfig.crossChunk().queueDefaultTtlTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL,
			CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE,
			ActivationMode.TOGGLE,
			0,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	/**
	 * 记录 triggerSource 区块卸载失效派发请求（仅剔除目标上的 sync 贡献）。
	 */
	public static QueueResult queueTriggerSourceChunkUnloadInvalidationRemove(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		long ttlTicks = RedstoneLinkConfig.crossChunk().queueDefaultTtlTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			CrossChunkDispatchQueueSavedData.DispatchKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
			CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE,
			ActivationMode.TOGGLE,
			0,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	/**
	 * 记录 triggerSource 其它失效派发请求（剔除目标上的 toggle/pulse/sync 贡献）。
	 */
	public static QueueResult queueTriggerSourceInvalidationRemove(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		long enqueueGameTick,
		int enqueueGameSlot
	) {
		long ttlTicks = RedstoneLinkConfig.crossChunk().queueDefaultTtlTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			CrossChunkDispatchQueueSavedData.DispatchKind.TRIGGER_SOURCE_INVALIDATION,
			CrossChunkDispatchQueueSavedData.DispatchAction.REMOVE,
			ActivationMode.TOGGLE,
			0,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	private static QueueResult queueDispatch(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		CrossChunkDispatchQueueSavedData.DispatchKind dispatchKind,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long ttlTicks
	) {
		return CrossChunkDispatchQueueSupport.queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			dispatchKind,
			dispatchAction,
			activationMode,
			syncSignalStrength,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	private static List<QueueResult> queueDispatchBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		CrossChunkDispatchQueueSavedData.DispatchKind dispatchKind,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long ttlTicks
	) {
		return CrossChunkDispatchQueueSupport.queueDispatchBatch(
			sourceLevel,
			targetNodes,
			sourceType,
			sourceSerial,
			dispatchKind,
			dispatchAction,
			activationMode,
			syncSignalStrength,
			enqueueGameTick,
			enqueueGameSlot,
			ttlTicks
		);
	}

	private static long resolveSyncTtlTicks(int normalizedStrength) {
		return CrossChunkDispatchQueueSupport.resolveSyncTtlTicks(normalizedStrength);
	}

	private static ActivationQueuePolicy resolveActivationQueuePolicy(ActivationMode activationMode) {
		return CrossChunkDispatchQueueSupport.resolveActivationQueuePolicy(activationMode);
	}

	private static long resolveActivationTtlTicks(ActivationQueuePolicy queuePolicy) {
		return CrossChunkDispatchQueueSupport.resolveActivationTtlTicks(queuePolicy);
	}

	private static List<QueueResult> queueRejectedResults(List<LinkSavedData.LinkNode> targetNodes) {
		return CrossChunkDispatchQueueSupport.queueRejectedResults(targetNodes);
	}

	private static void onServerTick(MinecraftServer server) {
		ServerThreadGuard.requireServerThread(server, "crosschunk dispatch tick");
		if (server == null || server.overworld() == null) {
			return;
		}
		long gameTime = server.overworld().getGameTime();
		DispatchState state = getOrCreateState(server);
		CrossChunkDispatchQueueSavedData queueData = CrossChunkDispatchQueueSavedData.get(server.overworld());
		syncResidentTickets(server, state);
		resetForceLoadWindow(state, gameTime);
		releaseExpiredForcedChunks(server, state, gameTime);
		processPendingDispatches(server, state, queueData, gameTime);
		if (
			queueData.pendingSize() <= 0
				&& state.forcedChunksUntilTick.isEmpty()
				&& state.residentTickets.isEmpty()
				&& !state.residentSyncArmed
		) {
			STATE_BY_SERVER.remove(server);
		}
	}

	/**
	 * 目标区块加载后，主动唤醒命中该区块的等待中 pending，避免继续卡在初始/退避窗口内。
	 */
	public static void notifyTargetChunkLoaded(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunkPos) {
		ServerThreadGuard.requireServerThread(server, "crosschunk target chunk load notification");
		if (server == null || dimension == null || chunkPos == null || server.overworld() == null) {
			return;
		}
		DispatchState state = STATE_BY_SERVER.get(server);
		if (state == null || state.retryStateByAttemptKey.isEmpty() || state.waitingUnlimitedAttemptKeysByTargetChunk.isEmpty()) {
			return;
		}
		CrossChunkDispatchQueueSavedData queueData = CrossChunkDispatchQueueSavedData.get(server.overworld());
		if (queueData == null || queueData.pendingSize() <= 0) {
			clearRetryTracking(state);
			return;
		}
		long gameTime = resolveGameTimeForTargetChunkLoad(server, dimension);
		notifyTargetChunkLoaded(state, queueData, dimension, chunkPos, gameTime);
	}

	private static void processPendingDispatches(
		MinecraftServer server,
		DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		long gameTime
	) {
		CrossChunkDispatchRuntimeSupport.processPendingDispatches(server, state, queueData, gameTime);
	}

	/**
	 * 目标区块加载时，按维度与区块键唤醒等待中的 pending。
	 *
	 * @return 本次被唤醒的 pending 数
	 */
	private static int notifyTargetChunkLoaded(
		DispatchState state,
		CrossChunkDispatchQueueSavedData queueData,
		ResourceKey<Level> dimension,
		ChunkPos chunkPos,
		long gameTime
	) {
		return CrossChunkDispatchRuntimeSupport.notifyTargetChunkLoaded(state, queueData, dimension, chunkPos, gameTime);
	}

	/**
	 * 清空重试状态与目标区块唤醒索引。
	 */
	private static void clearRetryTracking(DispatchState state) {
		CrossChunkDispatchRuntimeSupport.clearRetryTracking(state);
	}

	/**
	 * 获取目标区块加载通知使用的当前时间键。
	 */
	private static long resolveGameTimeForTargetChunkLoad(MinecraftServer server, ResourceKey<Level> dimension) {
		return CrossChunkDispatchRuntimeSupport.resolveGameTimeForTargetChunkLoad(server, dimension);
	}

	static boolean shouldForceLoad(
		ServerLevel contextLevel,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		return CrossChunkDispatchTicketSupport.shouldForceLoad(contextLevel, pending);
	}

	/**
	 * 同步 resident 白名单对应的常驻区块票据。
	 * <p>
	 * 仅操作本模组自有 TicketType，确保与其它模组强制加载来源隔离。
	 * </p>
	 */
	private static void syncResidentTickets(MinecraftServer server, DispatchState state) {
		CrossChunkDispatchTicketSupport.syncResidentTickets(server, state);
	}

	static void tryForceLoad(
		MinecraftServer server,
		DispatchState state,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime
	) {
		CrossChunkDispatchTicketSupport.tryForceLoad(server, state, pending, gameTime);
	}

	private static void releaseExpiredForcedChunks(MinecraftServer server, DispatchState state, long gameTime) {
		CrossChunkDispatchTicketSupport.releaseExpiredForcedChunks(server, state, gameTime);
	}

	/**
	 * 停服前主动释放当前服务端所有强制加载票据，并清理调度状态。
	 * <p>
	 * 用于兜底处理“未等到过期释放就停服”的场景，避免 forced chunk 状态跨重启残留。
	 * </p>
	 *
	 * @param server 当前服务端
	 */
	private static void releaseAllForcedChunksAndClearState(MinecraftServer server) {
		ServerThreadGuard.requireServerThread(server, "crosschunk forced chunk release");
		DispatchState state = STATE_BY_SERVER.get(server);
		CrossChunkDispatchTicketSupport.releaseAllForcedChunksAndClearState(server, state, STATE_BY_SERVER);
	}

	private static void resetForceLoadWindow(DispatchState state, long gameTime) {
		CrossChunkDispatchTicketSupport.resetForceLoadWindow(state, gameTime);
	}

	static DispatchState getOrCreateState(MinecraftServer server) {
		ServerThreadGuard.requireServerThread(server, "crosschunk dispatch state access");
		return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new DispatchState());
	}

	/**
	 * 跨区块排队结果快照。
	 *
	 * @param accepted 是否接管请求（已入队或将尝试强加载）
	 * @param forceLoadPlanned 是否命中强加载策略
	 */
	public record QueueResult(boolean accepted, boolean forceLoadPlanned) {
		private static QueueResult rejected() {
			return new QueueResult(false, false);
		}
	}

	record ActivationQueuePolicy(
		CrossChunkDispatchQueueSavedData.DispatchKind dispatchKind,
		boolean ttlRelayEnabled,
		boolean persistentExperimental,
		int ttlTicks
	) {}

	record SourceKey(LinkNodeType sourceType, long sourceSerial) {}

	record ForcedChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	record ResidentTicketKey(LinkNodeSemantics.Role role, LinkNodeType type, long serial) {}

	record ResidentChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	record BatchCandidate(int requestIndex, boolean forceLoadPlanned) {}

	record PendingAttemptKey(CrossChunkDispatchQueueSavedData.DispatchKey key, long version) {}

	record TargetChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	static final class RetryState {
		int attempts;
		long nextEligibleTick = Long.MIN_VALUE;
		TargetChunkKey targetChunkKey;
		boolean warnLogged;
		boolean errorLogged;
		boolean stagedBackoffLogged;
		boolean dropLogged;
	}

	/**
	 * 单服调度状态缓存。
	 */
	static final class DispatchState {
		final Map<ForcedChunkKey, Long> forcedChunksUntilTick = new HashMap<>();
		final Map<ResidentTicketKey, ResidentChunkKey> residentTickets = new HashMap<>();
		final Map<ResidentTicketKey, ResidentChunkKey> residentDesiredTicketsScratch = new HashMap<>();
		final Map<SourceKey, Integer> forceLoadCountBySource = new HashMap<>();
		final Map<PendingAttemptKey, RetryState> retryStateByAttemptKey = new HashMap<>();
		final Map<TargetChunkKey, Set<PendingAttemptKey>> waitingUnlimitedAttemptKeysByTargetChunk = new HashMap<>();
		final Set<PendingAttemptKey> retryActiveKeysScratch = new java.util.HashSet<>();
		final List<PendingAttemptKey> wakeAttemptSnapshotScratch = new ArrayList<>();
		final CrossChunkDispatchRuntimeSupport.TargetLocatorCache targetLocatorCache =
			new CrossChunkDispatchRuntimeSupport.TargetLocatorCache();
		final CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache readyDrainCache =
			new CrossChunkDispatchRuntimeSupport.ChunkReadyDrainCache();
		boolean residentSyncArmed;
		long residentWhitelistVersion = Long.MIN_VALUE;
		long residentActivatorVersion = Long.MIN_VALUE;
		long residentRuntimeNodeVersion = Long.MIN_VALUE;
		long forceLoadWindowTick = Long.MIN_VALUE;
		int forceLoadCountThisTick;
		long pendingCursor;
	}
}
