package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class LinkToggleLeverBlockEntity extends LinkButtonBlockEntity {
	public LinkToggleLeverBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_LEVER, blockPos, blockState);
	}
}
