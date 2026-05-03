package com.makomi.block.entity;

import com.makomi.block.WirelessRedstoneLampBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化红石灯方块实体。
 */
public class WirelessRedstoneLampBlockEntity extends WirelessCoreBlockEntity {
	public WirelessRedstoneLampBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_REDSTONE_LAMP, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof WirelessRedstoneLampBlock) || state.getValue(WirelessRedstoneLampBlock.LIT) == active) {
			return;
		}
		level.setBlock(worldPosition, state.setValue(WirelessRedstoneLampBlock.LIT, active), 2);
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessRedstoneLampBlock && state.getValue(WirelessRedstoneLampBlock.LIT) != active;
	}
}
