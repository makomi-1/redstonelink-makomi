package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化粘性活塞方块实体。
 * <p>
 * 行为与无线化活塞完全复用，只替换注册类型，便于持久化与注册表区分。
 * </p>
 */
public class WirelessStickyPistonBlockEntity extends WirelessPistonBlockEntity {
	public WirelessStickyPistonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.WIRELESS_STICKY_PISTON, blockPos, blockState);
	}
}
