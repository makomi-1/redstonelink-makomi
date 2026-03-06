package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切换按钮方块实体：复用按钮节点链路，保持默认 TOGGLE 触发模式。
 */
public class LinkToggleButtonBlockEntity extends LinkButtonBlockEntity {
	public LinkToggleButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_BUTTON, blockPos, blockState);
	}
}
