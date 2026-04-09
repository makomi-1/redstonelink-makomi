package com.makomi.block.entity;

import com.makomi.data.LinkFilterKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发送过滤器方块实体。
 * <p>
 * 仅服务 `triggerSource` 派发入口。
 * </p>
 */
public class LinkSendFilterBlockEntity extends AbstractLinkFilterBlockEntity {
	public LinkSendFilterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_SEND_FILTER, blockPos, blockState);
	}

	@Override
	public LinkFilterKind filterKind() {
		return LinkFilterKind.SEND;
	}
}
