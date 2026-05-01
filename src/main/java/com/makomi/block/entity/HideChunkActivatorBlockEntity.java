package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏区块激活器方块实体。
 * <p>
 * 复用普通区块激活器的配置、真值与运行态逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideChunkActivatorBlockEntity extends LinkChunkActivatorBlockEntity {
	public HideChunkActivatorBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_CHUNK_ACTIVATOR, blockPos, blockState);
	}
}
