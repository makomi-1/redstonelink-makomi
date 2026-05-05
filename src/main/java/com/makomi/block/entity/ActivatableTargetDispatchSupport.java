package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.DeltaAction;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DeltaKind;
import com.makomi.block.entity.ActivatableTargetBlockEntity.DispatchBatchEntry;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivatableTargetBlockEntity.SourceKey;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.level.Level;

/**
 * `core` 目标端派发支持组件。
 * <p>
 * 负责承接 `ActivatableTargetBlockEntity` 中与来源 delta、批提交、
 * 运行态模拟 `sync` 和 pulse 回落相关的运行态落地流程，
 * 让目标实体主类聚焦在 API、生命周期与桥接职责。
 * </p>
 */
final class ActivatableTargetDispatchSupport {
	private static final Comparator<DispatchBatchEntry> DISPATCH_BATCH_ENTRY_COMPARATOR =
		Comparator
			.comparing((DispatchBatchEntry entry) -> entry.eventMeta().timeKey())
			.thenComparingLong(entry -> entry.eventMeta().seq())
			.thenComparingInt(entry -> dispatchBatchDeltaPriority(entry.deltaKind()));

	private final ActivatableTargetBlockEntity owner;

	ActivatableTargetDispatchSupport(ActivatableTargetBlockEntity owner) {
		this.owner = owner;
	}

	/**
	 * 统一来源 delta 入口：同一入口处理 UPSERT/REMOVE，并按模式触发定向重算。
	 */
	void applyDispatchDelta(
		DeltaKind deltaKind,
		DeltaAction deltaAction,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		int signalStrength,
		EventMeta eventMeta
	) {
		if (deltaKind == null || deltaAction == null) {
			return;
		}
		EventMeta normalizedMeta = owner.normalizeEventMeta(eventMeta);
		SourceKey sourceKey = new SourceKey(sourceType, sourceSerial);
		if (sourceSerial <= 0L) {
			applyLegacyDeltaForNonSourceSerial(deltaKind, deltaAction, activationMode, signalStrength, normalizedMeta);
			return;
		}
		if (!owner.canBeTriggeredBy(sourceSerial)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceKey.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return;
		}

		switch (deltaKind) {
			case SYNC_SIGNAL -> applySyncDelta(sourceKey, deltaAction, signalStrength, normalizedMeta);
			case ACTIVATION -> applyActivationDelta(sourceKey, deltaAction, activationMode, normalizedMeta);
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION ->
				applyTriggerSourceChunkUnloadInvalidationDelta(sourceKey, deltaAction, normalizedMeta);
			case TRIGGER_SOURCE_INVALIDATION -> applyTriggerSourceInvalidationDelta(sourceKey, deltaAction, normalizedMeta);
		}
	}

	/**
	 * 批量应用目标级批窗口内的结构化变更。
	 * <p>
	 * 该入口会先按时间键与固定优先级排序，再在批末统一执行一次真值重算与派生态写回。
	 * </p>
	 */
	void applyDispatchBatch(List<DispatchBatchEntry> batchEntries) {
		if (batchEntries == null || batchEntries.isEmpty()) {
			return;
		}
		List<DispatchBatchEntry> sortedEntries = new ArrayList<>(batchEntries.size());
		for (DispatchBatchEntry batchEntry : batchEntries) {
			if (batchEntry != null) {
				sortedEntries.add(batchEntry);
			}
		}
		if (sortedEntries.isEmpty()) {
			return;
		}
		sortedEntries.sort(DISPATCH_BATCH_ENTRY_COMPARATOR);

		StructuredBatchMutationAccumulator accumulator = new StructuredBatchMutationAccumulator();
		for (DispatchBatchEntry batchEntry : sortedEntries) {
			applyStructuredBatchEntry(batchEntry, accumulator);
		}
		finalizeStructuredBatchMutation(accumulator);
	}

	/**
	 * 应用运行态模拟 SYNC 输入。
	 * <p>
	 * 该入口仅供输入播放服务使用，不进入持久化来源桶。
	 * </p>
	 */
	void applyRuntimeSimulatedSyncSource(long sourceSerial, int signalStrength, EventMeta eventMeta) {
		applyRuntimeSimulatedSyncDelta(sourceSerial, signalStrength, eventMeta, false);
	}

	/**
	 * 移除运行态模拟 SYNC 输入。
	 */
	void removeRuntimeSimulatedSyncSource(long sourceSerial, EventMeta eventMeta) {
		applyRuntimeSimulatedSyncDelta(sourceSerial, 0, eventMeta, true);
	}

	/**
	 * 处理 pulse 到期后的目标端回落。
	 */
	void onPulseTick() {
		Level level = owner.getLevel();
		if (level == null || level.isClientSide) {
			return;
		}
		if (!concurrentComponent().pulseResetArmed() && !concurrentComponent().pulseSnapshotRecorded()) {
			return;
		}
		long now = level.getGameTime();
		boolean bucketChanged = owner.recomputePulseTruthFromConcurrentBuckets();
		owner.recomputeToggleTruthFromConcurrentBuckets();
		owner.recomputeAuthorityFromConcurrentBuckets(owner.resolvePulseExpireFallbackTimeKey(now), arbitrationComponent().authoritySeq());
		owner.applyDerivedStateFromTruth();
		owner.markStructuredTruthDirty(bucketChanged);
	}

	/**
	 * 序号无效（例如玩家手动触发）时，走现有轻量语义兜底，不进入来源桶。
	 */
	private void applyLegacyDeltaForNonSourceSerial(
		DeltaKind deltaKind,
		DeltaAction deltaAction,
		ActivationMode activationMode,
		int signalStrength,
		EventMeta eventMeta
	) {
		if (deltaAction == DeltaAction.REMOVE) {
			return;
		}
		if (deltaKind == DeltaKind.SYNC_SIGNAL) {
			int normalizedStrength = ActivatableTargetBlockEntity.normalizeSignalStrength(signalStrength);
			boolean bucketChanged = false;
			if (normalizedStrength > 0) {
				bucketChanged |= concurrentComponent().pruneOlderFramesForIncoming(eventMeta.timeKey(), EffectiveMode.SYNC);
			}
			bucketChanged |= owner.updateSyncSignalStrength(0L, normalizedStrength);
			owner.applyDerivedStateFromTruth();
			owner.markStructuredTruthDirty(bucketChanged);
			return;
		}
		owner.applyActivation(0L, activationMode == null ? owner.getConfiguredMode() : activationMode, eventMeta);
	}

	/**
	 * 统一处理 SYNC delta（UPSERT/REMOVE）。
	 */
	private void applySyncDelta(SourceKey sourceKey, DeltaAction deltaAction, int signalStrength, EventMeta eventMeta) {
		StructuredBatchMutationAccumulator accumulator = new StructuredBatchMutationAccumulator();
		applySyncDeltaMutation(sourceKey, deltaAction, signalStrength, eventMeta, accumulator);
		finalizeStructuredBatchMutation(accumulator);
	}

	/**
	 * 统一处理运行态模拟 SYNC delta。
	 * <p>
	 * 该路径与真实 SYNC 共享裁决逻辑，但来源桶仅存于内存，不参与持久化。
	 * </p>
	 */
	private void applyRuntimeSimulatedSyncDelta(long sourceSerial, int signalStrength, EventMeta eventMeta, boolean removeOnly) {
		Level level = owner.getLevel();
		if (sourceSerial <= 0L || level == null || level.isClientSide) {
			return;
		}
		EventMeta normalizedMeta = owner.normalizeEventMeta(eventMeta);
		SourceKey sourceKey = new SourceKey(LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		int normalizedStrength = ActivatableTargetBlockEntity.normalizeSignalStrength(signalStrength);
		boolean bucketChanged = false;
		if (!removeOnly && normalizedStrength > 0) {
			bucketChanged |= concurrentComponent().pruneOlderFramesForIncoming(normalizedMeta.timeKey(), EffectiveMode.SYNC);
			if (com.makomi.config.RedstoneLinkConfig.crossChunk().syncCrossTickOverride()) {
				bucketChanged |= concurrentComponent().clearRuntimeSimulatedSyncTruthBefore(normalizedMeta.timeKey());
			}
		}
		if (removeOnly || normalizedStrength <= 0) {
			bucketChanged |= concurrentComponent().removeRuntimeSimulatedSyncConcurrentSource(
				sourceKey,
				normalizedMeta.timeKey(),
				normalizedMeta.seq()
			);
		} else {
			bucketChanged |= concurrentComponent().upsertRuntimeSimulatedSyncConcurrentSource(
				sourceKey,
				normalizedMeta.timeKey(),
				normalizedStrength,
				normalizedMeta.seq()
			);
		}
		owner.recomputeSyncTruthFromConcurrentBuckets();
		owner.recomputeToggleTruthFromConcurrentBuckets();
		owner.recomputeAuthorityFromConcurrentBuckets(normalizedMeta.timeKey(), normalizedMeta.seq());
		owner.applyDerivedStateFromTruth();
		owner.markStructuredTruthDirty(bucketChanged);
	}

	/**
	 * 统一处理 ACTIVATION delta（TOGGLE/PULSE 的 UPSERT/REMOVE）。
	 */
	private void applyActivationDelta(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		ActivationMode activationMode,
		EventMeta eventMeta
	) {
		owner.recomputeSyncTruthFromConcurrentBuckets();
		if (deltaAction != DeltaAction.REMOVE && concurrentComponent().hasEffectiveSyncTruth()) {
			return;
		}
		ActivationMode normalizedMode = activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		TimeKey normalizedTimeKey = eventMeta.timeKey() == null ? TimeKey.of(0L, 0) : eventMeta.timeKey();
		boolean baseToggleState = normalizedMode == ActivationMode.TOGGLE
			&& deltaAction != DeltaAction.REMOVE
			&& owner.resolveCurrentTargetStateBeforeToggle();
		boolean priorityAccepted = owner.acceptByPriority(normalizedTimeKey, priority, incomingMode, eventMeta.seq());
		if (!priorityAccepted) {
			return;
		}
		boolean bucketChanged = false;
		if (normalizedMode == ActivationMode.PULSE) {
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= concurrentComponent().clearPulseTruth();
			} else {
				bucketChanged |= concurrentComponent().recordPulseSnapshot(owner, normalizedTimeKey, eventMeta.seq());
			}
			owner.recomputeSyncTruthFromConcurrentBuckets();
			bucketChanged |= owner.recomputePulseTruthFromConcurrentBuckets();
		} else if (deltaAction != DeltaAction.REMOVE) {
			// `toggle` 语义直接对“本次写入前”的当前目标结果态取反，
			// 不能依赖旧 toggle fallback，也不能在 authority 已切到 TOGGLE 后再取基准。
			bucketChanged |= concurrentComponent().recordToggleSnapshot(!baseToggleState, normalizedTimeKey, eventMeta.seq());
			owner.recomputeSyncTruthFromConcurrentBuckets();
		} else {
			return;
		}
		owner.recomputeToggleTruthFromConcurrentBuckets();
		owner.recomputeAuthorityFromConcurrentBuckets(normalizedTimeKey, eventMeta.seq());
		owner.applyDerivedStateFromTruth();
		owner.markStructuredTruthDirty(bucketChanged);
	}

	/**
	 * 统一处理“triggerSource 区块卸载失效”delta：仅剔除该来源的 sync 贡献。
	 * <p>
	 * pulse/toggle 已按事件语义处理，不再因 triggerSource 所在区块卸载被回滚。
	 * </p>
	 */
	private void applyTriggerSourceChunkUnloadInvalidationDelta(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		EventMeta eventMeta
	) {
		StructuredBatchMutationAccumulator accumulator = new StructuredBatchMutationAccumulator();
		applyTriggerSourceChunkUnloadInvalidationMutation(sourceKey, deltaAction, eventMeta, accumulator);
		finalizeStructuredBatchMutation(accumulator);
	}

	/**
	 * 统一处理“triggerSource 其它失效”delta：仅剔除该来源的 sync 贡献。
	 * <p>
	 * `toggle/pulse` 为目标端事件结果，不再享有来源失效带来的生命周期回滚。
	 * </p>
	 */
	private void applyTriggerSourceInvalidationDelta(SourceKey sourceKey, DeltaAction deltaAction, EventMeta eventMeta) {
		StructuredBatchMutationAccumulator accumulator = new StructuredBatchMutationAccumulator();
		applyTriggerSourceInvalidationMutation(sourceKey, deltaAction, eventMeta, accumulator);
		finalizeStructuredBatchMutation(accumulator);
	}

	/**
	 * 批次内按时间顺序应用一条结构化变更，但不立即提交重算结果。
	 */
	private void applyStructuredBatchEntry(DispatchBatchEntry batchEntry, StructuredBatchMutationAccumulator accumulator) {
		if (batchEntry == null || accumulator == null || batchEntry.deltaKind() == null || batchEntry.deltaAction() == null) {
			return;
		}
		if (batchEntry.sourceSerial() <= 0L) {
			return;
		}
		if (!owner.canBeTriggeredBy(batchEntry.sourceSerial())) {
			return;
		}
		SourceKey sourceKey = new SourceKey(batchEntry.sourceType(), batchEntry.sourceSerial());
		if (!LinkNodeSemantics.isAllowedForRole(sourceKey.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		EventMeta normalizedMeta = owner.normalizeEventMeta(batchEntry.eventMeta());
		switch (batchEntry.deltaKind()) {
			case SYNC_SIGNAL -> applySyncDeltaMutation(
				sourceKey,
				batchEntry.deltaAction(),
				batchEntry.syncSignalStrength(),
				normalizedMeta,
				accumulator
			);
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION -> applyTriggerSourceChunkUnloadInvalidationMutation(
				sourceKey,
				batchEntry.deltaAction(),
				normalizedMeta,
				accumulator
			);
			case TRIGGER_SOURCE_INVALIDATION -> applyTriggerSourceInvalidationMutation(
				sourceKey,
				batchEntry.deltaAction(),
				normalizedMeta,
				accumulator
			);
			case ACTIVATION -> applyActivationDeltaMutation(
				sourceKey,
				batchEntry.deltaAction(),
				batchEntry.activationMode(),
				normalizedMeta,
				accumulator
			);
		}
	}

	/**
	 * 批次内应用 SYNC 变更，只更新来源桶，重算延后到批末统一提交。
	 */
	private void applySyncDeltaMutation(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		int signalStrength,
		EventMeta eventMeta,
		StructuredBatchMutationAccumulator accumulator
	) {
		int normalizedStrength = ActivatableTargetBlockEntity.normalizeSignalStrength(signalStrength);
		boolean bucketChanged = false;
		if (deltaAction != DeltaAction.REMOVE && normalizedStrength > 0) {
			bucketChanged |= concurrentComponent().pruneOlderFramesForIncoming(eventMeta.timeKey(), EffectiveMode.SYNC);
			if (com.makomi.config.RedstoneLinkConfig.crossChunk().syncCrossTickOverride()) {
				bucketChanged |= concurrentComponent().clearSyncTruthBefore(eventMeta.timeKey());
			}
		}
		if (deltaAction == DeltaAction.REMOVE || normalizedStrength <= 0) {
			bucketChanged |= concurrentComponent().removeSyncConcurrentSource(sourceKey, eventMeta.timeKey(), eventMeta.seq());
		} else {
			bucketChanged |= concurrentComponent().upsertSyncConcurrentSource(
				sourceKey,
				eventMeta.timeKey(),
				normalizedStrength,
				eventMeta.seq()
			);
		}
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批次内应用 ACTIVATION 变更，只更新并发桶，批末再统一计算派生态。
	 */
	private void applyActivationDeltaMutation(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		ActivationMode activationMode,
		EventMeta eventMeta,
		StructuredBatchMutationAccumulator accumulator
	) {
		owner.recomputeSyncTruthFromConcurrentBuckets();
		if (deltaAction != DeltaAction.REMOVE && concurrentComponent().hasEffectiveSyncTruth()) {
			return;
		}
		ActivationMode normalizedMode = activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		TimeKey normalizedTimeKey = eventMeta.timeKey() == null ? TimeKey.of(0L, 0) : eventMeta.timeKey();
		boolean baseToggleState = normalizedMode == ActivationMode.TOGGLE
			&& deltaAction != DeltaAction.REMOVE
			&& owner.resolveCurrentTargetStateBeforeToggle();
		boolean priorityAccepted = owner.acceptByPriority(normalizedTimeKey, priority, incomingMode, eventMeta.seq());
		if (!priorityAccepted) {
			return;
		}

		boolean bucketChanged = false;
		if (normalizedMode == ActivationMode.PULSE) {
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= concurrentComponent().clearPulseTruth();
			} else {
				bucketChanged |= concurrentComponent().recordPulseSnapshot(owner, normalizedTimeKey, eventMeta.seq());
			}
			owner.recomputeSyncTruthFromConcurrentBuckets();
			owner.recomputeAuthorityFromConcurrentBuckets(normalizedTimeKey, eventMeta.seq());
			accumulator.record(eventMeta, bucketChanged, true);
			return;
		}

		if (deltaAction == DeltaAction.REMOVE) {
			return;
		}
		// 批路径与 direct 路径保持一致：toggle 直接翻转“本次写入前”的当前目标结果态。
		bucketChanged |= concurrentComponent().recordToggleSnapshot(!baseToggleState, normalizedTimeKey, eventMeta.seq());
		owner.recomputeSyncTruthFromConcurrentBuckets();
		owner.recomputeAuthorityFromConcurrentBuckets(normalizedTimeKey, eventMeta.seq());
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批次内应用“triggerSource 区块卸载失效”，仅剔除 sync 贡献，重算延后到批末。
	 */
	private void applyTriggerSourceChunkUnloadInvalidationMutation(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		EventMeta eventMeta,
		StructuredBatchMutationAccumulator accumulator
	) {
		if (deltaAction != DeltaAction.REMOVE) {
			return;
		}
		boolean bucketChanged = concurrentComponent().removeSyncConcurrentSource(sourceKey, eventMeta.timeKey(), eventMeta.seq());
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批次内应用“triggerSource 其它失效”，仅剔除该来源的 sync 贡献，重算延后到批末。
	 */
	private void applyTriggerSourceInvalidationMutation(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		EventMeta eventMeta,
		StructuredBatchMutationAccumulator accumulator
	) {
		if (deltaAction != DeltaAction.REMOVE) {
			return;
		}
		boolean bucketChanged = concurrentComponent().removeSyncConcurrentSource(sourceKey, eventMeta.timeKey(), eventMeta.seq());
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批末统一提交结构化变更，避免逐条 delta 重复重算。
	 */
	private void finalizeStructuredBatchMutation(StructuredBatchMutationAccumulator accumulator) {
		if (accumulator == null || !accumulator.acceptedAny()) {
			return;
		}
		owner.recomputeSyncTruthFromConcurrentBuckets();
		if (accumulator.requiresPulseTruthRecompute()) {
			accumulator.mergeBucketChanged(owner.recomputePulseTruthFromConcurrentBuckets());
		}
		owner.recomputeToggleTruthFromConcurrentBuckets();
		owner.recomputeAuthorityFromConcurrentBuckets(accumulator.fallbackTimeKey(), accumulator.fallbackSeq());
		owner.applyDerivedStateFromTruth();
		owner.markStructuredTruthDirty(accumulator.bucketChanged());
	}

	private ActivatableTargetArbitrationComponent arbitrationComponent() {
		return owner.internalArbitrationComponent();
	}

	private ActivatableTargetConcurrentBucketComponent concurrentComponent() {
		return owner.internalConcurrentComponent();
	}

	/**
	 * 同时间粒度内的批条目固定排序：先按时间键/序列，再按失效覆盖优先级。
	 */
	private static int dispatchBatchDeltaPriority(DeltaKind deltaKind) {
		if (deltaKind == null) {
			return Integer.MAX_VALUE;
		}
		return switch (deltaKind) {
			case SYNC_SIGNAL -> 0;
			case SOURCE_INVALIDATION, TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION -> 1;
			case TRIGGER_SOURCE_INVALIDATION -> 2;
			case ACTIVATION -> 3;
		};
	}

	private static int compareEventMeta(EventMeta left, EventMeta right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return -1;
		}
		if (right == null) {
			return 1;
		}
		int timeKeyCompare = left.timeKey().compareTo(right.timeKey());
		if (timeKeyCompare != 0) {
			return timeKeyCompare;
		}
		return Long.compare(left.seq(), right.seq());
	}

	/**
	 * 批次内结构化变更累计器。
	 */
	private static final class StructuredBatchMutationAccumulator {
		private boolean acceptedAny;
		private boolean bucketChanged;
		private boolean requiresPulseTruthRecompute;
		private EventMeta fallbackEventMeta = EventMeta.of(0L, 0, 0L);

		void record(EventMeta eventMeta, boolean mutationChanged, boolean pulseTruthChangedPossible) {
			acceptedAny = true;
			bucketChanged |= mutationChanged;
			requiresPulseTruthRecompute |= pulseTruthChangedPossible;
			if (compareEventMeta(eventMeta, fallbackEventMeta) >= 0) {
				fallbackEventMeta = eventMeta == null ? EventMeta.of(0L, 0, 0L) : eventMeta;
			}
		}

		boolean acceptedAny() {
			return acceptedAny;
		}

		boolean bucketChanged() {
			return bucketChanged;
		}

		void mergeBucketChanged(boolean mutationChanged) {
			bucketChanged |= mutationChanged;
		}

		boolean requiresPulseTruthRecompute() {
			return requiresPulseTruthRecompute;
		}

		TimeKey fallbackTimeKey() {
			return fallbackEventMeta.timeKey();
		}

		long fallbackSeq() {
			return fallbackEventMeta.seq();
		}
	}
}
