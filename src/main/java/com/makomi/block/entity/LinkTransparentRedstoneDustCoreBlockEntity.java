package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 透明红石粉核心方块实体，复用普通红石粉核心的激活与配对逻辑。
 */
public class LinkTransparentRedstoneDustCoreBlockEntity extends LinkRedstoneDustCoreBlockEntity {
	public LinkTransparentRedstoneDustCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_DUST_CORE_TRANSPARENT, blockPos, blockState);
	}
}
