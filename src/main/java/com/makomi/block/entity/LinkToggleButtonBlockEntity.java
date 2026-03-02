package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class LinkToggleButtonBlockEntity extends LinkButtonBlockEntity {
	public LinkToggleButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_BUTTON, blockPos, blockState);
	}
}
