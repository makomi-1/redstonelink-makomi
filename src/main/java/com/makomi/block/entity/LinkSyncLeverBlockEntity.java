package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步拨杆方块实体：复用 triggerSource 公共实体层，交互语义由方块侧按同步（sync）方式派发。
 */
public class LinkSyncLeverBlockEntity extends SyncReplaySourceBlockEntity {
	public LinkSyncLeverBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_SYNC_LEVER, blockPos, blockState);
	}
}
