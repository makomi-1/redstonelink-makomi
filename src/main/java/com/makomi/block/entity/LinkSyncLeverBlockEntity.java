package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步拨杆方块实体：复用按钮节点链路，交互语义由方块侧按同步（sync）方式派发。
 */
public class LinkSyncLeverBlockEntity extends LinkButtonBlockEntity {
	public LinkSyncLeverBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_SYNC_LEVER, blockPos, blockState);
	}
}
