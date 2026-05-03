package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化石按钮方块实体。
 */
public class WirelessStoneButtonBlockEntity extends WirelessSyncTriggerSourceBlockEntity {
	public WirelessStoneButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_STONE_BUTTON, blockPos, blockState);
	}
}
