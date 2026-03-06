package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步发射器方块实体：复用按钮节点链路，供同步发射器转发输入电平。
 */
public class LinkSyncEmitterBlockEntity extends LinkButtonBlockEntity {
	public LinkSyncEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_SYNC_EMITTER, blockPos, blockState);
	}
}
