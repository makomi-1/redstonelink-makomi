package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.NeighborFanoutUtil;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 已连接目标派发复用服务。
 * <p>
 * 统一封装“来源序号 -> 目标序号集合”的激活/同步派发流程，
 * 并在跨区块接管时返回可用于提示的汇总信息。
 * </p>
 */
public final class LinkedTargetDispatchService {
	private static final Object FANOUT_DIAG_LOCK = new Object();
	private static NeighborFanoutUtil.FanoutDiagnosticsSnapshot lastLoggedFanoutDiagnostics =
		new NeighborFanoutUtil.FanoutDiagnosticsSnapshot(0L, 0L, 0L, 0L, 0L, 0L);

	private LinkedTargetDispatchService() {
	}

	/**
	 * 派发激活语义（TOGGLE/PULSE）到目标集合。
	 *
	 * @param sourceLevel 来源所在服务端维度
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源节点序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param activationMode 激活模式
	 * @return 派发汇总
	 */
	public static DispatchSummary dispatchActivation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		ActivationMode activationMode
	) {
		if (activationMode == null) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			null,
			targetType,
			targetSerials,
			DispatchKind.ACTIVATION,
			activationMode,
			0,
			null,
			EventMeta.now(sourceLevel)
		);
	}

	/**
	 * 按来源节点当前连接模式自动解析目标集合后，再派发激活语义。
	 */
	public static DispatchSummary dispatchActivation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		ActivationMode activationMode
	) {
		return dispatchActivation(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			resolveDispatchTargets(sourceLevel, sourceType, sourceSerial, targetType),
			activationMode
		);
	}

	/**
	 * 派发同步语义（SYNC）到目标集合。
	 *
	 * @param sourceLevel 来源所在服务端维度
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源节点序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param signalStrength 同步输入强度（0~15）
	 * @return 派发汇总
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		int signalStrength
	) {
		return dispatchSyncSignal(
			sourceLevel,
			sourceType,
			sourceSerial,
			null,
			targetType,
			targetSerials,
			signalStrength,
			null,
			EventMeta.now(sourceLevel)
		);
	}

	/**
	 * 按来源节点当前连接模式自动解析目标集合后，再派发同步语义。
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		int signalStrength
	) {
		return dispatchSyncSignal(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			resolveDispatchTargets(sourceLevel, sourceType, sourceSerial, targetType),
			signalStrength
		);
	}

	/**
	 * 派发同步语义（SYNC）到目标集合，并显式携带来源坐标。
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		BlockPos sourcePos,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		int signalStrength,
		com.makomi.block.entity.SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			sourcePos == null ? null : sourcePos.immutable(),
			targetType,
			targetSerials,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			normalizedStrength,
			previousSnapshot,
			eventMeta
		);
	}

	/**
	 * 按来源节点当前连接模式自动解析目标集合后，再派发同步语义，并显式携带来源坐标。
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		BlockPos sourcePos,
		LinkNodeType targetType,
		int signalStrength,
		com.makomi.block.entity.SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		return dispatchSyncSignal(
			sourceLevel,
			sourceType,
			sourceSerial,
			sourcePos,
			targetType,
			resolveDispatchTargets(sourceLevel, sourceType, sourceSerial, targetType),
			signalStrength,
			previousSnapshot,
			eventMeta
		);
	}

	/**
	 * 派发同步语义（SYNC）到目标集合，并在被过滤器拦截时按旧快照补做失效清理。
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		int signalStrength,
		com.makomi.block.entity.SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			null,
			targetType,
			targetSerials,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			normalizedStrength,
			previousSnapshot,
			eventMeta
		);
	}

	/**
	 * 按当前配置构建跨区块接管提示消息列表。
	 *
	 * @param summary 派发汇总
	 * @return 需发送给玩家的多行提示
	 */
	public static List<Component> buildCrossChunkNotifyMessages(DispatchSummary summary) {
		if (summary == null || !summary.hasCrossChunkHandled()) {
			return List.of();
		}
		int displayLimit = RedstoneLinkConfig.crossChunk().notifyMode() == RedstoneLinkConfig.CrossChunkNotifyMode.DETAILED
			? 50
			: 3;
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("message.redstonelink.crosschunk.notify.header", summary.crossChunkHandledCount()));
		lines.add(
			Component.translatable(
				"message.redstonelink.crosschunk.notify.source",
				LinkNodeSemantics.toSemanticName(summary.sourceType()),
				summary.sourceSerial()
			)
		);
		if (!summary.forceLoadTargetSerials().isEmpty()) {
			lines.add(
				Component.translatable(
					"message.redstonelink.crosschunk.notify.force_load_targets",
					formatNotifyTargets(summary.targetType(), summary.forceLoadTargetSerials(), displayLimit)
				)
			);
		}
		if (!summary.relayTargetSerials().isEmpty()) {
			lines.add(
				Component.translatable(
					"message.redstonelink.crosschunk.notify.relay_targets",
					formatNotifyTargets(summary.targetType(), summary.relayTargetSerials(), displayLimit)
				)
			);
		}
		return List.copyOf(lines);
	}

	/**
	 * 统一执行目标遍历与派发。
	 */
	private static DispatchSummary dispatchInternal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		BlockPos sourcePos,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		DispatchKind dispatchKind,
		ActivationMode activationMode,
		int syncSignalStrength,
		com.makomi.block.entity.SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| sourceType == null
				|| targetType == null
				|| dispatchKind == null
				|| sourceSerial <= 0L
				|| targetSerials == null
				|| targetSerials.isEmpty()
		) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		long startNs = System.nanoTime();

		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		EventMeta immediateEventMeta = eventMeta == null ? EventMeta.now(sourceLevel) : eventMeta;
		long eventTick = immediateEventMeta.timeKey().tick();
		int eventSlot = immediateEventMeta.timeKey().slot();
		LinkConnectionMode sourceConnectionMode = savedData.getConnectionMode(sourceType, sourceSerial);
		long sourceChannel = savedData.getChannel(sourceType, sourceSerial);
		RedstoneLinkConfig.CrossChunkDirectBatchingMode directBatchingMode =
			RedstoneLinkConfig.crossChunk().directBatchingMode();
		boolean shouldBatchLoadedDirectDispatch = shouldBatchLoadedDispatch(dispatchKind, directBatchingMode);
		boolean shouldStageLoadedTargetsIntoChannelBucket =
			shouldStageLoadedTargetsIntoChannelBucket(
				sourceType,
				targetType,
				sourceConnectionMode,
				sourceChannel,
				directBatchingMode
			);
		int handledCount = 0;
		List<Long> forceLoadTargetSerials = new ArrayList<>();
		List<Long> relayTargetSerials = new ArrayList<>();
		Set<ResourceKey<Level>> handledTargetDimensions = new java.util.HashSet<>();
		List<Long> pendingTargetSerials = new ArrayList<>();
		List<LinkSavedData.LinkNode> pendingTargetNodes = new ArrayList<>();
		List<ChannelDispatchScheduler.LoadedChannelTarget> loadedChannelTargets = new ArrayList<>();
		for (long targetSerial : targetSerials) {
			if (targetSerial <= 0L) {
				continue;
			}
			LinkSavedData.LinkNode node = savedData.findNode(targetType, targetSerial).orElse(null);
			if (node == null) {
				continue;
			}
			int effectiveSignalStrength = dispatchKind == DispatchKind.ACTIVATION ? 15 : syncSignalStrength;
			if (!LinkDispatchFilterService.allowsReceive(sourceLevel.getServer(), node.dimension(), node.pos(), targetSerial, effectiveSignalStrength)) {
				if (dispatchKind == DispatchKind.SYNC_SIGNAL) {
					LinkDispatchFilterService.reconcileBlockedSyncDispatchByReceiveFilter(
						sourceLevel,
						sourcePos,
						sourceSerial,
						node.dimension(),
						node.pos(),
						targetSerial,
						previousSnapshot,
						immediateEventMeta
					);
				}
				continue;
			}

			ServerLevel targetLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (targetLevel == null || !targetLevel.isLoaded(node.pos())) {
				pendingTargetSerials.add(targetSerial);
				pendingTargetNodes.add(node);
				continue;
			}

			BlockEntity blockEntity = targetLevel.getBlockEntity(node.pos());
			if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
				// 节点快照与实际方块实体不一致时，清理脏在线节点记录。
				savedData.removeNode(targetType, targetSerial);
				continue;
			}
			if (!targetBlockEntity.matchesNodeIdentity(targetType, targetSerial)) {
				// 节点快照命中了错误实体时，同样视为脏在线节点。
				savedData.removeNode(targetType, targetSerial);
				continue;
			}

			if (shouldStageLoadedTargetsIntoChannelBucket) {
				loadedChannelTargets.add(new ChannelDispatchScheduler.LoadedChannelTarget(targetBlockEntity, targetType, targetSerial));
			} else if (dispatchKind == DispatchKind.ACTIVATION) {
				applyLoadedActivationDispatch(
					sourceLevel,
					targetBlockEntity,
					sourceType,
					sourceSerial,
					targetType,
					targetSerial,
					activationMode,
					immediateEventMeta,
					shouldBatchLoadedDirectDispatch
				);
			} else {
				applyLoadedSyncDispatch(
					sourceLevel,
					targetBlockEntity,
					sourceType,
					sourceSerial,
					targetType,
					targetSerial,
					syncSignalStrength,
					immediateEventMeta,
					shouldBatchLoadedDirectDispatch
				);
			}
			handledCount++;
			handledTargetDimensions.add(targetLevel.dimension());
		}

		if (!pendingTargetNodes.isEmpty()) {
			List<CrossChunkDispatchService.QueueResult> queueResults = dispatchKind == DispatchKind.ACTIVATION
				? CrossChunkDispatchService.queueActivationBatch(
					sourceLevel,
					pendingTargetNodes,
					sourceType,
					sourceSerial,
					activationMode,
					eventTick,
					eventSlot
				)
				: CrossChunkDispatchService.queueSyncSignalBatch(
					sourceLevel,
					pendingTargetNodes,
					sourceType,
					sourceSerial,
					syncSignalStrength,
					eventTick,
					eventSlot
				);
			int resultCount = Math.min(queueResults.size(), pendingTargetSerials.size());
			for (int index = 0; index < resultCount; index++) {
				CrossChunkDispatchService.QueueResult queueResult = queueResults.get(index);
				if (!queueResult.accepted()) {
					continue;
				}
				handledCount++;
				handledTargetDimensions.add(pendingTargetNodes.get(index).dimension());
				if (queueResult.forceLoadPlanned()) {
					forceLoadTargetSerials.add(pendingTargetSerials.get(index));
				} else {
					relayTargetSerials.add(pendingTargetSerials.get(index));
				}
			}
		}

		if (shouldStageLoadedTargetsIntoChannelBucket && !loadedChannelTargets.isEmpty()) {
			ChannelDispatchScheduler.enqueueLoadedChannelDispatch(
				sourceLevel.getServer(),
				sourceChannel,
				savedData.graphRevision(),
				loadedChannelTargets,
				buildLoadedDispatchBatchEntry(dispatchKind, sourceType, sourceSerial, activationMode, syncSignalStrength, immediateEventMeta)
			);
		}

		if (shouldStageLoadedTargetsIntoChannelBucket && !loadedChannelTargets.isEmpty()) {
			ChannelDispatchScheduler.flushLateArrivalsIfCurrentTickEndAlreadyPassed(
				sourceLevel.getServer(),
				eventTick
			);
		} else if (shouldBatchLoadedDirectDispatch) {
			// `window=0` 的设计目的是保持当前 tick 对齐。
			// 若本次 loaded direct 批派发发生在当前 tick 的 END 之后，则在整批入队完成后补一次 flush，
			// 既保留统一 scheduler 的 merge 语义，也避免这批 late arrival 整体晚到下一 tick 末。
			CoreDispatchBatchScheduler.flushLateArrivalsIfCurrentTickEndAlreadyPassed(
				sourceLevel.getServer(),
				eventTick
			);
		}

		DispatchSummary summary = new DispatchSummary(
			sourceType,
			sourceSerial,
			targetType,
			targetSerials.size(),
			handledCount,
			immutableSortedSerials(forceLoadTargetSerials),
			immutableSortedSerials(relayTargetSerials),
			Set.copyOf(handledTargetDimensions)
		);
		logSyncFanoutIfSlow(
			sourceLevel,
			dispatchKind,
			sourceType,
			sourceSerial,
			targetType,
			targetSerials.size(),
			pendingTargetSerials.size(),
			summary.forceLoadHandledCount(),
			summary.relayTargetSerials().size(),
			summary.handledCount(),
			startNs
		);
		return summary;
	}

	/**
	 * 记录 SYNC fanout 慢路径耗时。
	 */
	private static void logSyncFanoutIfSlow(
		ServerLevel sourceLevel,
		DispatchKind dispatchKind,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		int totalTargets,
		int pendingTargets,
		int forceLoadHandled,
		int relayHandled,
		int handledCount,
		long startNs
	) {
		if (dispatchKind != DispatchKind.SYNC_SIGNAL || sourceLevel == null || !RedstoneLinkConfig.crossChunk().runtimeDiagEnabled()) {
			return;
		}
		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
		long thresholdMs = RedstoneLinkConfig.crossChunk().runtimeDiagWarnThresholdMs();
		if (elapsedMs < thresholdMs) {
			return;
		}
		if (!RedstoneLinkConfig.crossChunk().runtimeDiagFanoutCountersEnabled()) {
			RedstoneLink.LOGGER.warn(
				"[DiagRuntime] sync_fanout_slow source={}#{}, targetType={}, totalTargets={}, pendingTargets={}, handled={}, forceLoadHandled={}, relayHandled={}, elapsedMs={}, thresholdMs={}, dimension={}",
				LinkNodeSemantics.toSemanticName(sourceType),
				sourceSerial,
				LinkNodeSemantics.toSemanticName(targetType),
				totalTargets,
				pendingTargets,
				handledCount,
				forceLoadHandled,
				relayHandled,
				elapsedMs,
				thresholdMs,
				sourceLevel.dimension().location()
			);
			return;
		}
		NeighborFanoutUtil.FanoutDiagnosticsSnapshot fanoutDiagnosticsTotal = NeighborFanoutUtil.diagnosticsSnapshot();
		NeighborFanoutUtil.FanoutDiagnosticsSnapshot fanoutDiagnosticsDelta =
			computeFanoutDiagnosticsDeltaAndAdvance(fanoutDiagnosticsTotal);
		RedstoneLink.LOGGER.warn(
			"[DiagRuntime] sync_fanout_slow source={}#{}, targetType={}, totalTargets={}, pendingTargets={}, handled={}, forceLoadHandled={}, relayHandled={}, elapsedMs={}, thresholdMs={}, dimension={}, fanoutRequestDelta={}, centerNotifySentDelta={}, neighborNotifyAttemptDelta={}, neighborNotifySentDelta={}, crossChunkSkipDelta={}, fanoutDedupHitDelta={}, fanoutRequestTotal={}, centerNotifySentTotal={}, neighborNotifyAttemptTotal={}, neighborNotifySentTotal={}, crossChunkSkipTotal={}, fanoutDedupHitTotal={}",
			LinkNodeSemantics.toSemanticName(sourceType),
			sourceSerial,
			LinkNodeSemantics.toSemanticName(targetType),
			totalTargets,
			pendingTargets,
			handledCount,
			forceLoadHandled,
			relayHandled,
			elapsedMs,
			thresholdMs,
			sourceLevel.dimension().location(),
			fanoutDiagnosticsDelta.fanoutRequestCount(),
			fanoutDiagnosticsDelta.centerNotifySentCount(),
			fanoutDiagnosticsDelta.neighborNotifyAttemptCount(),
			fanoutDiagnosticsDelta.neighborNotifySentCount(),
			fanoutDiagnosticsDelta.crossChunkSkipCount(),
			fanoutDiagnosticsDelta.fanoutDedupHitCount(),
			fanoutDiagnosticsTotal.fanoutRequestCount(),
			fanoutDiagnosticsTotal.centerNotifySentCount(),
			fanoutDiagnosticsTotal.neighborNotifyAttemptCount(),
			fanoutDiagnosticsTotal.neighborNotifySentCount(),
			fanoutDiagnosticsTotal.crossChunkSkipCount(),
			fanoutDiagnosticsTotal.fanoutDedupHitCount()
		);
	}

	/**
	 * 按来源节点当前连接模式解析本次派发应使用的目标集合。
	 * <p>
	 * 频道模式优先走频道成员索引，序号模式保持走普通边集合。
	 * </p>
	 */
	private static Set<Long> resolveDispatchTargets(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType
	) {
		if (sourceLevel == null || sourceType == null || targetType == null || sourceSerial <= 0L) {
			return Set.of();
		}
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		if (savedData == null) {
			return Set.of();
		}
		if (
			sourceType == LinkNodeType.TRIGGER_SOURCE
				&& targetType == LinkNodeType.CORE
				&& savedData.getConnectionMode(sourceType, sourceSerial) == LinkConnectionMode.CHANNEL
		) {
			long channel = savedData.getChannel(sourceType, sourceSerial);
			if (LinkSavedDataChannelSupport.isValidChannel(channel)) {
				return savedData.getChannelMembers(targetType, channel);
			}
			return Set.of();
		}
		return savedData.getLinkedPeersByNodeType(sourceType, sourceSerial);
	}

	/**
	 * 判断当前 loaded direct 目标是否应先进入频道中间层。
	 * <p>
	 * `directBatching=off` 时，频道模式需与序号模式对齐，直接按边派发，不再经过频道收口层。
	 * </p>
	 */
	static boolean shouldStageLoadedTargetsIntoChannelBucket(
		LinkNodeType sourceType,
		LinkNodeType targetType,
		LinkConnectionMode sourceConnectionMode,
		long sourceChannel,
		RedstoneLinkConfig.CrossChunkDirectBatchingMode directBatchingMode
	) {
		return sourceType == LinkNodeType.TRIGGER_SOURCE
			&& targetType == LinkNodeType.CORE
			&& sourceConnectionMode == LinkConnectionMode.CHANNEL
			&& LinkSavedDataChannelSupport.isValidChannel(sourceChannel)
			&& directBatchingMode != RedstoneLinkConfig.CrossChunkDirectBatchingMode.OFF;
	}

	/**
	 * 将 loaded direct 派发转换为频道中间层使用的批条目。
	 */
	private static ActivatableTargetBlockEntity.DispatchBatchEntry buildLoadedDispatchBatchEntry(
		DispatchKind dispatchKind,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		int syncSignalStrength,
		EventMeta eventMeta
	) {
		if (dispatchKind == DispatchKind.ACTIVATION) {
			return new ActivatableTargetBlockEntity.DispatchBatchEntry(
				ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
				ActivatableTargetBlockEntity.DeltaAction.UPSERT,
				sourceType,
				sourceSerial,
				activationMode,
				0,
				eventMeta
			);
		}
		return new ActivatableTargetBlockEntity.DispatchBatchEntry(
			ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
			ActivatableTargetBlockEntity.DeltaAction.UPSERT,
			sourceType,
			sourceSerial,
			ActivationMode.TOGGLE,
			syncSignalStrength,
			eventMeta
		);
	}

	/**
	 * 计算本次日志窗口的扇出计数增量，并推进基线。
	 * <p>
	 * 若运行期发生计数重置（当前值小于上次基线），该字段按“从 0 到当前”的窗口增量处理，避免出现负值。
	 * </p>
	 */
	private static NeighborFanoutUtil.FanoutDiagnosticsSnapshot computeFanoutDiagnosticsDeltaAndAdvance(
		NeighborFanoutUtil.FanoutDiagnosticsSnapshot current
	) {
		synchronized (FANOUT_DIAG_LOCK) {
			NeighborFanoutUtil.FanoutDiagnosticsSnapshot previous = lastLoggedFanoutDiagnostics;
			NeighborFanoutUtil.FanoutDiagnosticsSnapshot delta = new NeighborFanoutUtil.FanoutDiagnosticsSnapshot(
				nonNegativeDelta(current.fanoutRequestCount(), previous.fanoutRequestCount()),
				nonNegativeDelta(current.centerNotifySentCount(), previous.centerNotifySentCount()),
				nonNegativeDelta(current.neighborNotifyAttemptCount(), previous.neighborNotifyAttemptCount()),
				nonNegativeDelta(current.neighborNotifySentCount(), previous.neighborNotifySentCount()),
				nonNegativeDelta(current.crossChunkSkipCount(), previous.crossChunkSkipCount()),
				nonNegativeDelta(current.fanoutDedupHitCount(), previous.fanoutDedupHitCount())
			);
			lastLoggedFanoutDiagnostics = current;
			return delta;
		}
	}

	/**
	 * 计算非负增量；若检测到计数回绕/重置，使用当前值作为窗口增量。
	 */
	private static long nonNegativeDelta(long current, long previous) {
		return current >= previous ? (current - previous) : current;
	}

	/**
	 * 格式化同类型目标列表文本，并在超限时追加 `(+n)`。
	 */
	private static String formatNotifyTargets(LinkNodeType targetType, List<Long> serials, int displayLimit) {
		if (serials.isEmpty()) {
			return "-";
		}
		int showCount = Math.min(Math.max(1, displayLimit), serials.size());
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < showCount; index++) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(LinkNodeSemantics.toSemanticName(targetType)).append(":").append(serials.get(index));
		}
		int remain = serials.size() - showCount;
		if (remain > 0) {
			builder.append(" (+").append(remain).append(")");
		}
		return builder.toString();
	}

	/**
	 * 将序号列表转为不可变升序快照。
	 */
	private static List<Long> immutableSortedSerials(List<Long> serials) {
		return serials.stream().sorted().toList();
	}

	/**
	 * 将已加载目标上的 direct `ACTIVATION` 以 immediate 或 batch 方式投递。
	 */
	private static void applyLoadedActivationDispatch(
		ServerLevel sourceLevel,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		ActivationMode activationMode,
		EventMeta eventMeta,
		boolean shouldBatchLoadedDispatch
	) {
		if (shouldBatchLoadedDispatch) {
			CoreDispatchBatchScheduler.enqueueLoadedTargetDispatch(
				sourceLevel.getServer(),
				targetBlockEntity,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
				ActivatableTargetBlockEntity.DeltaAction.UPSERT,
				sourceType,
				sourceSerial,
				activationMode,
				0,
				eventMeta
			);
			return;
		}
		targetBlockEntity.applyDispatchDelta(
			ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
			ActivatableTargetBlockEntity.DeltaAction.UPSERT,
			sourceType,
			sourceSerial,
			activationMode,
			0,
			eventMeta
		);
	}

	/**
	 * 将已加载目标上的 direct `SYNC` 以 immediate 或 batch 方式投递。
	 */
	private static void applyLoadedSyncDispatch(
		ServerLevel sourceLevel,
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		int syncSignalStrength,
		EventMeta eventMeta,
		boolean shouldBatchLoadedDispatch
	) {
		if (shouldBatchLoadedDispatch) {
			CoreDispatchBatchScheduler.enqueueLoadedTargetDispatch(
				sourceLevel.getServer(),
				targetBlockEntity,
				targetType,
				targetSerial,
				ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
				ActivatableTargetBlockEntity.DeltaAction.UPSERT,
				sourceType,
				sourceSerial,
				ActivationMode.TOGGLE,
				syncSignalStrength,
				eventMeta
			);
			return;
		}
		targetBlockEntity.applyDispatchDelta(
			ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
			ActivatableTargetBlockEntity.DeltaAction.UPSERT,
			sourceType,
			sourceSerial,
			ActivationMode.TOGGLE,
			syncSignalStrength,
			eventMeta
		);
	}

	/**
	 * 判断当前 loaded direct 派发是否应进入目标级批提交。
	 */
	static boolean shouldBatchLoadedDispatch(
		DispatchKind dispatchKind,
		RedstoneLinkConfig.CrossChunkDirectBatchingMode directBatchingMode
	) {
		RedstoneLinkConfig.CrossChunkDirectBatchingMode normalizedMode = directBatchingMode == null
			? RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT
			: directBatchingMode;
		if (dispatchKind == DispatchKind.ACTIVATION) {
			return normalizedMode == RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT;
		}
		return InternalDispatchDeltaProjector.shouldBatchLoadedDelta(
			ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
			InternalDispatchDeltaEvents.DeliveryMode.IMMEDIATE,
			normalizedMode
		);
	}

	private enum DispatchKind {
		ACTIVATION,
		SYNC_SIGNAL
	}

	/**
	 * 派发统计快照。
	 *
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标类型
	 * @param totalTargets 输入目标数量
	 * @param handledCount 成功派发/入队数量
	 * @param forceLoadTargetSerials 强加载链路接管目标
	 * @param relayTargetSerials 中继缓冲链路接管目标
	 * @param handledTargetDimensions 本次真正成功处理目标所处维度
	 */
	public record DispatchSummary(
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		int totalTargets,
		int handledCount,
		List<Long> forceLoadTargetSerials,
		List<Long> relayTargetSerials,
		Set<ResourceKey<Level>> handledTargetDimensions
	) {
		public DispatchSummary {
			forceLoadTargetSerials = List.copyOf(forceLoadTargetSerials == null ? List.of() : forceLoadTargetSerials);
			relayTargetSerials = List.copyOf(relayTargetSerials == null ? List.of() : relayTargetSerials);
			handledTargetDimensions = Set.copyOf(handledTargetDimensions == null ? Set.of() : handledTargetDimensions);
		}

		/**
		 * @return 强加载接管数量
		 */
		public int forceLoadHandledCount() {
			return forceLoadTargetSerials.size();
		}

		/**
		 * @return 是否发生强加载接管
		 */
		public boolean hasForceLoadHandled() {
			return forceLoadHandledCount() > 0;
		}

		/**
		 * @return 跨区块接管总数量（强加载 + 中继缓冲）
		 */
		public int crossChunkHandledCount() {
			return forceLoadTargetSerials.size() + relayTargetSerials.size();
		}

		/**
		 * @return 是否发生跨区块接管
		 */
		public boolean hasCrossChunkHandled() {
			return crossChunkHandledCount() > 0;
		}

		/**
		 * 判断本次成功处理的目标中是否包含指定维度。
		 */
		public boolean hasHandledTargetInDimension(ResourceKey<Level> dimension) {
			return dimension != null && handledTargetDimensions.contains(dimension);
		}

		private static DispatchSummary empty(
			LinkNodeType sourceType,
			long sourceSerial,
			LinkNodeType targetType,
			Set<Long> targetSerials
		) {
			return new DispatchSummary(
				sourceType,
				sourceSerial,
				targetType,
				targetSerials == null ? 0 : targetSerials.size(),
				0,
				List.of(),
				List.of(),
				Set.of()
			);
		}
	}
}
