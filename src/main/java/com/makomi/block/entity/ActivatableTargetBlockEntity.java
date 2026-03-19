package com.makomi.block.entity;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	// TOGGLE 同 tick 合并：以 tick 内基准态 + 奇偶翻转计算结果。
	private boolean toggleMergeInitialized;
	private boolean toggleMergeBaseActive;
	private boolean toggleMergeParity;

	// SYNC 多源聚合缓存：sourceSerial -> signalStrength(0~15)，最终态采用 max 强度。
	private final Map<Long, Integer> syncSignalStrengthBySource = new HashMap<>();
	private int syncSignalMaxStrength;
	// 并列最大强度来源集合：用于稳定审计输出，不依赖事件到达顺序。
	private final Set<Long> syncSignalMaxSources = new TreeSet<>();

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
		applyActivation(sourceSerial, triggerMode == null ? configuredMode : triggerMode, normalizeEventMeta(eventMeta));
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
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}
		EventMeta normalizedMeta = normalizeEventMeta(eventMeta);
		if (!acceptByPriority(normalizedMeta.timeKey(), PRIORITY_SYNC, EffectiveMode.SYNC, normalizedMeta.seq())) {
			return;
		}

		int normalizedStrength = normalizeSignalStrength(signalStrength);
		updateSyncSignalStrength(sourceSerial, normalizedStrength);
		// 同步语义不应受历史脉冲回落影响。
		pulseUntilGameTime = 0L;
		pulseResetArmed = false;
		applyDerivedStateFromTruth();
	}

	public final void onPulseTick() {
		// tick 仅在脉冲触发路径中调度，到点后统一回落。
		if (level == null || level.isClientSide) {
			return;
		}
		// 已被更高语义（如 SYNC）失效的历史回落任务直接忽略。
		if (!pulseResetArmed) {
			return;
		}
		if (!active) {
			pulseResetArmed = false;
			pulseUntilGameTime = 0L;
			return;
		}

		long now = level.getGameTime();
		if (now < pulseUntilGameTime) {
			long remaining = pulseUntilGameTime - now;
			schedulePulseReset((int) Math.max(1L, remaining));
			return;
		}
		pulseResetArmed = false;
		pulseUntilGameTime = 0L;
		// 脉冲窗口结束时，若当前 authority 为 PULSE，则在当前时间键落回 NONE。
		if (authorityMode == EffectiveMode.PULSE) {
			authorityMode = EffectiveMode.NONE;
			authorityTimeKey = TimeKey.of(now, 0);
			authoritySeq = 0L;
		}
		applyDerivedStateFromTruth();
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
			case PULSE, TOGGLE -> getDefaultActiveOutputPower();
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
	 * 同 tick 统一结果态写回（激活态 + 输出功率）。
	 * <p>
	 * 若同 tick 内结算结果不变，则跳过重复写回；当仅输出功率变化时，仍会刷新目标方块状态。
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
			syncToClient();
		}
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
	private void updateSyncSignalStrength(long sourceSerial, int signalStrength) {
		int normalizedStrength = normalizeSignalStrength(signalStrength);
		if (sourceSerial <= 0L) {
			syncSignalStrengthBySource.clear();
			syncSignalMaxSources.clear();
			syncSignalMaxStrength = normalizedStrength;
			return;
		}

		if (normalizedStrength <= 0) {
			syncSignalStrengthBySource.remove(sourceSerial);
		} else {
			syncSignalStrengthBySource.put(sourceSerial, normalizedStrength);
		}
		syncSignalMaxStrength = recalculateSyncMaxStrengthAndSources();
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
	 * 按 authority 与当前结构真值计算运行态生效模式。
	 */
	private EffectiveMode resolveAuthorityEffectiveMode() {
		return switch (authorityMode) {
			case SYNC -> syncSignalMaxStrength > 0 ? EffectiveMode.SYNC : EffectiveMode.NONE;
			case PULSE -> isPulseTruthActive() ? EffectiveMode.PULSE : EffectiveMode.NONE;
			case TOGGLE -> toggleState ? EffectiveMode.TOGGLE : EffectiveMode.NONE;
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
		if (authorityMode != EffectiveMode.NONE) {
			authorityMode = EffectiveMode.NONE;
			authoritySeq = 0L;
		}
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
		toggleMergeInitialized = false;
		toggleMergeParity = false;
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
}

