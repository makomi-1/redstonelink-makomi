package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切换按钮方块实体：复用 triggerSource 公共实体层，保持默认 TOGGLE 触发模式。
 */
public class LinkToggleButtonBlockEntity extends LinkTriggerSourceBlockEntity {
	public LinkToggleButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_BUTTON, blockPos, blockState);
	}
}
