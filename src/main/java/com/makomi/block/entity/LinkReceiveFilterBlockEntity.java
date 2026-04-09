package com.makomi.block.entity;

import com.makomi.data.LinkFilterKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 接收过滤器方块实体。
 * <p>
 * 仅服务 `core` 候选目标过滤。
 * </p>
 */
public class LinkReceiveFilterBlockEntity extends AbstractLinkFilterBlockEntity {
	public LinkReceiveFilterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_RECEIVE_FILTER, blockPos, blockState);
	}

	@Override
	public LinkFilterKind filterKind() {
		return LinkFilterKind.RECEIVE;
	}
}
