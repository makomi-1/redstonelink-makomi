package com.makomi.block.entity;

import com.makomi.data.LinkFilterKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发送过滤器方块实体。
 * <p>
 * 仅服务 `triggerSource` 派发入口。
 * </p>
 */
public class LinkSendFilterBlockEntity extends AbstractLinkFilterBlockEntity {
	public LinkSendFilterBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_SEND_FILTER, blockPos, blockState);
	}

	/**
	 * 允许 hide 变种切换为自定义实体类型，同时保持 SEND 过滤语义不变。
	 */
	protected LinkSendFilterBlockEntity(
		BlockEntityType<? extends AbstractLinkFilterBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	public LinkFilterKind filterKind() {
		return LinkFilterKind.SEND;
	}
}
