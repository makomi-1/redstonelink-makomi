package com.makomi.block.entity;

import com.makomi.block.WirelessPistonBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化活塞方块实体。
 */
public class WirelessPistonBlockEntity extends WirelessCoreBlockEntity {
	public WirelessPistonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_PISTON, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null || !(level.getBlockState(worldPosition).getBlock() instanceof WirelessPistonBlock wirelessPistonBlock)) {
			return;
		}
		wirelessPistonBlock.syncWirelessExtension(level, worldPosition, level.getBlockState(worldPosition), active);
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessPistonBlock && state.getValue(WirelessPistonBlock.EXTENDED) != active;
	}
}
