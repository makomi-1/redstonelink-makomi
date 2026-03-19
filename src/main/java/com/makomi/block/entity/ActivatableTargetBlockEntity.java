package com.makomi.block.entity;

import com.makomi.config.RedstoneLinkConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
	private static final String KEY_ACTIVATION_MODE = "ActivationMode";
	private static final String KEY_PULSE_EXPIRE_GAME_TIME = "PulseExpireGameTime";
	private static final String KEY_RESOLVED_OUTPUT_POWER = "ResolvedOutputPower";

	private static final int PRIORITY_TOGGLE = 1;
	private static final int PRIORITY_PULSE = 2;
	private static final int PRIORITY_SYNC = 3;

	private boolean active;
	private ActivationMode activationMode = ActivationMode.TOGGLE;
	private long pulseExpireGameTime;
	private int resolvedOutputPower;
	// 脉冲回落任务是否仍有效；用于让 SYNC 能失效已排队的历史回落 tick。
	private boolean pulseResetArmed;

	// 同 tick 仲裁帧：用于优先级覆盖与轻量合并。
	private long arbitrationGameTime = Long.MIN_VALUE;
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

	public final ActivationMode getActivationMode() {
		return activationMode;
	}

	public final void setActivationMode(ActivationMode activationMode) {
		if (activationMode == null || this.activationMode == activationMode) {
			return;
		}
		this.activationMode = activationMode;
		syncToClient();
	}

	public final void triggerByPlayer() {
		applyActivation(0L, activationMode);
	}

	public final void triggerBySource(long sourceSerial) {
		applyActivation(sourceSerial, activationMode);
	}

	public final void triggerBySource(long sourceSerial, ActivationMode triggerMode) {
		applyActivation(sourceSerial, triggerMode == null ? activationMode : triggerMode);
	}

	/**
	 * 按源端当前输入电平同步目标状态。
	 * <p>
	 * 兼容布尔输入：ON 视为强度 15，OFF 视为强度 0。
	 * </p>
	 */
	public final void syncBySource(long sourceSerial, boolean signalOn) {
		syncBySource(sourceSerial, signalOn ? 15 : 0);
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
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}
		if (!acceptByPriority(PRIORITY_SYNC)) {
			return;
		}

		int normalizedStrength = normalizeSignalStrength(signalStrength);
		updateSyncSignalStrength(sourceSerial, normalizedStrength);
		// 同步语义不应受历史脉冲回落影响。
		pulseExpireGameTime = 0L;
		pulseResetArmed = false;
		applyResolvedState(syncSignalMaxStrength > 0, syncSignalMaxStrength);
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
			pulseExpireGameTime = 0L;
			return;
		}

		long now = level.getGameTime();
		if (now < pulseExpireGameTime) {
			long remaining = pulseExpireGameTime - now;
			schedulePulseReset((int) Math.max(1L, remaining));
			return;
		}
		pulseResetArmed = false;
		pulseExpireGameTime = 0L;
		applyResolvedState(false, 0);
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
	private void applyActivation(long sourceSerial, ActivationMode mode) {
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}

		int priority = mode == ActivationMode.PULSE ? PRIORITY_PULSE : PRIORITY_TOGGLE;
		if (!acceptByPriority(priority)) {
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
			toggleMergeBaseActive = active;
			toggleMergeParity = false;
		}
		toggleMergeParity = !toggleMergeParity;
		boolean resolved = toggleMergeParity ? !toggleMergeBaseActive : toggleMergeBaseActive;
		applyResolvedState(resolved, resolved ? getDefaultActiveOutputPower() : 0);
	}

	/**
	 * 轻量版 L2：同 tick PULSE 只在到期时间被延长时重新调度。
	 */
	private void applyPulseMerged() {
		int pulseTicks = Math.max(1, getPulseDurationTicks());
		pulseResetArmed = true;
		if (level != null) {
			long nextExpireTime = level.getGameTime() + pulseTicks;
			if (nextExpireTime > pulseExpireGameTime) {
				pulseExpireGameTime = nextExpireTime;
				schedulePulseReset(pulseTicks);
			}
		} else {
			schedulePulseReset(pulseTicks);
		}
		applyResolvedState(true, getDefaultActiveOutputPower());
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
		beginArbitrationFrame();
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
	private boolean acceptByPriority(int incomingPriority) {
		beginArbitrationFrame();
		if (incomingPriority < arbitrationPriority) {
			return false;
		}
		if (incomingPriority > arbitrationPriority) {
			arbitrationPriority = incomingPriority;
			// 更高优先级覆盖时，清空同 tick 低优先级的合并缓存。
			tickResolvedInitialized = false;
			toggleMergeInitialized = false;
			toggleMergeParity = false;
		}
		return true;
	}

	/**
	 * 进入同 tick 仲裁帧，tick 切换时重置轻量合并缓存。
	 */
	private void beginArbitrationFrame() {
		if (level == null) {
			return;
		}
		long gameTime = level.getGameTime();
		if (gameTime == arbitrationGameTime) {
			return;
		}
		arbitrationGameTime = gameTime;
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
			syncSignalMaxStrength = normalizedStrength;
			return;
		}

		if (normalizedStrength <= 0) {
			syncSignalStrengthBySource.remove(sourceSerial);
		} else {
			syncSignalStrengthBySource.put(sourceSerial, normalizedStrength);
		}
		syncSignalMaxStrength = recalculateSyncMaxStrength();
	}

	/**
	 * 重算当前同步源 max 强度。
	 */
	private int recalculateSyncMaxStrength() {
		int maxStrength = 0;
		for (Integer strength : syncSignalStrengthBySource.values()) {
			if (strength != null && strength > maxStrength) {
				maxStrength = strength;
			}
		}
		return maxStrength;
	}

	/**
	 * 归一化输入强度，避免异常值污染聚合。
	 */
	private static int normalizeSignalStrength(int signalStrength) {
		return Math.max(0, Math.min(15, signalStrength));
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
		active = tag.getBoolean(KEY_ACTIVE);
		pulseExpireGameTime = tag.getLong(KEY_PULSE_EXPIRE_GAME_TIME);
		pulseResetArmed = pulseExpireGameTime > 0L;
		if (!active) {
			resolvedOutputPower = 0;
		} else if (tag.contains(KEY_RESOLVED_OUTPUT_POWER)) {
			resolvedOutputPower = normalizeSignalStrength(tag.getInt(KEY_RESOLVED_OUTPUT_POWER));
		} else {
			// 兼容旧存档：历史版本仅持久化 active，不持久化输出功率。
			resolvedOutputPower = getDefaultActiveOutputPower();
		}
		if (tag.contains(KEY_ACTIVATION_MODE)) {
			activationMode = ActivationMode.fromName(tag.getString(KEY_ACTIVATION_MODE));
		}

		// 运行时缓存不持久化，读档后重置。
		arbitrationGameTime = Long.MIN_VALUE;
		arbitrationPriority = Integer.MIN_VALUE;
		tickResolvedInitialized = false;
		tickResolvedPower = 0;
		toggleMergeInitialized = false;
		toggleMergeParity = false;
		syncSignalStrengthBySource.clear();
		syncSignalMaxStrength = 0;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (active) {
			tag.putBoolean(KEY_ACTIVE, true);
		}
		if (pulseExpireGameTime > 0L) {
			tag.putLong(KEY_PULSE_EXPIRE_GAME_TIME, pulseExpireGameTime);
		}
		if (resolvedOutputPower > 0) {
			tag.putInt(KEY_RESOLVED_OUTPUT_POWER, normalizeSignalStrength(resolvedOutputPower));
		}
		tag.putString(KEY_ACTIVATION_MODE, activationMode.name());
	}
}

