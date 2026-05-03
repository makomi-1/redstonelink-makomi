package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化 core 方块实体基类。
 * <p>
 * 仅负责把解析后的激活态同步到原版表现，
 * 不向邻居扇出红石更新，也不承担额外输出职责。
 * </p>
 */
public abstract class WirelessCoreBlockEntity extends ActivatableTargetBlockEntity {
	protected WirelessCoreBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected final LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	protected final void onActiveChanged(boolean active) {
		syncWirelessBlockState(active);
	}

	@Override
	protected final void syncBlockStateFromDerivedState(boolean active) {
		syncWirelessBlockState(active);
	}

	@Override
	protected boolean shouldSyncClientOnPowerChanged() {
		return false;
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}

	/**
	 * 按当前 core 激活态同步方块外显或原版受电表现。
	 */
	protected abstract void syncWirelessBlockState(boolean active);
}
