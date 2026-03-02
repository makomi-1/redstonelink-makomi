package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class ActivatableTargetBlockEntity extends PairableNodeBlockEntity {
	private static final String KEY_ACTIVE = "Active";
	private static final String KEY_ACTIVATION_MODE = "ActivationMode";

	private boolean active;
	private ActivationMode activationMode = ActivationMode.TOGGLE;

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

	public final void onPulseTick() {
		// tick 仅在脉冲触发路径中调度，到点后统一回落。
		if (active) {
			setActive(false);
		}
	}

	protected boolean canBeTriggeredBy(long sourceSerial) {
		return true;
	}

	protected int getPulseDurationTicks() {
		return 4;
	}

	protected abstract void onActiveChanged(boolean active);

	protected abstract void schedulePulseReset(int pulseTicks);

	private void applyActivation(long sourceSerial, ActivationMode mode) {
		if (!canBeTriggeredBy(sourceSerial)) {
			return;
		}

		// 脉冲语义：本次触发立即置为激活态，并在延时 tick 后自动熄灭。
		if (mode == ActivationMode.PULSE) {
			setActive(true);
			schedulePulseReset(getPulseDurationTicks());
			return;
		}

		setActive(!active);
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
		if (tag.contains(KEY_ACTIVATION_MODE)) {
			activationMode = ActivationMode.fromName(tag.getString(KEY_ACTIVATION_MODE));
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (active) {
			tag.putBoolean(KEY_ACTIVE, true);
		}
		tag.putString(KEY_ACTIVATION_MODE, activationMode.name());
	}
}
