package com.makomi.block.entity;

import com.makomi.block.WirelessSeaLanternBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化海晶灯方块实体。
 */
public class WirelessSeaLanternBlockEntity extends WirelessCoreBlockEntity {
	public WirelessSeaLanternBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_SEA_LANTERN, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof WirelessSeaLanternBlock) || state.getValue(WirelessSeaLanternBlock.LIT) == active) {
			return;
		}
		level.setBlock(worldPosition, state.setValue(WirelessSeaLanternBlock.LIT, active), 2);
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessSeaLanternBlock && state.getValue(WirelessSeaLanternBlock.LIT) != active;
	}
}
