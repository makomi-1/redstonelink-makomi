package com.makomi.block.entity;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SignalStrengths;
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
	private final ActivatableTargetDispatchSupport dispatchSupport = new ActivatableTargetDispatchSupport(this);

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
	 * 该路径不执行 TOGGLE/PULSE 语义转换；
	 * 同 tick 内多个 `sync` 来源按强度 `max` 聚合，跨 tick 则仅保留最新一帧。
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
		dispatchSupport.applyDispatchDelta(
			deltaKind,
			deltaAction,
			sourceType,
			sourceSerial,
			activationMode,
			signalStrength,
			eventMeta
		);
	}

	/**
	 * 批量应用目标级批窗口内的结构化变更。
	 * <p>
	 * 该入口会先按时间键与固定优先级排序，再在批末统一执行一次真值重算与派生态写回。
	 * </p>
	 */
	public final void applyDispatchBatch(List<DispatchBatchEntry> batchEntries) {
		dispatchSupport.applyDispatchBatch(batchEntries);
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
		dispatchSupport.applyRuntimeSimulatedSyncSource(sourceSerial, signalStrength, eventMeta);
	}

	/**
	 * 移除运行态模拟 SYNC 输入。
	 */
	public final void removeRuntimeSimulatedSyncSource(long sourceSerial, EventMeta eventMeta) {
		dispatchSupport.removeRuntimeSimulatedSyncSource(sourceSerial, eventMeta);
	}

	public final void onPulseTick() {
		dispatchSupport.onPulseTick();
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
	TimeKey resolvePulseExpireFallbackTimeKey(long nowTick) {
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
	void applyActivation(long sourceSerial, ActivationMode mode, EventMeta eventMeta) {
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		recomputeSyncTruthFromConcurrentBuckets();
		if (concurrentComponent.hasEffectiveSyncTruth()) {
			return;
		}
		ActivationMode normalizedMode = mode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		int priority = ActivatableTargetArbitrationComponent.priorityOfActivationMode(normalizedMode);
		EffectiveMode incomingMode = ActivatableTargetArbitrationComponent.effectiveModeOfActivationMode(normalizedMode);
		boolean baseToggleState = normalizedMode == ActivationMode.TOGGLE && resolveCurrentTargetStateBeforeToggle();
		if (!acceptByPriority(normalizedMeta.timeKey(), priority, incomingMode, normalizedMeta.seq())) {
			return;
		}

		if (normalizedMode == ActivationMode.PULSE) {
			applyPulseMerged(normalizedMeta);
			return;
		}

		applyToggleMerged(normalizedMeta, baseToggleState);
	}

	/**
	 * 轻量版 L2：同 tick TOGGLE 按“当前解析目标状态 + 奇偶”合并。
	 */
	void applyToggleMerged(EventMeta eventMeta) {
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		boolean currentTargetState = resolveCurrentTargetStateBeforeToggle();
		applyToggleMerged(normalizedMeta, currentTargetState);
	}

	/**
	 * 按已解析出的当前目标状态写入一次 toggle 合并结果。
	 */
	void applyToggleMerged(EventMeta eventMeta, boolean currentTargetState) {
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		recomputeSyncTruthFromConcurrentBuckets();
		if (concurrentComponent.hasEffectiveSyncTruth()) {
			return;
		}
		boolean nextToggleState = arbitrationComponent.applyToggleMerged(concurrentComponent, currentTargetState);
		concurrentComponent.recordToggleSnapshot(nextToggleState, normalizedMeta.timeKey(), normalizedMeta.seq());
		recomputeSyncTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(normalizedMeta.timeKey(), normalizedMeta.seq());
		applyDerivedStateFromTruth();
	}

	void applyToggleMerged() {
		applyToggleMerged(EventMeta.now(level));
	}

	/**
	 * 轻量版 L2：同 tick PULSE 只在到期时间被延长时重新调度。
	 */
	void applyPulseMerged(EventMeta eventMeta) {
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		recomputeSyncTruthFromConcurrentBuckets();
		if (concurrentComponent.hasEffectiveSyncTruth()) {
			return;
		}
		concurrentComponent.recordPulseSnapshot(this, normalizedMeta.timeKey(), normalizedMeta.seq());
		recomputeSyncTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(normalizedMeta.timeKey(), normalizedMeta.seq());
		applyDerivedStateFromTruth();
	}

	void applyPulseMerged() {
		applyPulseMerged(EventMeta.now(level));
	}

	/**
	 * 按结构真值推导当前结果态，并统一写回缓存。
	 * <p>
	 * 类间优先级固定：SYNC > PULSE > TOGGLE。
	 * </p>
	 */
	void applyDerivedStateFromTruth() {
		normalizeAuthorityByTruth();
		int resolvedPower = resolveDerivedOutputPowerFromTruth();
		observationComponent.applyResolvedState(this, arbitrationComponent.authorityTimeKey(), resolvedPower > 0, resolvedPower);
	}

	/**
	 * 在写入新 toggle 前，解析当前目标结果态。
	 * <p>
	 * 必须在本次 toggle 更新 authority 之前取值；否则较新的 toggle 会提前遮掉仍在生效的
	 * `sync/pulse`，把“对当前状态取反”误算成“对空态取反”。
	 * </p>
	 */
	boolean resolveCurrentTargetStateBeforeToggle() {
		normalizeAuthorityByTruth();
		return resolveDerivedOutputPowerFromTruth() > 0;
	}

	/**
	 * 计算结构真值对应的输出功率。
	 */
	int resolveDerivedOutputPowerFromTruth() {
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
	boolean isPulseTruthActive() {
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
	boolean acceptByPriority(TimeKey eventTimeKey, int incomingPriority, EffectiveMode incomingMode, long incomingSeq) {
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
	boolean updateSyncSignalStrength(long sourceSerial, int signalStrength) {
		return concurrentComponent.updateSyncSignalStrength(
			sourceSerial,
			signalStrength,
			arbitrationComponent.authorityTimeKey(),
			arbitrationComponent.authoritySeq()
		);
	}

	/**
	 * 从最新 `sync` 帧重建 SYNC 真值（来源表 + max + maxSources）。
	 */
	void recomputeSyncTruthFromConcurrentBuckets() {
		concurrentComponent.recomputeSyncTruthFromConcurrentBuckets();
	}

	/**
	 * 从 `pulse` 事件快照重建 PULSE 真值（有效下落窗口）。
	 */
	boolean recomputePulseTruthFromConcurrentBuckets() {
		return concurrentComponent.recomputePulseTruthFromConcurrentBuckets(this);
	}

	/**
	 * 结构化真值已变化时，独立标记区块实体脏态，避免仅靠输出态变化触发落盘。
	 */
	void markStructuredTruthDirty(boolean truthChanged) {
		if (truthChanged) {
			setChanged();
		}
	}

	/**
	 * 从 `toggle` 事件快照重建 TOGGLE 真值。
	 */
	void recomputeToggleTruthFromConcurrentBuckets() {
		concurrentComponent.recomputeToggleTruthFromConcurrentBuckets();
	}

	/**
	 * 按并发桶候选重算 authority，确保 REMOVE 后可回退到仍有效的下层真值。
	 */
	void recomputeAuthorityFromConcurrentBuckets(TimeKey fallbackTimeKey, long fallbackSeq) {
		arbitrationComponent.recomputeAuthorityFromConcurrentBuckets(concurrentComponent, fallbackTimeKey, fallbackSeq, this);
	}

	/**
	 * 按 authority 与当前结构真值计算运行态生效模式。
	 */
	EffectiveMode resolveAuthorityEffectiveMode() {
		return arbitrationComponent.resolveAuthorityEffectiveMode(concurrentComponent, this);
	}

	/**
	 * 结构真值变化后，校正 authority 的有效性。
	 */
	void normalizeAuthorityByTruth() {
		arbitrationComponent.normalizeAuthorityByTruth(concurrentComponent, this);
	}

	/**
	 * 兜底归一化事件元数据，避免空入参污染仲裁。
	 */
	EventMeta normalizeEventMeta(EventMeta eventMeta) {
		return eventMeta == null ? EventMeta.now(level) : eventMeta;
	}

	/**
	 * 归一化输入强度，避免异常值污染聚合。
	 */
	static int normalizeSignalStrength(int signalStrength) {
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
			concurrentComponent.clearPulseTruth();
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
		if (concurrentComponent.toggleSnapshotRecorded() || concurrentComponent.toggleState()) {
			tag.putBoolean(ActivatableTargetPersistenceHelper.KEY_TOGGLE_STATE, concurrentComponent.toggleState());
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
		if (concurrentComponent.toggleSnapshotRecorded()) {
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
