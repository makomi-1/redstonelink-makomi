package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化拉杆方块实体。
 */
public class WirelessLeverBlockEntity extends WirelessSyncTriggerSourceBlockEntity {
	public WirelessLeverBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_LEVER, blockPos, blockState);
	}
}
