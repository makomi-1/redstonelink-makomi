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

	private static final int PRIORITY_TOGGLE = 1;
	private static final int PRIORITY_PULSE = 2;
	private static final int PRIORITY_SYNC = 3;

	private boolean active;
	private ActivationMode activationMode = ActivationMode.TOGGLE;
	private long pulseExpireGameTime;
	// 脉冲回落任务是否仍有效；用于让 SYNC 能失效已排队的历史回落 tick。
	private boolean pulseResetArmed;

	// 同 tick 仲裁帧：用于优先级覆盖与轻量合并。
	private long arbitrationGameTime = Long.MIN_VALUE;
	private int arbitrationPriority = Integer.MIN_VALUE;
	private boolean tickResolvedInitialized;
	private boolean tickResolvedState;

	// TOGGLE 同 tick 合并：以 tick 内基准态 + 奇偶翻转计算结果。
	private boolean toggleMergeInitialized;
	private boolean toggleMergeBaseActive;
	private boolean toggleMergeParity;

	// SYNC 多源聚合缓存：sourceSerial -> signalOn，最终态采用 OR。
	private final Map<Long, Boolean> syncSignalBySource = new HashMap<>();
	private int syncSignalOnSourceCount;

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
	 * 该路径不执行 TOGGLE/PULSE 语义转换；多同步源按 OR 聚合，避免最后写入覆盖。
	 * </p>
	 */
	public final void syncBySource(long sourceSerial, boolean signalOn) {
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}
		if (!acceptByPriority(PRIORITY_SYNC)) {
			return;
		}

		updateSyncSignalState(sourceSerial, signalOn);
		// 同步语义不应受历史脉冲回落影响。
		pulseExpireGameTime = 0L;
		pulseResetArmed = false;
		applyResolvedActive(syncSignalOnSourceCount > 0);
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
		setActive(false);
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
		applyResolvedActive(resolved);
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
		applyResolvedActive(true);
	}

	/**
	 * 同 tick 统一结果态写回。
	 * <p>
	 * 若同 tick 内结算结果不变，则跳过重复写回。
	 * </p>
	 */
	private void applyResolvedActive(boolean resolvedActive) {
		if (level == null || level.isClientSide) {
			return;
		}
		beginArbitrationFrame();
		if (tickResolvedInitialized && tickResolvedState == resolvedActive) {
			return;
		}
		tickResolvedInitialized = true;
		tickResolvedState = resolvedActive;
		setActive(resolvedActive);
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
		toggleMergeInitialized = false;
		toggleMergeParity = false;
	}

	/**
	 * 维护同步触发源电平缓存，并增量维护 OR 聚合结果。
	 */
	private void updateSyncSignalState(long sourceSerial, boolean signalOn) {
		if (sourceSerial <= 0L) {
			syncSignalBySource.clear();
			syncSignalOnSourceCount = signalOn ? 1 : 0;
			return;
		}

		Boolean previous = syncSignalBySource.get(sourceSerial);
		if (signalOn) {
			if (!Boolean.TRUE.equals(previous)) {
				syncSignalOnSourceCount++;
			}
			syncSignalBySource.put(sourceSerial, true);
			return;
		}

		if (Boolean.TRUE.equals(previous) && syncSignalOnSourceCount > 0) {
			syncSignalOnSourceCount--;
		}
		syncSignalBySource.remove(sourceSerial);
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
		if (tag.contains(KEY_ACTIVATION_MODE)) {
			activationMode = ActivationMode.fromName(tag.getString(KEY_ACTIVATION_MODE));
		}

		// 运行时缓存不持久化，读档后重置。
		arbitrationGameTime = Long.MIN_VALUE;
		arbitrationPriority = Integer.MIN_VALUE;
		tickResolvedInitialized = false;
		toggleMergeInitialized = false;
		toggleMergeParity = false;
		syncSignalBySource.clear();
		syncSignalOnSourceCount = 0;
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
		tag.putString(KEY_ACTIVATION_MODE, activationMode.name());
	}
}

