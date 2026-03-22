package com.makomi.block.entity;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.NeighborFanoutUtil;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
	private static final String KEY_ACTIVE = "Active";
	private static final String KEY_CONFIGURED_MODE = "ConfiguredMode";
	private static final String KEY_PULSE_UNTIL_GAME_TIME = "PulseExpireGameTime";
	private static final String KEY_PULSE_EPOCH = "PulseEpoch";
	private static final String KEY_TOGGLE_STATE = "ToggleState";
	private static final String KEY_RESOLVED_OUTPUT_POWER = "ResolvedOutputPower";
	private static final String KEY_SYNC_SOURCE_STRENGTHS = "SyncSourceStrengths";
	private static final String KEY_SYNC_SOURCE_SERIAL = "Serial";
	private static final String KEY_SYNC_SOURCE_STRENGTH = "Strength";
	private static final String KEY_SYNC_MAX_SOURCES = "SyncMaxSources";
	private static final String KEY_AUTHORITY_MODE = "AuthorityMode";
	private static final String KEY_AUTHORITY_TICK = "AuthorityTick";
	private static final String KEY_AUTHORITY_SLOT = "AuthoritySlot";
	private static final String KEY_AUTHORITY_SEQ = "AuthoritySeq";
	private static final String KEY_SYNC_CONCURRENT_ENTRIES = "SyncConcurrentEntries";
	private static final String KEY_PULSE_CONCURRENT_ENTRIES = "PulseConcurrentEntries";
	private static final String KEY_TOGGLE_CONCURRENT_ENTRIES = "ToggleConcurrentEntries";
	private static final String KEY_CONCURRENT_SOURCE_TYPE = "SourceType";
	private static final String KEY_CONCURRENT_SOURCE_SERIAL = "SourceSerial";
	private static final String KEY_CONCURRENT_TICK = "Tick";
	private static final String KEY_CONCURRENT_SLOT = "Slot";
	private static final String KEY_CONCURRENT_SEQ = "Seq";
	private static final String KEY_CONCURRENT_STRENGTH = "Strength";
	private static final String KEY_CONCURRENT_UNTIL_TICK = "UntilTick";
	private static final String KEY_CONCURRENT_CONTRIBUTES = "Contributes";
	private static final String KEY_TOGGLE_CONCURRENT_COUNT = "ToggleConcurrentCount";

	private static final int PRIORITY_TOGGLE = 1;
	private static final int PRIORITY_PULSE = 2;
	private static final int PRIORITY_SYNC = 3;

	private boolean active;
	private ActivationMode configuredMode = ActivationMode.TOGGLE;
	private long pulseUntilGameTime;
	private long pulseEpoch;
	private boolean toggleState;
	private int resolvedOutputPower;
	// 脉冲回落任务是否仍有效；用于让 SYNC 能失效已排队的历史回落 tick。
	private boolean pulseResetArmed;

	// 运行态 authority：先比时间键，再比同粒度固定优先级（SYNC > PULSE > TOGGLE）。
	private TimeKey authorityTimeKey = TimeKey.minValue();
	private EffectiveMode authorityMode = EffectiveMode.NONE;
	private long authoritySeq;

	// 同时间键仲裁帧：用于优先级覆盖与轻量合并。
	private TimeKey arbitrationTimeKey = TimeKey.minValue();
	private int arbitrationPriority = Integer.MIN_VALUE;
	private boolean tickResolvedInitialized;
	private boolean tickResolvedState;
	private int tickResolvedPower;
	// 实体侧扇出去重：同时间粒度同输出值仅允许一次邻居扇出。
	private boolean fanoutResolvedInitialized;
	private TimeKey fanoutResolvedTimeKey = TimeKey.minValue();
	private boolean fanoutResolvedState;
	private int fanoutResolvedPower;

	// TOGGLE 同 tick 合并：以 tick 内基准态 + 奇偶翻转计算结果。
	private boolean toggleMergeInitialized;
	private boolean toggleMergeBaseActive;
	private boolean toggleMergeParity;

	// SYNC 多源聚合缓存：sourceSerial -> signalStrength(0~15)，最终态采用 max 强度。
	private final Map<Long, Integer> syncSignalStrengthBySource = new HashMap<>();
	private int syncSignalMaxStrength;
	// 并列最大强度来源集合：用于稳定审计输出，不依赖事件到达顺序。
	private final Set<Long> syncSignalMaxSources = new TreeSet<>();

	// P6 并发桶来源表：按时间键组织来源贡献，统一用于 UPSERT/REMOVE 重算。
	private final NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> syncConcurrentBuckets = new TreeMap<>();
	private final NavigableMap<TimeKey, Map<SourceKey, PulseConcurrentEntry>> pulseConcurrentBuckets = new TreeMap<>();
	private final NavigableMap<TimeKey, Map<SourceKey, ToggleConcurrentEntry>> toggleConcurrentBuckets = new TreeMap<>();
	private int toggleConcurrentCount;
	// toggle 帧级快照：保证同时间粒度内即使高优先级事件先 prune，后到 toggle 仍能看到上一轮旧贡献。
	private final Set<SourceKey> toggleFrameStartContributors = new TreeSet<>();
	private final Set<SourceKey> toggleSourcesTouchedInCurrentFrame = new TreeSet<>();

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

	private record SyncConcurrentEntry(int strength, long seq) {
		private SyncConcurrentEntry {
			strength = SignalStrengths.clamp(strength);
			seq = Math.max(0L, seq);
		}
	}

	private record PulseConcurrentEntry(long untilGameTick, long seq) {
		private PulseConcurrentEntry {
			untilGameTick = Math.max(0L, untilGameTick);
			seq = Math.max(0L, seq);
		}
	}

	private record ToggleConcurrentEntry(boolean contributes, long seq) {
		private ToggleConcurrentEntry {
			seq = Math.max(0L, seq);
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
		return active ? resolvedOutputPower : 0;
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
		if (syncSignalMaxSources.isEmpty()) {
			return List.of();
		}
		return List.copyOf(new ArrayList<>(syncSignalMaxSources));
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

	public final void onPulseTick() {
		// tick 统一按并发来源桶重算脉冲窗口，到期来源会在重算中自动剔除。
		if (level == null || level.isClientSide) {
			return;
		}
		if (!pulseResetArmed && pulseConcurrentBuckets.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		boolean bucketChanged = recomputePulseTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(TimeKey.of(now, 0), authoritySeq);
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
	}

	protected boolean canBeTriggeredBy(long sourceSerial) {
		return true;
	}

	protected int getPulseDurationTicks() {
		return RedstoneLinkConfig.pulseDurationTicks();
	}

	protected abstract void onActiveChanged(boolean active);

	protected abstract void schedulePulseReset(int pulseTicks);

	/**
	 * 默认激活输出功率（TOGGLE/PULSE 生效）。
	 */
	protected int getDefaultActiveOutputPower() {
		return normalizeSignalStrength(RedstoneLinkConfig.coreOutputPower());
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

		int priority = mode == ActivationMode.PULSE ? PRIORITY_PULSE : PRIORITY_TOGGLE;
		EffectiveMode incomingMode = mode == ActivationMode.PULSE ? EffectiveMode.PULSE : EffectiveMode.TOGGLE;
		if (!acceptByPriority(normalizedMeta.timeKey(), priority, incomingMode, normalizedMeta.seq())) {
			return;
		}

		if (mode == ActivationMode.PULSE) {
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
			if (!acceptByPriority(eventMeta.timeKey(), PRIORITY_SYNC, EffectiveMode.SYNC, eventMeta.seq())) {
				return;
			}
			boolean bucketChanged = updateSyncSignalStrength(0L, normalizedStrength);
			bucketChanged |= clearPulseTruth();
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
		if (!acceptByPriority(eventMeta.timeKey(), PRIORITY_SYNC, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = pruneOlderFramesForIncoming(eventMeta.timeKey(), EffectiveMode.SYNC);
		int normalizedStrength = normalizeSignalStrength(signalStrength);
		if (deltaAction == DeltaAction.REMOVE || normalizedStrength <= 0) {
			bucketChanged |= removeSyncConcurrentSource(sourceKey);
		} else {
			bucketChanged |= upsertSyncConcurrentSource(sourceKey, eventMeta.timeKey(), normalizedStrength, eventMeta.seq());
			// 同步语义生效时，不应受历史脉冲回落影响。
			pulseUntilGameTime = 0L;
			pulseResetArmed = false;
		}
		recomputeSyncTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(eventMeta.timeKey(), eventMeta.seq());
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
		EffectiveMode incomingMode = normalizedMode == ActivationMode.PULSE ? EffectiveMode.PULSE : EffectiveMode.TOGGLE;
		int priority = normalizedMode == ActivationMode.PULSE ? PRIORITY_PULSE : PRIORITY_TOGGLE;
		TimeKey normalizedTimeKey = eventMeta.timeKey() == null ? TimeKey.of(0L, 0) : eventMeta.timeKey();
		boolean priorityAccepted = acceptByPriority(normalizedTimeKey, priority, incomingMode, eventMeta.seq());
		if (!priorityAccepted && normalizedTimeKey.compareTo(authorityTimeKey) < 0) {
			return;
		}
		// TOGGLE 需要在 prune 前先记住“同源是否已有贡献”，否则 later toggle 会把自己旧贡献误判为不存在。
		boolean sameSourceHadToggleContribution = normalizedMode == ActivationMode.TOGGLE
			&& deltaAction != DeltaAction.REMOVE
			&& resolveToggleContributionBeforePrune(sourceKey);
		boolean bucketChanged = pruneOlderFramesForIncoming(normalizedTimeKey, incomingMode);
		recomputeSyncTruthFromConcurrentBuckets();
		if (normalizedMode == ActivationMode.PULSE) {
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= removePulseConcurrentSource(sourceKey);
			} else {
				bucketChanged |= upsertPulseConcurrentSource(sourceKey, normalizedTimeKey, eventMeta.seq());
			}
			bucketChanged |= recomputePulseTruthFromConcurrentBuckets();
			recomputeToggleTruthFromConcurrentBuckets();
		} else {
			markToggleSourceTouchedInCurrentFrame(sourceKey);
			if (deltaAction == DeltaAction.REMOVE) {
				bucketChanged |= removeToggleConcurrentSource(sourceKey);
			} else {
				bucketChanged |= upsertToggleConcurrentSource(sourceKey, normalizedTimeKey, eventMeta.seq(), sameSourceHadToggleContribution);
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
		if (deltaAction != DeltaAction.REMOVE) {
			return;
		}
		// 失效事件按最高优先级（SYNC）做防旧判断，避免旧事件回放污染现态。
		if (!acceptByPriority(eventMeta.timeKey(), PRIORITY_SYNC, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = removeSyncConcurrentSource(sourceKey);
		recomputeSyncTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(eventMeta.timeKey(), eventMeta.seq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
	}

	/**
	 * 统一处理“triggerSource 其它失效”delta：剔除该来源的 toggle/pulse/sync 贡献并重算。
	 */
	private void applyTriggerSourceInvalidationDelta(SourceKey sourceKey, DeltaAction deltaAction, EventMeta eventMeta) {
		if (deltaAction != DeltaAction.REMOVE) {
			return;
		}
		if (!acceptByPriority(eventMeta.timeKey(), PRIORITY_SYNC, EffectiveMode.SYNC, eventMeta.seq())) {
			return;
		}
		boolean bucketChanged = removeSyncConcurrentSource(sourceKey);
		bucketChanged |= removePulseConcurrentSource(sourceKey);
		bucketChanged |= removeToggleConcurrentSource(sourceKey);
		recomputeSyncTruthFromConcurrentBuckets();
		bucketChanged |= recomputePulseTruthFromConcurrentBuckets();
		recomputeToggleTruthFromConcurrentBuckets();
		recomputeAuthorityFromConcurrentBuckets(eventMeta.timeKey(), eventMeta.seq());
		applyDerivedStateFromTruth();
		markStructuredTruthDirty(bucketChanged);
	}

	/**
	 * 写入或覆盖同步来源贡献（同 sourceKey 仅保留最新）。
	 */
	private boolean upsertSyncConcurrentSource(SourceKey sourceKey, TimeKey timeKey, int strength, long seq) {
		SyncConcurrentEntry nextEntry = new SyncConcurrentEntry(strength, seq);
		if (hasExactConcurrentEntry(syncConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(syncConcurrentBuckets, sourceKey);
		Map<SourceKey, SyncConcurrentEntry> bucket = syncConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		return true;
	}

	private boolean removeSyncConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(syncConcurrentBuckets, sourceKey);
	}

	/**
	 * 写入或覆盖脉冲来源贡献（同 sourceKey 仅保留最新）。
	 */
	private boolean upsertPulseConcurrentSource(SourceKey sourceKey, TimeKey timeKey, long seq) {
		int pulseTicks = Math.max(1, getPulseDurationTicks());
		long now = level == null ? 0L : level.getGameTime();
		long untilTick = now + pulseTicks;
		PulseConcurrentEntry nextEntry = new PulseConcurrentEntry(untilTick, seq);
		if (hasExactConcurrentEntry(pulseConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(pulseConcurrentBuckets, sourceKey);
		Map<SourceKey, PulseConcurrentEntry> bucket = pulseConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		pulseEpoch++;
		if (level != null) {
			schedulePulseReset(pulseTicks);
		}
		return true;
	}

	private boolean removePulseConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(pulseConcurrentBuckets, sourceKey);
	}

	/**
	 * 写入或覆盖切换来源贡献：同来源再次 UPSERT 等价于翻转贡献位。
	 */
	private boolean upsertToggleConcurrentSource(SourceKey sourceKey, TimeKey timeKey, long seq, boolean hadContributionBeforePrune) {
		boolean next = !hadContributionBeforePrune;
		if (!next) {
			return removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
		}
		ToggleConcurrentEntry nextEntry = new ToggleConcurrentEntry(true, seq);
		if (hasExactConcurrentEntry(toggleConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
		Map<SourceKey, ToggleConcurrentEntry> bucket = toggleConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		return true;
	}

	private boolean removeToggleConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
	}

	/**
	 * 从并发桶集合中移除指定来源，并清理空桶。
	 */
	private static <V> boolean removeSourceFromConcurrentBuckets(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		SourceKey sourceKey
	) {
		if (buckets.isEmpty() || sourceKey == null) {
			return false;
		}
		boolean changed = false;
		List<TimeKey> emptyKeys = new ArrayList<>();
		for (Map.Entry<TimeKey, Map<SourceKey, V>> bucketEntry : buckets.entrySet()) {
			Map<SourceKey, V> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				continue;
			}
			if (bucket.remove(sourceKey) != null) {
				changed = true;
			}
			if (bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
			}
		}
		for (TimeKey emptyKey : emptyKeys) {
			if (buckets.remove(emptyKey) != null) {
				changed = true;
			}
		}
		return changed;
	}

	private static <V> boolean hasExactConcurrentEntry(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		SourceKey sourceKey,
		TimeKey timeKey,
		V expectedValue
	) {
		boolean foundExpected = false;
		for (Map.Entry<TimeKey, Map<SourceKey, V>> bucketEntry : buckets.entrySet()) {
			Map<SourceKey, V> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty() || !bucket.containsKey(sourceKey)) {
				continue;
			}
			if (!Objects.equals(bucketEntry.getKey(), timeKey) || !Objects.equals(bucket.get(sourceKey), expectedValue) || foundExpected) {
				return false;
			}
			foundExpected = true;
		}
		return foundExpected;
	}

	/**
	 * 更晚时间粒度到来时，淘汰更早帧的结构真值。
	 * <p>
	 * 规则：
	 * 1. 新 SYNC：淘汰更早的 sync/toggle，并取消 pulse 武装与脉冲桶；
	 * 2. 新 PULSE：淘汰更早的 sync/toggle/pulse；
	 * 3. 新 TOGGLE：淘汰更早的 sync/toggle，但不打断仍在生效窗口中的 pulse。
	 * </p>
	 */
	private boolean pruneOlderFramesForIncoming(TimeKey incomingTimeKey, EffectiveMode incomingMode) {
		TimeKey normalizedTimeKey = incomingTimeKey == null ? TimeKey.of(0L, 0) : incomingTimeKey;
		boolean changed = removeConcurrentBucketsBefore(syncConcurrentBuckets, normalizedTimeKey);
		changed |= removeConcurrentBucketsBefore(toggleConcurrentBuckets, normalizedTimeKey);
		if (incomingMode == EffectiveMode.SYNC) {
			changed |= clearPulseTruth();
			return changed;
		}
		if (incomingMode == EffectiveMode.PULSE) {
			changed |= removeConcurrentBucketsBefore(pulseConcurrentBuckets, normalizedTimeKey);
		}
		return changed;
	}

	/**
	 * 取消当前 pulse 真值与下落窗口。
	 */
	private boolean clearPulseTruth() {
		boolean changed = !pulseConcurrentBuckets.isEmpty() || pulseUntilGameTime > 0L || pulseResetArmed;
		pulseConcurrentBuckets.clear();
		pulseUntilGameTime = 0L;
		pulseResetArmed = false;
		return changed;
	}

	/**
	 * 清除早于指定时间键的并发桶帧。
	 */
	private static <V> boolean removeConcurrentBucketsBefore(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		TimeKey cutoffTimeKey
	) {
		if (buckets.isEmpty() || cutoffTimeKey == null) {
			return false;
		}
		List<TimeKey> staleKeys = new ArrayList<>();
		for (TimeKey timeKey : buckets.keySet()) {
			if (timeKey == null || timeKey.compareTo(cutoffTimeKey) < 0) {
				staleKeys.add(timeKey);
				continue;
			}
			break;
		}
		boolean changed = false;
		for (TimeKey staleKey : staleKeys) {
			if (buckets.remove(staleKey) != null) {
				changed = true;
			}
		}
		return changed;
	}

	private boolean findToggleContribution(SourceKey sourceKey) {
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			ToggleConcurrentEntry entry = bucket == null ? null : bucket.get(sourceKey);
			if (entry != null) {
				return entry.contributes();
			}
		}
		return false;
	}

	/**
	 * 新仲裁帧开始前，快照当前仍有效的 toggle 来源贡献。
	 */
	private void snapshotToggleFrameStartContributors() {
		toggleFrameStartContributors.clear();
		toggleSourcesTouchedInCurrentFrame.clear();
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ToggleConcurrentEntry> entry : bucket.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null && entry.getValue().contributes()) {
					toggleFrameStartContributors.add(entry.getKey());
				}
			}
		}
	}

	/**
	 * 在 prune 之前解析同源 toggle 是否已有贡献。
	 * <p>
	 * 同帧若该来源尚未触达，则优先参考帧起始快照；若已触达，则改看当前桶状态，保证同帧多次 toggle 仍按奇偶翻转。
	 * </p>
	 */
	private boolean resolveToggleContributionBeforePrune(SourceKey sourceKey) {
		if (sourceKey == null) {
			return false;
		}
		if (toggleSourcesTouchedInCurrentFrame.contains(sourceKey)) {
			return findToggleContribution(sourceKey);
		}
		return toggleFrameStartContributors.contains(sourceKey) || findToggleContribution(sourceKey);
	}

	/**
	 * 记录当前仲裁帧内已经处理过 toggle 的来源。
	 */
	private void markToggleSourceTouchedInCurrentFrame(SourceKey sourceKey) {
		if (sourceKey != null) {
			toggleSourcesTouchedInCurrentFrame.add(sourceKey);
		}
	}

	/**
	 * 轻量版 L2：同 tick TOGGLE 按“基准态 + 奇偶”合并。
	 */
	private void applyToggleMerged() {
		if (!toggleMergeInitialized) {
			toggleMergeInitialized = true;
			// TOGGLE 语义以“当前外显 active”为翻转基准，而不是历史锁存位。
			toggleMergeBaseActive = active;
			toggleMergeParity = false;
		}
		toggleMergeParity = !toggleMergeParity;
		toggleState = toggleMergeParity ? !toggleMergeBaseActive : toggleMergeBaseActive;
		applyDerivedStateFromTruth();
	}

	/**
	 * 轻量版 L2：同 tick PULSE 只在到期时间被延长时重新调度。
	 */
	private void applyPulseMerged() {
		int pulseTicks = Math.max(1, getPulseDurationTicks());
		pulseEpoch++;
		pulseResetArmed = true;
		if (level != null) {
			long nextExpireTime = level.getGameTime() + pulseTicks;
			if (nextExpireTime > pulseUntilGameTime) {
				pulseUntilGameTime = nextExpireTime;
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
		applyResolvedState(resolvedPower > 0, resolvedPower);
	}

	/**
	 * 计算结构真值对应的输出功率。
	 */
	private int resolveDerivedOutputPowerFromTruth() {
		return switch (resolveAuthorityEffectiveMode()) {
			case SYNC -> normalizeSignalStrength(syncSignalMaxStrength);
			case PULSE -> getDefaultActiveOutputPower();
			case TOGGLE -> toggleState ? getDefaultActiveOutputPower() : 0;
			case NONE -> 0;
		};
	}

	/**
	 * 判断脉冲结构真值是否处于生效窗口。
	 */
	private boolean isPulseTruthActive() {
		if (pulseUntilGameTime <= 0L) {
			return false;
		}
		if (level == null) {
			return true;
		}
		return level.getGameTime() < pulseUntilGameTime;
	}

	/**
	 * 同时间粒度统一结果态写回（激活态 + 输出功率）。
	 * <p>
	 * 若同时间粒度内结算结果不变，则跳过重复写回；当仅输出功率变化时，仍会刷新目标方块状态。
	 * </p>
	 */
	private void applyResolvedState(boolean resolvedActive, int resolvedPower) {
		if (level == null || level.isClientSide) {
			return;
		}
		int normalizedPower = normalizeSignalStrength(resolvedPower);
		boolean powerChanged = setResolvedOutputPower(normalizedPower);
		beginArbitrationFrame(authorityTimeKey);
		if (tickResolvedInitialized && tickResolvedState == resolvedActive && tickResolvedPower == normalizedPower) {
			return;
		}
		tickResolvedInitialized = true;
		tickResolvedState = resolvedActive;
		tickResolvedPower = normalizedPower;
		if (this.active != resolvedActive) {
			setActive(resolvedActive);
			return;
		}
		if (powerChanged) {
			// 激活态未变但功率变化时，仍需刷新方块输出与客户端外显。
			onActiveChanged(active);
			if (shouldSyncClientOnPowerChanged()) {
				syncToClient();
			}
		}
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
		if (getEffectiveMode() != EffectiveMode.SYNC) {
			return true;
		}
		TimeKey normalizedTimeKey = authorityTimeKey == null ? TimeKey.of(0L, 0) : authorityTimeKey;
		int normalizedPower = normalizeSignalStrength(resolvedOutputPower);
		if (
			fanoutResolvedInitialized
				&& fanoutResolvedState == resolvedActive
				&& fanoutResolvedPower == normalizedPower
				&& fanoutResolvedTimeKey.equals(normalizedTimeKey)
		) {
			NeighborFanoutUtil.recordFanoutDedupHit();
			return false;
		}
		fanoutResolvedInitialized = true;
		fanoutResolvedState = resolvedActive;
		fanoutResolvedPower = normalizedPower;
		fanoutResolvedTimeKey = normalizedTimeKey;
		return true;
	}

	/**
	 * 返回当前扇出去重时间键的 tick 分量。
	 * <p>
	 * 供子类传递给工具层做统一时间粒度去重，避免写死同 tick 判定。
	 * </p>
	 */
	protected final long getFanoutTimeTick() {
		return authorityTimeKey == null ? 0L : Math.max(0L, authorityTimeKey.tick());
	}

	/**
	 * 返回当前扇出去重时间键的 slot 分量。
	 * <p>
	 * 与 tick 共同构成时间粒度键，保持与仲裁模型一致。
	 * </p>
	 */
	protected final int getFanoutTimeSlot() {
		return authorityTimeKey == null ? 0 : Math.max(0, authorityTimeKey.slot());
	}

	/**
	 * 同 tick 冲突仲裁。
	 * <p>
	 * 仅在同一个 gameTime 内按优先级裁决：SYNC > PULSE > TOGGLE。
	 * </p>
	 */
	private boolean acceptByPriority(TimeKey eventTimeKey, int incomingPriority, EffectiveMode incomingMode, long incomingSeq) {
		TimeKey normalizedTimeKey = eventTimeKey == null ? TimeKey.of(0L, 0) : eventTimeKey;
		long normalizedSeq = Math.max(0L, incomingSeq);
		int timeCompare = normalizedTimeKey.compareTo(authorityTimeKey);
		if (timeCompare < 0) {
			return false;
		}

		beginArbitrationFrame(normalizedTimeKey);
		if (timeCompare == 0 && incomingPriority < getPriorityOfEffectiveMode(resolveAuthorityEffectiveMode())) {
			return false;
		}

		if (timeCompare > 0 || incomingPriority > arbitrationPriority || incomingMode != authorityMode) {
			authorityMode = incomingMode == null ? EffectiveMode.NONE : incomingMode;
			authorityTimeKey = normalizedTimeKey;
			authoritySeq = normalizedSeq;
			arbitrationPriority = incomingPriority;
			// 更高优先级覆盖时，清空同 tick 低优先级的合并缓存。
			tickResolvedInitialized = false;
			toggleMergeInitialized = false;
			toggleMergeParity = false;
			return true;
		}
		if (normalizedSeq > authoritySeq) {
			authoritySeq = normalizedSeq;
		}
		arbitrationPriority = Math.max(arbitrationPriority, incomingPriority);
		return true;
	}

	/**
	 * 进入同 tick 仲裁帧，tick 切换时重置轻量合并缓存。
	 */
	private void beginArbitrationFrame(TimeKey timeKey) {
		if (timeKey == null || timeKey.equals(arbitrationTimeKey)) {
			return;
		}
		snapshotToggleFrameStartContributors();
		arbitrationTimeKey = timeKey;
		arbitrationPriority = Integer.MIN_VALUE;
		tickResolvedInitialized = false;
		tickResolvedPower = 0;
		toggleMergeInitialized = false;
		toggleMergeParity = false;
	}

	/**
	 * 维护同步触发源强度缓存，并重算 max 聚合结果。
	 */
	private boolean updateSyncSignalStrength(long sourceSerial, int signalStrength) {
		// 兼容现有测试入口：无来源类型时默认映射为 triggerSource。
		SourceKey sourceKey = new SourceKey(LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		boolean bucketChanged;
		if (sourceSerial <= 0L) {
			bucketChanged = !syncConcurrentBuckets.isEmpty();
			syncConcurrentBuckets.clear();
		} else if (normalizeSignalStrength(signalStrength) <= 0) {
			bucketChanged = removeSyncConcurrentSource(sourceKey);
		} else {
			bucketChanged = upsertSyncConcurrentSource(sourceKey, authorityTimeKey, signalStrength, authoritySeq);
		}
		recomputeSyncTruthFromConcurrentBuckets();
		return bucketChanged;
	}

	/**
	 * 重算当前同步源 max 强度。
	 */
	private int recalculateSyncMaxStrengthAndSources() {
		int maxStrength = 0;
		syncSignalMaxSources.clear();
		for (Map.Entry<Long, Integer> entry : syncSignalStrengthBySource.entrySet()) {
			Long sourceSerial = entry.getKey();
			Integer strength = entry.getValue();
			if (sourceSerial == null || sourceSerial <= 0L || strength == null || strength <= 0) {
				continue;
			}
			if (strength > maxStrength) {
				maxStrength = strength;
				syncSignalMaxSources.clear();
				syncSignalMaxSources.add(sourceSerial);
				continue;
			}
			if (strength == maxStrength) {
				syncSignalMaxSources.add(sourceSerial);
			}
		}
		return maxStrength;
	}

	/**
	 * 从同步并发桶重建 SYNC 真值（来源表 + max + maxSources）。
	 */
	private void recomputeSyncTruthFromConcurrentBuckets() {
		syncSignalStrengthBySource.clear();
		for (Map<SourceKey, SyncConcurrentEntry> bucket : syncConcurrentBuckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, SyncConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				SyncConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || sourceKey.sourceSerial() <= 0L || concurrentEntry == null) {
					continue;
				}
				int strength = normalizeSignalStrength(concurrentEntry.strength());
				if (strength <= 0) {
					continue;
				}
				syncSignalStrengthBySource.merge(sourceKey.sourceSerial(), strength, Math::max);
			}
		}
		syncSignalMaxStrength = recalculateSyncMaxStrengthAndSources();
	}

	/**
	 * 从脉冲并发桶重建 PULSE 真值（有效下落窗口）。
	 */
	private boolean recomputePulseTruthFromConcurrentBuckets() {
		long now = level == null ? 0L : level.getGameTime();
		List<TimeKey> emptyKeys = new ArrayList<>();
		long maxUntilTick = 0L;
		boolean changed = false;
		for (Map.Entry<TimeKey, Map<SourceKey, PulseConcurrentEntry>> bucketEntry : pulseConcurrentBuckets.entrySet()) {
			Map<SourceKey, PulseConcurrentEntry> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				changed = true;
				continue;
			}
			if (bucket.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().untilGameTick() <= now)) {
				changed = true;
			}
			if (bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				continue;
			}
			for (PulseConcurrentEntry pulseEntry : bucket.values()) {
				if (pulseEntry == null) {
					continue;
				}
				maxUntilTick = Math.max(maxUntilTick, pulseEntry.untilGameTick());
			}
		}
		for (TimeKey emptyKey : emptyKeys) {
			if (pulseConcurrentBuckets.remove(emptyKey) != null) {
				changed = true;
			}
		}
		if (pulseUntilGameTime != maxUntilTick) {
			changed = true;
		}
		pulseUntilGameTime = maxUntilTick;
		boolean nextPulseResetArmed = maxUntilTick > now;
		if (pulseResetArmed != nextPulseResetArmed) {
			changed = true;
		}
		pulseResetArmed = nextPulseResetArmed;
		if (pulseResetArmed && level != null) {
			long remaining = Math.max(1L, maxUntilTick - now);
			schedulePulseReset((int) remaining);
		}
		return changed;
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
		int activeContributors = 0;
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (ToggleConcurrentEntry entry : bucket.values()) {
				if (entry != null && entry.contributes()) {
					activeContributors++;
				}
			}
		}
		toggleConcurrentCount = activeContributors;
		boolean baseActive = syncSignalMaxStrength > 0 || isPulseTruthActive();
		boolean oddParity = (toggleConcurrentCount & 1) == 1;
		toggleState = oddParity ? !baseActive : baseActive;
	}

	/**
	 * 按并发桶候选重算 authority，确保 REMOVE 后可回退到仍有效的下层真值。
	 */
	private void recomputeAuthorityFromConcurrentBuckets(TimeKey fallbackTimeKey, long fallbackSeq) {
		Candidate syncCandidate = resolveSyncCandidate();
		Candidate pulseCandidate = resolvePulseCandidate();
		Candidate toggleCandidate = resolveToggleCandidate();
		Candidate winner = pickWinner(syncCandidate, pulseCandidate, toggleCandidate);
		if (winner == null) {
			authorityMode = EffectiveMode.NONE;
			authorityTimeKey = fallbackTimeKey == null ? TimeKey.of(0L, 0) : fallbackTimeKey;
			authoritySeq = Math.max(0L, fallbackSeq);
			return;
		}
		authorityMode = winner.mode();
		authorityTimeKey = winner.timeKey();
		authoritySeq = winner.seq();
	}

	private Candidate resolveSyncCandidate() {
		if (syncSignalMaxStrength <= 0 || syncConcurrentBuckets.isEmpty()) {
			return null;
		}
		TimeKey timeKey = syncConcurrentBuckets.lastKey();
		Map<SourceKey, SyncConcurrentEntry> bucket = syncConcurrentBuckets.get(timeKey);
		long seq = 0L;
		if (bucket != null) {
			for (SyncConcurrentEntry entry : bucket.values()) {
				if (entry != null) {
					seq = Math.max(seq, entry.seq());
				}
			}
		}
		return new Candidate(EffectiveMode.SYNC, timeKey, seq, PRIORITY_SYNC);
	}

	private Candidate resolvePulseCandidate() {
		if (!isPulseTruthActive() || pulseConcurrentBuckets.isEmpty()) {
			return null;
		}
		TimeKey timeKey = pulseConcurrentBuckets.lastKey();
		Map<SourceKey, PulseConcurrentEntry> bucket = pulseConcurrentBuckets.get(timeKey);
		long seq = 0L;
		if (bucket != null) {
			for (PulseConcurrentEntry entry : bucket.values()) {
				if (entry != null) {
					seq = Math.max(seq, entry.seq());
				}
			}
		}
		return new Candidate(EffectiveMode.PULSE, timeKey, seq, PRIORITY_PULSE);
	}

	private Candidate resolveToggleCandidate() {
		if (toggleConcurrentBuckets.isEmpty() || toggleConcurrentCount <= 0) {
			return null;
		}
		TimeKey timeKey = toggleConcurrentBuckets.lastKey();
		Map<SourceKey, ToggleConcurrentEntry> bucket = toggleConcurrentBuckets.get(timeKey);
		long seq = 0L;
		if (bucket != null) {
			for (ToggleConcurrentEntry entry : bucket.values()) {
				if (entry != null) {
					seq = Math.max(seq, entry.seq());
				}
			}
		}
		return new Candidate(EffectiveMode.TOGGLE, timeKey, seq, PRIORITY_TOGGLE);
	}

	private static Candidate pickWinner(Candidate syncCandidate, Candidate pulseCandidate, Candidate toggleCandidate) {
		Candidate nonToggleWinner = pickMoreRecentCandidate(syncCandidate, pulseCandidate);
		if (nonToggleWinner == null) {
			return toggleCandidate;
		}
		// pulse 进入下落窗口后，later toggle 只作为后续候选，不能打断当前 pulse。
		if (nonToggleWinner.mode() == EffectiveMode.PULSE) {
			return nonToggleWinner;
		}
		return pickMoreRecentCandidate(nonToggleWinner, toggleCandidate);
	}

	private static Candidate pickMoreRecentCandidate(Candidate left, Candidate right) {
		if (left == null) {
			return right;
		}
		if (right == null) {
			return left;
		}
		int timeCompare = right.timeKey().compareTo(left.timeKey());
		if (timeCompare > 0) {
			return right;
		}
		if (timeCompare < 0) {
			return left;
		}
		if (right.priority() > left.priority()) {
			return right;
		}
		if (right.priority() < left.priority()) {
			return left;
		}
		return right.seq() > left.seq() ? right : left;
	}

	private record Candidate(EffectiveMode mode, TimeKey timeKey, long seq, int priority) {
		private Candidate {
			timeKey = timeKey == null ? TimeKey.of(0L, 0) : timeKey;
			seq = Math.max(0L, seq);
		}
	}

	/**
	 * 按 authority 与当前结构真值计算运行态生效模式。
	 */
	private EffectiveMode resolveAuthorityEffectiveMode() {
		return switch (authorityMode) {
			case SYNC -> syncSignalMaxStrength > 0 ? EffectiveMode.SYNC : EffectiveMode.NONE;
			case PULSE -> isPulseTruthActive() ? EffectiveMode.PULSE : EffectiveMode.NONE;
			case TOGGLE -> (toggleConcurrentCount > 0 || toggleState) ? EffectiveMode.TOGGLE : EffectiveMode.NONE;
			case NONE -> EffectiveMode.NONE;
		};
	}

	/**
	 * 结构真值变化后，校正 authority 的有效性。
	 */
	private void normalizeAuthorityByTruth() {
		if (resolveAuthorityEffectiveMode() != EffectiveMode.NONE) {
			return;
		}
		if (hasAnyConcurrentBuckets()) {
			recomputeAuthorityFromConcurrentBuckets(authorityTimeKey, authoritySeq);
			if (resolveAuthorityEffectiveMode() != EffectiveMode.NONE) {
				return;
			}
		}
		if (authorityMode != EffectiveMode.NONE) {
			authorityMode = EffectiveMode.NONE;
			authoritySeq = 0L;
		}
	}

	private boolean hasAnyConcurrentBuckets() {
		return !syncConcurrentBuckets.isEmpty() || !pulseConcurrentBuckets.isEmpty() || !toggleConcurrentBuckets.isEmpty();
	}

	/**
	 * 将配置触发模式映射为同粒度仲裁优先级。
	 */
	private static int getPriorityOfEffectiveMode(EffectiveMode mode) {
		if (mode == null) {
			return Integer.MIN_VALUE;
		}
		return switch (mode) {
			case SYNC -> PRIORITY_SYNC;
			case PULSE -> PRIORITY_PULSE;
			case TOGGLE -> PRIORITY_TOGGLE;
			case NONE -> Integer.MIN_VALUE;
		};
	}

	/**
	 * 兜底归一化事件元数据，避免空入参污染仲裁。
	 */
	private EventMeta normalizeEventMeta(EventMeta eventMeta) {
		return eventMeta == null ? EventMeta.now(level) : eventMeta;
	}

	/**
	 * 归一化输入强度，避免异常值污染聚合。
	 */
	private static int normalizeSignalStrength(int signalStrength) {
		return SignalStrengths.clamp(signalStrength);
	}

	/**
	 * 更新解析输出功率。
	 *
	 * @return 是否发生变化
	 */
	private boolean setResolvedOutputPower(int outputPower) {
		int normalizedPower = normalizeSignalStrength(outputPower);
		if (resolvedOutputPower == normalizedPower) {
			return false;
		}
		resolvedOutputPower = normalizedPower;
		setChanged();
		return true;
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
		pulseUntilGameTime = Math.max(0L, tag.getLong(KEY_PULSE_UNTIL_GAME_TIME));
		pulseEpoch = Math.max(0L, tag.getLong(KEY_PULSE_EPOCH));
		toggleState = tag.getBoolean(KEY_TOGGLE_STATE);
		toggleConcurrentCount = Math.max(0, tag.getInt(KEY_TOGGLE_CONCURRENT_COUNT));
		loadSyncSourceStrengths(tag);
		syncSignalMaxStrength = recalculateSyncMaxStrengthAndSources();
		if (syncSignalMaxStrength <= 0 && tag.contains(KEY_SYNC_MAX_SOURCES, Tag.TAG_LONG_ARRAY)) {
			syncSignalMaxSources.clear();
			for (long sourceSerial : tag.getLongArray(KEY_SYNC_MAX_SOURCES)) {
				if (sourceSerial > 0L) {
					syncSignalMaxSources.add(sourceSerial);
				}
			}
		}
		if (tag.contains(KEY_CONFIGURED_MODE)) {
			configuredMode = ActivationMode.fromName(tag.getString(KEY_CONFIGURED_MODE));
		}
		if (tag.contains(KEY_AUTHORITY_MODE, Tag.TAG_STRING)) {
			authorityMode = parseEffectiveMode(tag.getString(KEY_AUTHORITY_MODE));
		} else {
			authorityMode = deriveLegacyAuthorityModeFromTruth();
		}
		authorityTimeKey = TimeKey.of(Math.max(0L, tag.getLong(KEY_AUTHORITY_TICK)), Math.max(0, tag.getInt(KEY_AUTHORITY_SLOT)));
		authoritySeq = Math.max(0L, tag.getLong(KEY_AUTHORITY_SEQ));
		boolean hasConcurrentTruth = loadConcurrentBuckets(tag);
		if (hasConcurrentTruth) {
			recomputeSyncTruthFromConcurrentBuckets();
			recomputePulseTruthFromConcurrentBuckets();
			recomputeToggleTruthFromConcurrentBuckets();
			recomputeAuthorityFromConcurrentBuckets(authorityTimeKey, authoritySeq);
		}
		// 脉冲窗口跨重启后若已过期，读档即回收。
		if (level != null && pulseUntilGameTime > 0L && level.getGameTime() >= pulseUntilGameTime) {
			pulseUntilGameTime = 0L;
		}
		pulseResetArmed = pulseUntilGameTime > 0L;
		rebuildDerivedCacheFromTruth();

		// 运行时缓存不持久化，读档后重置。
		arbitrationTimeKey = TimeKey.minValue();
		arbitrationPriority = Integer.MIN_VALUE;
		tickResolvedInitialized = false;
		tickResolvedPower = 0;
		fanoutResolvedInitialized = false;
		fanoutResolvedTimeKey = TimeKey.minValue();
		fanoutResolvedPower = 0;
		toggleMergeInitialized = false;
		toggleMergeParity = false;
		toggleFrameStartContributors.clear();
		toggleSourcesTouchedInCurrentFrame.clear();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		// 派生缓存：保留写入便于观测，但不再作为主真值来源。
		if (active) {
			tag.putBoolean(KEY_ACTIVE, true);
		}
		if (resolvedOutputPower > 0) {
			tag.putInt(KEY_RESOLVED_OUTPUT_POWER, normalizeSignalStrength(resolvedOutputPower));
		}
		// 结构真值持久化：SYNC 来源表 + PULSE 窗口 + TOGGLE 锁存态 + maxSources。
		if (pulseUntilGameTime > 0L) {
			tag.putLong(KEY_PULSE_UNTIL_GAME_TIME, pulseUntilGameTime);
		}
		if (pulseEpoch > 0L) {
			tag.putLong(KEY_PULSE_EPOCH, pulseEpoch);
		}
		if (toggleState) {
			tag.putBoolean(KEY_TOGGLE_STATE, true);
		}
		writeSyncSourceStrengths(tag);
		if (!syncSignalMaxSources.isEmpty()) {
			long[] serialArray = new long[syncSignalMaxSources.size()];
			int index = 0;
			for (Long sourceSerial : syncSignalMaxSources) {
				serialArray[index++] = sourceSerial;
			}
			tag.putLongArray(KEY_SYNC_MAX_SOURCES, serialArray);
		}
		tag.putString(KEY_CONFIGURED_MODE, configuredMode.name());
		tag.putString(KEY_AUTHORITY_MODE, authorityMode.name());
		tag.putLong(KEY_AUTHORITY_TICK, Math.max(0L, authorityTimeKey.tick()));
		tag.putInt(KEY_AUTHORITY_SLOT, Math.max(0, authorityTimeKey.slot()));
		tag.putLong(KEY_AUTHORITY_SEQ, Math.max(0L, authoritySeq));
		tag.putInt(KEY_TOGGLE_CONCURRENT_COUNT, Math.max(0, toggleConcurrentCount));
		writeConcurrentBuckets(tag);
	}

	/**
	 * 读档时按结构真值重建派生缓存（active/output）。
	 */
	private void rebuildDerivedCacheFromTruth() {
		normalizeAuthorityByTruth();
		resolvedOutputPower = normalizeSignalStrength(resolveDerivedOutputPowerFromTruth());
		active = resolvedOutputPower > 0;
	}

	private static EffectiveMode parseEffectiveMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return EffectiveMode.NONE;
		}
		for (EffectiveMode mode : EffectiveMode.values()) {
			if (mode.name().equalsIgnoreCase(raw.trim())) {
				return mode;
			}
		}
		return EffectiveMode.NONE;
	}

	private EffectiveMode deriveLegacyAuthorityModeFromTruth() {
		if (syncSignalMaxStrength > 0) {
			return EffectiveMode.SYNC;
		}
		if (isPulseTruthActive()) {
			return EffectiveMode.PULSE;
		}
		if (toggleState) {
			return EffectiveMode.TOGGLE;
		}
		return EffectiveMode.NONE;
	}

	/**
	 * 序列化 SYNC 来源强度表（sourceSerial -> strength）。
	 */
	private void writeSyncSourceStrengths(CompoundTag tag) {
		if (syncSignalStrengthBySource.isEmpty()) {
			return;
		}
		ListTag sourceList = new ListTag();
		syncSignalStrengthBySource
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				long sourceSerial = entry.getKey() == null ? 0L : entry.getKey();
				int strength = entry.getValue() == null ? 0 : entry.getValue();
				if (sourceSerial <= 0L || strength <= 0) {
					return;
				}
				CompoundTag sourceTag = new CompoundTag();
				sourceTag.putLong(KEY_SYNC_SOURCE_SERIAL, sourceSerial);
				sourceTag.putInt(KEY_SYNC_SOURCE_STRENGTH, normalizeSignalStrength(strength));
				sourceList.add(sourceTag);
			});
		if (!sourceList.isEmpty()) {
			tag.put(KEY_SYNC_SOURCE_STRENGTHS, sourceList);
		}
	}

	/**
	 * 反序列化 SYNC 来源强度表。
	 */
	private void loadSyncSourceStrengths(CompoundTag tag) {
		syncSignalStrengthBySource.clear();
		if (!tag.contains(KEY_SYNC_SOURCE_STRENGTHS, Tag.TAG_LIST)) {
			return;
		}
		ListTag sourceList = tag.getList(KEY_SYNC_SOURCE_STRENGTHS, Tag.TAG_COMPOUND);
		for (int index = 0; index < sourceList.size(); index++) {
			CompoundTag sourceTag = sourceList.getCompound(index);
			long sourceSerial = sourceTag.getLong(KEY_SYNC_SOURCE_SERIAL);
			int strength = normalizeSignalStrength(sourceTag.getInt(KEY_SYNC_SOURCE_STRENGTH));
			if (sourceSerial <= 0L || strength <= 0) {
				continue;
			}
			syncSignalStrengthBySource.put(sourceSerial, strength);
		}
	}

	private boolean loadConcurrentBuckets(CompoundTag tag) {
		syncConcurrentBuckets.clear();
		pulseConcurrentBuckets.clear();
		toggleConcurrentBuckets.clear();
		boolean loaded = false;
		loaded |= loadSyncConcurrentEntries(tag.getList(KEY_SYNC_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND));
		loaded |= loadPulseConcurrentEntries(tag.getList(KEY_PULSE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND));
		loaded |= loadToggleConcurrentEntries(tag.getList(KEY_TOGGLE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND));
		return loaded;
	}

	private boolean loadSyncConcurrentEntries(ListTag listTag) {
		boolean loaded = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			Optional<SourceKey> sourceKey = parseConcurrentSourceKey(entryTag);
			if (sourceKey.isEmpty()) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			int strength = normalizeSignalStrength(entryTag.getInt(KEY_CONCURRENT_STRENGTH));
			if (strength <= 0) {
				continue;
			}
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			syncConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>()).put(sourceKey.get(), new SyncConcurrentEntry(strength, seq));
			loaded = true;
		}
		return loaded;
	}

	private boolean loadPulseConcurrentEntries(ListTag listTag) {
		boolean loaded = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			Optional<SourceKey> sourceKey = parseConcurrentSourceKey(entryTag);
			if (sourceKey.isEmpty()) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			long untilTick = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_UNTIL_TICK));
			if (untilTick <= 0L) {
				continue;
			}
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			pulseConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>()).put(sourceKey.get(), new PulseConcurrentEntry(untilTick, seq));
			loaded = true;
		}
		return loaded;
	}

	private boolean loadToggleConcurrentEntries(ListTag listTag) {
		boolean loaded = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			Optional<SourceKey> sourceKey = parseConcurrentSourceKey(entryTag);
			if (sourceKey.isEmpty()) {
				continue;
			}
			if (!entryTag.getBoolean(KEY_CONCURRENT_CONTRIBUTES)) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			toggleConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>()).put(sourceKey.get(), new ToggleConcurrentEntry(true, seq));
			loaded = true;
		}
		return loaded;
	}

	private Optional<SourceKey> parseConcurrentSourceKey(CompoundTag entryTag) {
		long sourceSerial = entryTag.getLong(KEY_CONCURRENT_SOURCE_SERIAL);
		if (sourceSerial <= 0L) {
			return Optional.empty();
		}
		String rawType = entryTag.getString(KEY_CONCURRENT_SOURCE_TYPE);
		Optional<LinkNodeType> sourceType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (sourceType.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new SourceKey(sourceType.get(), sourceSerial));
	}

	private void writeConcurrentBuckets(CompoundTag tag) {
		ListTag syncList = new ListTag();
		appendSyncConcurrentEntries(syncList);
		if (!syncList.isEmpty()) {
			tag.put(KEY_SYNC_CONCURRENT_ENTRIES, syncList);
		}

		ListTag pulseList = new ListTag();
		appendPulseConcurrentEntries(pulseList);
		if (!pulseList.isEmpty()) {
			tag.put(KEY_PULSE_CONCURRENT_ENTRIES, pulseList);
		}

		ListTag toggleList = new ListTag();
		appendToggleConcurrentEntries(toggleList);
		if (!toggleList.isEmpty()) {
			tag.put(KEY_TOGGLE_CONCURRENT_ENTRIES, toggleList);
		}
	}

	private void appendSyncConcurrentEntries(ListTag targetList) {
		for (Map.Entry<TimeKey, Map<SourceKey, SyncConcurrentEntry>> bucketEntry : syncConcurrentBuckets.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, SyncConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, SyncConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				SyncConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || concurrentEntry.strength() <= 0) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putInt(KEY_CONCURRENT_STRENGTH, normalizeSignalStrength(concurrentEntry.strength()));
				targetList.add(entryTag);
			}
		}
	}

	private void appendPulseConcurrentEntries(ListTag targetList) {
		for (Map.Entry<TimeKey, Map<SourceKey, PulseConcurrentEntry>> bucketEntry : pulseConcurrentBuckets.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, PulseConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, PulseConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				PulseConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || concurrentEntry.untilGameTick() <= 0L) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putLong(KEY_CONCURRENT_UNTIL_TICK, Math.max(0L, concurrentEntry.untilGameTick()));
				targetList.add(entryTag);
			}
		}
	}

	private void appendToggleConcurrentEntries(ListTag targetList) {
		for (Map.Entry<TimeKey, Map<SourceKey, ToggleConcurrentEntry>> bucketEntry : toggleConcurrentBuckets.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, ToggleConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ToggleConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				ToggleConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || !concurrentEntry.contributes()) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putBoolean(KEY_CONCURRENT_CONTRIBUTES, true);
				targetList.add(entryTag);
			}
		}
	}

	private static void writeConcurrentSourceKey(CompoundTag entryTag, SourceKey sourceKey, TimeKey timeKey, long seq) {
		entryTag.putString(KEY_CONCURRENT_SOURCE_TYPE, LinkNodeSemantics.toSemanticName(sourceKey.sourceType()));
		entryTag.putLong(KEY_CONCURRENT_SOURCE_SERIAL, sourceKey.sourceSerial());
		entryTag.putLong(KEY_CONCURRENT_TICK, Math.max(0L, timeKey.tick()));
		entryTag.putInt(KEY_CONCURRENT_SLOT, Math.max(0, timeKey.slot()));
		entryTag.putLong(KEY_CONCURRENT_SEQ, Math.max(0L, seq));
	}
}
