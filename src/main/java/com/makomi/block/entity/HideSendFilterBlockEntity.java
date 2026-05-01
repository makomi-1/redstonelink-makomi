package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏发送过滤器方块实体。
 * <p>
 * 复用普通发送过滤器的配置、真值与运行态逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideSendFilterBlockEntity extends LinkSendFilterBlockEntity {
	public HideSendFilterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_SEND_FILTER, blockPos, blockState);
	}
}
