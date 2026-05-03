package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化石压力板方块实体。
 */
public class WirelessStonePressurePlateBlockEntity extends WirelessSyncTriggerSourceBlockEntity {
	public WirelessStonePressurePlateBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_STONE_PRESSURE_PLATE, blockPos, blockState);
	}
}
