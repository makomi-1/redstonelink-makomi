package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 透明红石块核心方块实体，复用普通红石块核心的激活与配对逻辑。
 */
public class LinkTransparentCoreBlockEntity extends LinkCoreBlockEntity {
	public LinkTransparentCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_CORE_TRANSPARENT, blockPos, blockState);
	}
}
