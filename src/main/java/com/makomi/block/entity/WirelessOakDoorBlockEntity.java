package com.makomi.block.entity;

import com.makomi.block.WirelessOakDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 无线化橡木门方块实体。
 */
public class WirelessOakDoorBlockEntity extends WirelessCoreBlockEntity {
	public WirelessOakDoorBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_OAK_DOOR, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof WirelessOakDoorBlock) || state.getValue(WirelessOakDoorBlock.HALF) != DoubleBlockHalf.LOWER) {
			return;
		}
		if (state.getValue(WirelessOakDoorBlock.OPEN) == active) {
			return;
		}
		((WirelessOakDoorBlock) state.getBlock()).syncWirelessOpenState(level, state, worldPosition, active);
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessOakDoorBlock
			&& state.getValue(WirelessOakDoorBlock.HALF) == DoubleBlockHalf.LOWER
			&& state.getValue(WirelessOakDoorBlock.OPEN) != active;
	}
}
