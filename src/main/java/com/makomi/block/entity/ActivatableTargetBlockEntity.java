package com.makomi.block.entity;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 可激活目标节点基类。
 * <p>
 * 封装 TOGGLE/PULSE/SYNC 三类触发语义，并在状态变化后交由子类处理方块状态同步与红石更新。
 * </p>
 */
public abstract class ActivatableTargetBlockEntity extends PairableNodeBlockEntity {
	private boolean active;
	private ActivationMode configuredMode = ActivationMode.TOGGLE;
	private final ActivatableTargetConcurrentBucketComponent concurrentComponent = new ActivatableTargetConcurrentBucketComponent();
	private final ActivatableTargetArbitrationComponent arbitrationComponent = new ActivatableTargetArbitrationComponent();
	private final ActivatableTargetObservationComponent observationComponent = new ActivatableTargetObservationComponent();

	/**
	 * 运行态生效模式（用于可观测，不参与额外仲裁）。
	 */
	public enum EffectiveMode {
		NONE,
		TOGGLE,
		PULSE,
		SYNC
	}

	/**
	 * 跨来源统一 delta 类型：激活语义、同步语义，或两类 triggerSource 失效语义。
	 */
	public enum DeltaKind {
		ACTIVATION,
		SYNC_SIGNAL,
		SOURCE_INVALIDATION,
		TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
		TRIGGER_SOURCE_INVALIDATION
	}

	/**
	 * 来源 delta 动作：UPSERT 表示建立/更新/恢复，REMOVE 表示失效/断链/下线等剔除。
	 */
	public enum DeltaAction {
		UPSERT,
		REMOVE
	}

	/**
	 * 时间键：默认粒度为 tick，slot 预留给未来 tick 细分。
	 */
	public record TimeKey(long tick, int slot) implements Comparable<TimeKey> {
		private static final TimeKey MIN_VALUE = new TimeKey(Long.MIN_VALUE, Integer.MIN_VALUE);

		public TimeKey {
			tick = Math.max(0L, tick);
			slot = Math.max(0, slot);
		}

		public static TimeKey of(long tick, int slot) {
			return new TimeKey(tick, slot);
		}

		public static TimeKey minValue() {
			return MIN_VALUE;
		}

		@Override
		public int compareTo(TimeKey other) {
			if (other == null) {
				return 1;
			}
			int tickCompare = Long.compare(tick, other.tick);
			if (tickCompare != 0) {
				return tickCompare;
			}
			return Integer.compare(slot, other.slot);
		}
	}

	/**
	 * 来源静态键：sourceType + sourceSerial，确保重放/重启下去顺序无关确定性。
	 */
	public record SourceKey(LinkNodeType sourceType, long sourceSerial) implements Comparable<SourceKey> {
		public SourceKey {
			sourceType = sourceType == null ? LinkNodeType.TRIGGER_SOURCE : sourceType;
		}

		@Override
		public int compareTo(SourceKey other) {
			if (other == null) {
				return 1;
			}
			int typeCompare = sourceType.name().compareTo(other.sourceType.name());
			if (typeCompare != 0) {
				return typeCompare;
			}
			return Long.compare(sourceSerial, other.sourceSerial);
		}
	}

	/**
	 * 事件元数据：用于可扩展时间粒度仲裁与防旧观测。
	 */
	public record EventMeta(TimeKey timeKey, long seq) {
		public EventMeta {
			timeKey = timeKey == null ? TimeKey.of(0L, 0) : timeKey;
			seq = Math.max(0L, seq);
		}

		public static EventMeta of(long tick, int slot, long seq) {
			return new EventMeta(TimeKey.of(tick, slot), seq);
		}

		public static EventMeta now(Level level) {
			long tick = level == null ? 0L : Math.max(0L, level.getGameTime());
			return of(tick, 0, 0L);
		}
	}

	/**
	 * `core` 目标端批提交条目。
	 * <p>
	 * 用于目标级批窗口内的结构化规约提交：
	 * `SYNC_SIGNAL`、`ACTIVATION` 与两类 `triggerSource` 失效语义均可复用该结构。
	 * </p>
	 */
	public record DispatchBatchEntry(
		DeltaKind deltaKind,
		DeltaAction deltaAction,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		int syncSignalStrength,
		EventMeta eventMeta
	) {
		public DispatchBatchEntry {
			activationMode = activationMode == null ? ActivationMode.TOGGLE : activationMode;
			syncSignalStrength = SignalStrengths.clamp(syncSignalStrength);
			eventMeta = eventMeta == null ? EventMeta.of(0L, 0, 0L) : eventMeta;
		}
	}

	private static final Comparator<DispatchBatchEntry> DISPATCH_BATCH_ENTRY_COMPARATOR =
		Comparator
			.comparing((DispatchBatchEntry entry) -> entry.eventMeta().timeKey())
			.thenComparingLong(entry -> entry.eventMeta().seq())
			.thenComparingInt(entry -> dispatchBatchDeltaPriority(entry.deltaKind()));

	protected ActivatableTargetBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	public final boolean isActive() {
		return active;
	}

	/**
	 * 返回当前解析后的输出功率（0~15）。
	 * <p>
	 * 目标未激活时固定返回 0；激活时：
	 * SYNC 返回聚合强度，TOGGLE/PULSE 返回默认激活功率。
	 * </p>
	 */
	public final int getResolvedOutputPower() {
		return active ? observationComponent.resolvedOutputPower() : 0;
	}

	/**
	 * 返回当前主结果强度（0~15）。
	 * <p>
	 * P2 三层结果模型中的主结果：与最终输出强度一致。
	 * </p>
	 */
	public final int getResolvedStrength() {
		return getResolvedOutputPower();
	}

	/**
	 * 返回当前并列最大强度来源快照（升序）。
	 * <p>
	 * 仅用于可观测/审计，不参与裁决主流程。
	 * </p>
	 */
	public final List<Long> getSyncMaxSourceSerialsSnapshot() {
		return concurrentComponent.syncMaxSourceSerialsSnapshot();
	}

	public final ActivationMode getConfiguredMode() {
		return configuredMode;
	}

	public final void setConfiguredMode(ActivationMode configuredMode) {
		if (configuredMode == null || this.configuredMode == configuredMode) {
			return;
		}
		this.configuredMode = configuredMode;
		syncToClient();
	}

	/**
	 * 返回当前运行态实际生效模式。
	 * <p>
	 * 裁决顺序为：先按时间键，再按同粒度固定优先级 `SYNC > PULSE > TOGGLE`。
	 * </p>
	 */
	public final EffectiveMode getEffectiveMode() {
		return resolveAuthorityEffectiveMode();
	}

	public final void triggerByPlayer() {
		triggerByPlayer(EventMeta.now(level));
	}

	public final void triggerByPlayer(EventMeta eventMeta) {
		applyActivation(0L, configuredMode, normalizeEventMeta(eventMeta));
	}

	public final void triggerBySource(long sourceSerial) {
		triggerBySource(sourceSerial, configuredMode, EventMeta.now(level));
	}

	public final void triggerBySource(long sourceSerial, ActivationMode triggerMode) {
		triggerBySource(sourceSerial, triggerMode, EventMeta.now(level));
	}

	public final void triggerBySource(long sourceSerial, ActivationMode triggerMode, EventMeta eventMeta) {
		applyDispatchDelta(
			DeltaKind.ACTIVATION,
			DeltaAction.UPSERT,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			triggerMode == null ? configuredMode : triggerMode,
			0,
			eventMeta
		);
	}

	/**
	 * 按源端当前输入电平同步目标状态。
	 * <p>
	 * 兼容布尔输入：ON 视为强度 15，OFF 视为强度 0。
	 * </p>
	 */
	public final void syncBySource(long sourceSerial, boolean signalOn) {
		syncBySource(sourceSerial, signalOn ? 15 : 0, EventMeta.now(level));
	}

	/**
	 * 按源端当前输入强度同步目标状态。
	 * <p>
	 * 该路径不执行 TOGGLE/PULSE 语义转换；多同步源按 max 强度聚合，避免最后写入覆盖。
	 * </p>
	 *
	 * @param sourceSerial 来源序号
	 * @param signalStrength 输入强度（会被归一到 0~15）
	 */
	public final void syncBySource(long sourceSerial, int signalStrength) {
		syncBySource(sourceSerial, signalStrength, EventMeta.now(level));
	}

	public final void syncBySource(long sourceSerial, int signalStrength, EventMeta eventMeta) {
		applyDispatchDelta(
			DeltaKind.SYNC_SIGNAL,
			DeltaAction.UPSERT,
			LinkNodeType.TRIGGER_SOURCE,
			sourceSerial,
			ActivationMode.TOGGLE,
			signalStrength,
			eventMeta
		);
	}

	/**
	 * 统一来源 delta 入口：同一入口处理 UPSERT/REMOVE，并按模式触发定向重算。
	 */
	public final void applyDispatchDelta(
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
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		SourceKey sourceKey = new SourceKey(sourceType, sourceSerial);
		if (sourceSerial <= 0L) {
			applyLegacyDeltaForNonSourceSerial(deltaKind, deltaAction, activationMode, signalStrength, normalizedMeta);
			return;
		}
		if (!canBeTriggeredBy(sourceSerial)) {
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
	public final void applyDispatchBatch(List<DispatchBatchEntry> batchEntries) {
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
	 * 来源失效时移除激活语义贡献（TOGGLE/PULSE）。
	 */
	public final void removeActivationSource(
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		EventMeta eventMeta
	) {
		applyDispatchDelta(
			DeltaKind.ACTIVATION,
			DeltaAction.REMOVE,
			sourceType,
			sourceSerial,
			activationMode,
			0,
			eventMeta
		);
	}

	/**
	 * 来源失效时移除同步语义贡献（SYNC）。
	 */
	public final void removeSyncSource(
		LinkNodeType sourceType,
		long sourceSerial,
		EventMeta eventMeta
	) {
		applyDispatchDelta(
			DeltaKind.SYNC_SIGNAL,
			DeltaAction.REMOVE,
			sourceType,
			sourceSerial,
			ActivationMode.TOGGLE,
			0,
			eventMeta
		);
	}

	/**
	 * 应用运行态模拟 SYNC 输入。
	 * <p>
	 * 该入口仅供输入播放服务使用，不进入持久化来源桶。
	 * </p>
	 */
	public final void applyRuntimeSimulatedSyncSource(long sourceSerial, int signalStrength, EventMeta eventMeta) {
		applyRuntimeSimulatedSyncDelta(sourceSerial, signalStrength, eventMeta, false);
	}

	/**
	 * 移除运行态模拟 SYNC 输入。
	 */
	public final void removeRuntimeSimulatedSyncSource(long sourceSerial, EventMeta eventMeta) {
		applyRuntimeSimulatedSyncDelta(sourceSerial, 0, eventMeta, true);
	}

	public final void onPulseTick() {
		if (level == null || level.isClientSide) {
			return;
		}
		if (!concurrentComponent.pulseResetArmed() && concurrentComponent.pulseConcurrentBuckets().isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		boolean bucketChanged = recomputePulseTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(resolvePulseExpireFallbackTimeKey(now), arbitrationComponent.authoritySeq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
	}

	protected boolean canBeTriggeredBy(long sourceSerial) {
		return true;
	}

	protected int getPulseDurationTicks() {
		return RedstoneLinkConfig.general().pulseDurationTicks();
	}

	/**
	 * 计算 pulse 回落后的 stale guard fallback 时间键。
	 * <p>
	 * loaded direct batching 会让合法事件相对其源侧 `eventMeta.timeKey` 固定晚到若干 tick。
	 * pulse 回落时若直接把 authority 推进到“当前 tick”，窗口内仍在路上的 delayed event
	 * 会被误判为旧事件。这里按目标级批窗口向前回退，既保留窗口外旧事件过滤，
	 * 又允许窗口内合法迟到继续生效。
	 * </p>
	 */
	private TimeKey resolvePulseExpireFallbackTimeKey(long nowTick) {
		long normalizedNowTick = Math.max(0L, nowTick);
		int batchWindowTicks = Math.max(0, RedstoneLinkConfig.crossChunk().dispatchBatchWindowTicks());
		long fallbackTick = Math.max(0L, normalizedNowTick - batchWindowTicks);
		return TimeKey.of(fallbackTick, 0);
	}

	protected abstract void onActiveChanged(boolean active);

	/**
	 * 读档后按当前派生态静默同步方块状态。
	 * <p>
	 * 默认无操作；仅 blockstate 承载可见激活态的 `core` 子类需要覆盖。
	 * </p>
	 */
	protected void syncBlockStateFromDerivedState(boolean active) {}

	/**
	 * 判断当前派生态是否需要在加载后异步校正 blockstate。
	 * <p>
	 * 默认不需要；仅 blockstate 承载可见激活态的 `core` 子类需要覆盖。
	 * </p>
	 */
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		return false;
	}

	protected abstract void schedulePulseReset(int pulseTicks);

	/**
	 * 当前实体是否仍有待处理的加载后 blockstate 校正任务。
	 */
	public final boolean hasPendingLoadBlockStateSync() {
		return observationComponent.pendingLoadBlockStateSync();
	}

	/**
	 * 消费一次加载后 blockstate 校正任务。
	 */
	public final void consumePendingLoadBlockStateSync() {
		observationComponent.consumePendingLoadBlockStateSync(this);
	}

	/**
	 * 默认激活输出功率（TOGGLE/PULSE 生效）。
	 */
	protected int getDefaultActiveOutputPower() {
		return normalizeSignalStrength(RedstoneLinkConfig.general().coreOutputPower());
	}

	/**
	 * 应用一次触发请求。
	 * <p>
	 * PULSE 模式会立即激活并调度自动回落，TOGGLE 模式按同 tick 奇偶合并后结算。
	 * </p>
	 */
	private void applyActivation(long sourceSerial, ActivationMode mode, EventMeta eventMeta) {
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		ActivationMode normalizedMode = mode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		if (!acceptByPriority(normalizedMeta.timeKey(), priority, incomingMode, normalizedMeta.seq())) {
			return;
		}

		if (normalizedMode == ActivationMode.PULSE) {
			applyPulseMerged();
			return;
		}

		applyToggleMerged();
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
			int normalizedStrength = normalizeSignalStrength(signalStrength);
			if (!acceptByPriority(eventMeta.timeKey(), 3, EffectiveMode.SYNC, eventMeta.seq())) {
				return;
			}
			boolean bucketChanged = updateSyncSignalStrength(0L, normalizedStrength);
			bucketChanged |= concurrentComponent.clearPulseTruth();
			applyDerivedStateFromTruth();
			markStructuredTruthDirty(bucketChanged);
			return;
		}
		applyActivation(0L, activationMode == null ? configuredMode : activationMode, eventMeta);
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
		if (sourceSerial <= 0L || level == null || level.isClientSide) {
			return;
		}
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		if (!acceptByPriority(normalizedMeta.timeKey(), 3, EffectiveMode.SYNC, normalizedMeta.seq())) {
			return;
		}
		SourceKey sourceKey = new SourceKey(LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		boolean bucketChanged = concurrentComponent.pruneOlderFramesForIncoming(normalizedMeta.timeKey(), EffectiveMode.SYNC);
		int normalizedStrength = normalizeSignalStrength(signalStrength);
		if (removeOnly || normalizedStrength <= 0) {
			bucketChanged |= concurrentComponent.removeRuntimeSimulatedSyncConcurrentSource(sourceKey);
		} else {
			bucketChanged |= concurrentComponent.upsertRuntimeSimulatedSyncConcurrentSource(
				sourceKey,
				normalizedMeta.timeKey(),
				normalizedStrength,
				normalizedMeta.seq()
			);
			concurrentComponent.setPulseUntilGameTime(0L);
			concurrentComponent.setPulseResetArmed(false);
		}
		recomputeSyncTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(normalizedMeta.timeKey(), normalizedMeta.seq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
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
		ActivationMode normalizedMode = activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		TimeKey normalizedTimeKey = eventMeta.timeKey() == null ? TimeKey.of(0L, 0) : eventMeta.timeKey();
		boolean priorityAccepted = acceptByPriority(normalizedTimeKey, priority, incomingMode, eventMeta.seq());
		if (!priorityAccepted && normalizedTimeKey.compareTo(arbitrationComponent.authorityTimeKey()) < 0) {
			return;
		}
		boolean sameSourceHadToggleContribution = normalizedMode == ActivationMode.TOGGLE
			&& deltaAction != DeltaAction.REMOVE
			&& concurrentComponent.resolveToggleContributionBeforePrune(sourceKey);
		boolean bucketChanged = concurrentComponent.pruneOlderFramesForIncoming(normalizedTimeKey, incomingMode);
		recomputeSyncTruthFromConcurrentBuckets();
		if (normalizedMode == ActivationMode.PULSE) {
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= concurrentComponent.removePulseConcurrentSource(sourceKey);
			} else {
				bucketChanged |= concurrentComponent.upsertPulseConcurrentSource(this, sourceKey, normalizedTimeKey, eventMeta.seq());
			}
			bucketChanged |= recomputePulseTruthFromConcurrentBuckets();
			recomputeToggleTruthFromConcurrentBuckets();
		} else {
			concurrentComponent.markToggleSourceTouchedInCurrentFrame(sourceKey);
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= concurrentComponent.removeToggleConcurrentSource(sourceKey);
			} else {
				bucketChanged |= concurrentComponent.upsertToggleConcurrentSource(
					sourceKey,
					normalizedTimeKey,
					eventMeta.seq(),
					sameSourceHadToggleContribution
				);
			}
			recomputeToggleTruthFromConcurrentBuckets();
		}
		recomputeAuthorityFromConcurrentBuckets(normalizedTimeKey, eventMeta.seq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
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
	 * 统一处理“triggerSource 其它失效”delta：剔除该来源的 toggle/pulse/sync 贡献并重算。
	 */
	private void applyTriggerSourceInvalidationDelta(SourceKey sourceKey, DeltaAction deltaAction, EventMeta eventMeta) {
		StructuredBatchMutationAccumulator accumulator = new StructuredBatchMutationAccumulator();
		applyTriggerSourceInvalidationMutation(sourceKey, deltaAction, eventMeta, accumulator);
		finalizeStructuredBatchMutation(accumulator);
	}

	/**
	 * 轻量版 L2：同 tick TOGGLE 按“基准态 + 奇偶”合并。
	 */
	private void applyToggleMerged() {
		arbitrationComponent.applyToggleMerged(concurrentComponent, active);
		applyDerivedStateFromTruth();
	}

	/**
	 * 轻量版 L2：同 tick PULSE 只在到期时间被延长时重新调度。
	 */
	private void applyPulseMerged() {
		int pulseTicks = Math.max(1, getPulseDurationTicks());
		concurrentComponent.setPulseEpoch(concurrentComponent.pulseEpoch() + 1L);
		concurrentComponent.setPulseResetArmed(true);
		if (level != null) {
			long nextExpireTime = level.getGameTime() + pulseTicks;
			if (nextExpireTime > concurrentComponent.pulseUntilGameTime()) {
				concurrentComponent.setPulseUntilGameTime(nextExpireTime);
				schedulePulseReset(pulseTicks);
			}
		} else {
			schedulePulseReset(pulseTicks);
		}
		applyDerivedStateFromTruth();
	}

	/**
	 * 按结构真值推导当前结果态，并统一写回缓存。
	 * <p>
	 * 类间优先级固定：SYNC > PULSE > TOGGLE。
	 * </p>
	 */
	private void applyDerivedStateFromTruth() {
		normalizeAuthorityByTruth();
		int resolvedPower = resolveDerivedOutputPowerFromTruth();
		observationComponent.applyResolvedState(this, arbitrationComponent.authorityTimeKey(), resolvedPower > 0, resolvedPower);
	}

	/**
	 * 计算结构真值对应的输出功率。
	 */
	private int resolveDerivedOutputPowerFromTruth() {
		return switch (resolveAuthorityEffectiveMode()) {
			case SYNC -> normalizeSignalStrength(concurrentComponent.syncSignalMaxStrength());
			case PULSE -> getDefaultActiveOutputPower();
			case TOGGLE -> concurrentComponent.toggleState() ? getDefaultActiveOutputPower() : 0;
			case NONE -> 0;
		};
	}

	/**
	 * 判断脉冲结构真值是否处于生效窗口。
	 */
	private boolean isPulseTruthActive() {
		return concurrentComponent.isPulseTruthActive(this);
	}

	/**
	 * 功率变化且激活态不变时，是否需要同步方块实体到客户端。
	 * <p>
	 * 默认保持同步，依赖方块状态外显的子类可覆写为 false 以减少网络包。
	 * </p>
	 */
	protected boolean shouldSyncClientOnPowerChanged() {
		return true;
	}

	/**
	 * 实体侧邻居扇出去重守卫。
	 * <p>
	 * 仅对 SYNC 生效：复用既有时间粒度键（{@link TimeKey}）对齐仲裁语义，
	 * 仅当“时间键 + 激活态 + 输出功率”发生变化时才允许扇出。
	 * 非 SYNC 模式（TOGGLE/PULSE/NONE）始终放行，避免改变其时序语义。
	 * </p>
	 *
	 * @param resolvedActive 当前解析激活态
	 * @return true 表示应执行扇出；false 表示同时间粒度重复扇出应抑制
	 */
	protected final boolean shouldFanoutByResolvedOutput(boolean resolvedActive) {
		return observationComponent.shouldFanoutByResolvedOutput(
			getEffectiveMode(),
			arbitrationComponent.authorityTimeKey(),
			resolvedActive,
			observationComponent.resolvedOutputPower()
		);
	}

	/**
	 * 返回当前扇出去重时间键的 tick 分量。
	 * <p>
	 * 供子类传递给工具层做统一时间粒度去重，避免写死同 tick 判定。
	 * </p>
	 */
	protected final long getFanoutTimeTick() {
		return observationComponent.fanoutTimeTick(arbitrationComponent.authorityTimeKey());
	}

	/**
	 * 返回当前扇出去重时间键的 slot 分量。
	 * <p>
	 * 与 tick 共同构成时间粒度键，保持与仲裁模型一致。
	 * </p>
	 */
	protected final int getFanoutTimeSlot() {
		return observationComponent.fanoutTimeSlot(arbitrationComponent.authorityTimeKey());
	}

	/**
	 * 同 tick 冲突仲裁。
	 * <p>
	 * 仅在同一个 gameTime 内按优先级裁决：SYNC > PULSE > TOGGLE。
	 * </p>
	 */
	private boolean acceptByPriority(TimeKey eventTimeKey, int incomingPriority, EffectiveMode incomingMode, long incomingSeq) {
		TimeKey previousAuthorityTimeKey = arbitrationComponent.authorityTimeKey();
		EffectiveMode previousAuthorityMode = arbitrationComponent.authorityMode();
		int previousArbitrationPriority = arbitrationComponent.arbitrationPriority();
		boolean accepted = arbitrationComponent.acceptByPriority(
			this,
			concurrentComponent,
			eventTimeKey,
			incomingPriority,
			incomingMode,
			incomingSeq
		);
		if (
			accepted
				&& (
					!Objects.equals(previousAuthorityTimeKey, arbitrationComponent.authorityTimeKey())
						|| previousAuthorityMode != arbitrationComponent.authorityMode()
						|| incomingPriority > previousArbitrationPriority
				)
		) {
			observationComponent.invalidateTickResolvedCache();
		}
		return accepted;
	}

	/**
	 * 维护同步触发源强度缓存，并重算 max 聚合结果。
	 */
	private boolean updateSyncSignalStrength(long sourceSerial, int signalStrength) {
		return concurrentComponent.updateSyncSignalStrength(
			sourceSerial,
			signalStrength,
			arbitrationComponent.authorityTimeKey(),
			arbitrationComponent.authoritySeq()
		);
	}

	/**
	 * 从同步并发桶重建 SYNC 真值（来源表 + max + maxSources）。
	 */
	private void recomputeSyncTruthFromConcurrentBuckets() {
		concurrentComponent.recomputeSyncTruthFromConcurrentBuckets();
	}

	/**
	 * 从脉冲并发桶重建 PULSE 真值（有效下落窗口）。
	 */
	private boolean recomputePulseTruthFromConcurrentBuckets() {
		return concurrentComponent.recomputePulseTruthFromConcurrentBuckets(this);
	}

	/**
	 * 结构化真值已变化时，独立标记区块实体脏态，避免仅靠输出态变化触发落盘。
	 */
	private void markStructuredTruthDirty(boolean truthChanged) {
		if (truthChanged) {
			setChanged();
		}
	}

	/**
	 * 从切换并发桶重建 TOGGLE 真值（并发计数 + 最终锁存态）。
	 */
	private void recomputeToggleTruthFromConcurrentBuckets() {
		concurrentComponent.recomputeToggleTruthFromConcurrentBuckets();
		boolean baseActive = concurrentComponent.syncSignalMaxStrength() > 0 || isPulseTruthActive();
		boolean oddParity = (concurrentComponent.toggleConcurrentCount() & 1) == 1;
		concurrentComponent.setToggleState(oddParity ? !baseActive : baseActive);
	}

	/**
	 * 按并发桶候选重算 authority，确保 REMOVE 后可回退到仍有效的下层真值。
	 */
	private void recomputeAuthorityFromConcurrentBuckets(TimeKey fallbackTimeKey, long fallbackSeq) {
		arbitrationComponent.recomputeAuthorityFromConcurrentBuckets(concurrentComponent, fallbackTimeKey, fallbackSeq, this);
	}

	/**
	 * 按 authority 与当前结构真值计算运行态生效模式。
	 */
	private EffectiveMode resolveAuthorityEffectiveMode() {
		return arbitrationComponent.resolveAuthorityEffectiveMode(concurrentComponent, this);
	}

	/**
	 * 结构真值变化后，校正 authority 的有效性。
	 */
	private void normalizeAuthorityByTruth() {
		arbitrationComponent.normalizeAuthorityByTruth(concurrentComponent, this);
	}

	/**
	 * 兜底归一化事件元数据，避免空入参污染仲裁。
	 */
	private EventMeta normalizeEventMeta(EventMeta eventMeta) {
		return eventMeta == null ? EventMeta.now(level) : eventMeta;
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
		if (!canBeTriggeredBy(batchEntry.sourceSerial())) {
			return;
		}
		SourceKey sourceKey = new SourceKey(batchEntry.sourceType(), batchEntry.sourceSerial());
		if (!LinkNodeSemantics.isAllowedForRole(sourceKey.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		EventMeta normalizedMeta = normalizeEventMeta(batchEntry.eventMeta());
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
	 * 批次内应用 SYNC 变更，只更新来源桶与仲裁时间，不立即提交结果。
	 */
	private void applySyncDeltaMutation(
		SourceKey sourceKey,
		DeltaAction deltaAction,
		int signalStrength,
		EventMeta eventMeta,
		StructuredBatchMutationAccumulator accumulator
	) {
		if (!acceptByPriority(eventMeta.timeKey(), 3, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = concurrentComponent.pruneOlderFramesForIncoming(eventMeta.timeKey(), EffectiveMode.SYNC);
		int normalizedStrength = normalizeSignalStrength(signalStrength);
		if (deltaAction == DeltaAction.REMOVE || normalizedStrength <= 0) {
			bucketChanged |= concurrentComponent.removeSyncConcurrentSource(sourceKey);
		} else {
			bucketChanged |= concurrentComponent.upsertSyncConcurrentSource(
				sourceKey,
				eventMeta.timeKey(),
				normalizedStrength,
				eventMeta.seq()
			);
			concurrentComponent.setPulseUntilGameTime(0L);
			concurrentComponent.setPulseResetArmed(false);
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
		ActivationMode normalizedMode = activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		TimeKey normalizedTimeKey = eventMeta.timeKey() == null ? TimeKey.of(0L, 0) : eventMeta.timeKey();
		boolean priorityAccepted = acceptByPriority(normalizedTimeKey, priority, incomingMode, eventMeta.seq());
		if (!priorityAccepted && normalizedTimeKey.compareTo(arbitrationComponent.authorityTimeKey()) < 0) {
			return;
		}

		boolean sameSourceHadToggleContribution = normalizedMode == ActivationMode.TOGGLE
			&& deltaAction != DeltaAction.REMOVE
			&& concurrentComponent.resolveToggleContributionBeforePrune(sourceKey);
		boolean bucketChanged = concurrentComponent.pruneOlderFramesForIncoming(normalizedTimeKey, incomingMode);
		if (normalizedMode == ActivationMode.PULSE) {
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= concurrentComponent.removePulseConcurrentSource(sourceKey);
			} else {
				bucketChanged |= concurrentComponent.upsertPulseConcurrentSource(this, sourceKey, normalizedTimeKey, eventMeta.seq());
			}
			accumulator.record(eventMeta, bucketChanged, true);
			return;
		}

		concurrentComponent.markToggleSourceTouchedInCurrentFrame(sourceKey);
		if (deltaAction == DeltaAction.REMOVE) {
			bucketChanged |= concurrentComponent.removeToggleConcurrentSource(sourceKey);
		} else {
			bucketChanged |= concurrentComponent.upsertToggleConcurrentSource(
				sourceKey,
				normalizedTimeKey,
				eventMeta.seq(),
				sameSourceHadToggleContribution
			);
		}
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批次内应用“triggerSource 区块卸载失效”，仅剔除 sync 贡献。
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
		if (!acceptByPriority(eventMeta.timeKey(), 3, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = concurrentComponent.removeSyncConcurrentSource(sourceKey);
		accumulator.record(eventMeta, bucketChanged, false);
	}

	/**
	 * 批次内应用“triggerSource 其它失效”，剔除该来源的全部结构化贡献。
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
		if (!acceptByPriority(eventMeta.timeKey(), 3, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = concurrentComponent.removeSyncConcurrentSource(sourceKey);
		bucketChanged |= concurrentComponent.removePulseConcurrentSource(sourceKey);
		bucketChanged |= concurrentComponent.removeToggleConcurrentSource(sourceKey);
		accumulator.record(eventMeta, bucketChanged, true);
	}

	/**
	 * 批末统一提交结构化变更，避免逐条 delta 重复重算。
	 */
	private void finalizeStructuredBatchMutation(StructuredBatchMutationAccumulator accumulator) {
		if (accumulator == null || !accumulator.acceptedAny()) {
			return;
		}
		recomputeSyncTruthFromConcurrentBuckets();
		if (accumulator.requiresPulseTruthRecompute()) {
			accumulator.mergeBucketChanged(recomputePulseTruthFromConcurrentBuckets());
		}
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(accumulator.fallbackTimeKey(), accumulator.fallbackSeq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(accumulator.bucketChanged());
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

	/**
	 * 归一化输入强度，避免异常值污染聚合。
	 */
	private static int normalizeSignalStrength(int signalStrength) {
		return SignalStrengths.clamp(signalStrength);
	}




	protected final void setActive(boolean active) {
		if (level == null || level.isClientSide) {
			return;
		}
		if (this.active == active) {
			return;
		}
		this.active = active;
		onActiveChanged(active);
		syncToClient();
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		concurrentComponent.setPulseUntilGameTime(
			Math.max(0L, tag.getLong(ActivatableTargetPersistenceHelper.KEY_PULSE_UNTIL_GAME_TIME))
		);
		concurrentComponent.setPulseEpoch(
			Math.max(0L, tag.getLong(ActivatableTargetPersistenceHelper.KEY_PULSE_EPOCH))
		);
		concurrentComponent.setToggleState(tag.getBoolean(ActivatableTargetPersistenceHelper.KEY_TOGGLE_STATE));
		concurrentComponent.setToggleConcurrentCount(
			Math.max(0, tag.getInt(ActivatableTargetPersistenceHelper.KEY_TOGGLE_CONCURRENT_COUNT))
		);
		ActivatableTargetPersistenceHelper.loadSyncSourceStrengths(tag, concurrentComponent);
		concurrentComponent.setSyncSignalMaxStrength(concurrentComponent.recalculateSyncMaxStrengthAndSources());
		if (
			concurrentComponent.syncSignalMaxStrength() <= 0
				&& tag.contains(ActivatableTargetPersistenceHelper.KEY_SYNC_MAX_SOURCES, Tag.TAG_LONG_ARRAY)
		) {
			concurrentComponent.syncSignalMaxSources().clear();
			for (long sourceSerial : tag.getLongArray(ActivatableTargetPersistenceHelper.KEY_SYNC_MAX_SOURCES)) {
				if (sourceSerial > 0L) {
					concurrentComponent.syncSignalMaxSources().add(sourceSerial);
				}
			}
		}
		if (tag.contains(ActivatableTargetPersistenceHelper.KEY_CONFIGURED_MODE)) {
			configuredMode = ActivationMode.fromName(tag.getString(ActivatableTargetPersistenceHelper.KEY_CONFIGURED_MODE));
		}
		if (tag.contains(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_MODE, Tag.TAG_STRING)) {
			arbitrationComponent.setAuthorityMode(
				ActivatableTargetPersistenceHelper.parseEffectiveMode(
					tag.getString(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_MODE)
				)
			);
		} else {
			arbitrationComponent.setAuthorityMode(deriveLegacyAuthorityModeFromTruth());
		}
		arbitrationComponent.setAuthorityTimeKey(
			TimeKey.of(
				Math.max(0L, tag.getLong(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_TICK)),
				Math.max(0, tag.getInt(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_SLOT))
			)
		);
		arbitrationComponent.setAuthoritySeq(
			Math.max(0L, tag.getLong(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_SEQ))
		);
		boolean hasConcurrentTruth = ActivatableTargetPersistenceHelper.loadConcurrentBuckets(tag, concurrentComponent);
		if (hasConcurrentTruth) {
			recomputeSyncTruthFromConcurrentBuckets();
			recomputePulseTruthFromConcurrentBuckets();
			recomputeToggleTruthFromConcurrentBuckets();
			recomputeAuthorityFromConcurrentBuckets(
				arbitrationComponent.authorityTimeKey(),
				arbitrationComponent.authoritySeq()
			);
		}
		if (
			level != null
				&& concurrentComponent.pulseUntilGameTime() > 0L
				&& level.getGameTime() >= concurrentComponent.pulseUntilGameTime()
		) {
			concurrentComponent.setPulseUntilGameTime(0L);
		}
		concurrentComponent.setPulseResetArmed(concurrentComponent.pulseUntilGameTime() > 0L);
		rebuildDerivedCacheFromTruth();
		observationComponent.setPendingLoadBlockStateSync(shouldQueueLoadBlockStateSync(active));

		concurrentComponent.resetRuntimeTransientAfterLoad();
		arbitrationComponent.setArbitrationTimeKey(TimeKey.minValue());
		arbitrationComponent.setArbitrationPriority(Integer.MIN_VALUE);
		arbitrationComponent.setToggleMergeInitialized(false);
		arbitrationComponent.setToggleMergeParity(false);
		observationComponent.resetTransientAfterLoad();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (active) {
			tag.putBoolean(ActivatableTargetPersistenceHelper.KEY_ACTIVE, true);
		}
		if (observationComponent.resolvedOutputPower() > 0) {
			tag.putInt(
				ActivatableTargetPersistenceHelper.KEY_RESOLVED_OUTPUT_POWER,
				normalizeSignalStrength(observationComponent.resolvedOutputPower())
			);
		}
		if (concurrentComponent.pulseUntilGameTime() > 0L) {
			tag.putLong(
				ActivatableTargetPersistenceHelper.KEY_PULSE_UNTIL_GAME_TIME,
				concurrentComponent.pulseUntilGameTime()
			);
		}
		if (concurrentComponent.pulseEpoch() > 0L) {
			tag.putLong(ActivatableTargetPersistenceHelper.KEY_PULSE_EPOCH, concurrentComponent.pulseEpoch());
		}
		if (concurrentComponent.toggleState()) {
			tag.putBoolean(ActivatableTargetPersistenceHelper.KEY_TOGGLE_STATE, true);
		}
		ActivatableTargetConcurrentBucketComponent.PersistentSyncSnapshot persistentSyncSnapshot =
			concurrentComponent.buildPersistentSyncSnapshot();
		ActivatableTargetPersistenceHelper.writeSyncSourceStrengths(tag, persistentSyncSnapshot.strengthBySource());
		if (!persistentSyncSnapshot.maxSources().isEmpty()) {
			long[] serialArray = new long[persistentSyncSnapshot.maxSources().size()];
			int index = 0;
			for (Long sourceSerial : persistentSyncSnapshot.maxSources()) {
				serialArray[index++] = sourceSerial;
			}
			tag.putLongArray(ActivatableTargetPersistenceHelper.KEY_SYNC_MAX_SOURCES, serialArray);
		}
		tag.putString(ActivatableTargetPersistenceHelper.KEY_CONFIGURED_MODE, configuredMode.name());
		tag.putString(ActivatableTargetPersistenceHelper.KEY_AUTHORITY_MODE, arbitrationComponent.authorityMode().name());
		tag.putLong(
			ActivatableTargetPersistenceHelper.KEY_AUTHORITY_TICK,
			Math.max(0L, arbitrationComponent.authorityTimeKey().tick())
		);
		tag.putInt(
			ActivatableTargetPersistenceHelper.KEY_AUTHORITY_SLOT,
			Math.max(0, arbitrationComponent.authorityTimeKey().slot())
		);
		tag.putLong(
			ActivatableTargetPersistenceHelper.KEY_AUTHORITY_SEQ,
			Math.max(0L, arbitrationComponent.authoritySeq())
		);
		tag.putInt(
			ActivatableTargetPersistenceHelper.KEY_TOGGLE_CONCURRENT_COUNT,
			Math.max(0, concurrentComponent.toggleConcurrentCount())
		);
		ActivatableTargetPersistenceHelper.writeConcurrentBuckets(tag, concurrentComponent);
	}

	/**
	 * 读档时按结构真值重建派生缓存（active/output）。
	 */
	private void rebuildDerivedCacheFromTruth() {
		normalizeAuthorityByTruth();
		observationComponent.setResolvedOutputPowerRaw(resolveDerivedOutputPowerFromTruth());
		active = observationComponent.resolvedOutputPower() > 0;
	}

	private EffectiveMode deriveLegacyAuthorityModeFromTruth() {
		if (concurrentComponent.syncSignalMaxStrength() > 0) {
			return EffectiveMode.SYNC;
		}
		if (isPulseTruthActive()) {
			return EffectiveMode.PULSE;
		}
		if (concurrentComponent.toggleState()) {
			return EffectiveMode.TOGGLE;
		}
		return EffectiveMode.NONE;
	}

	/**
	 * 测试辅助：暴露仲裁组件，避免内部测试绑死主类字段布局。
	 */
	ActivatableTargetArbitrationComponent internalArbitrationComponent() {
		return arbitrationComponent;
	}

	/**
	 * 测试辅助：暴露并发来源桶组件，避免内部测试绑死主类字段布局。
	 */
	ActivatableTargetConcurrentBucketComponent internalConcurrentComponent() {
		return concurrentComponent;
	}

	/**
	 * 测试辅助：暴露同步观测组件，避免内部测试绑死主类字段布局。
	 */
	ActivatableTargetObservationComponent internalObservationComponent() {
		return observationComponent;
	}
}
