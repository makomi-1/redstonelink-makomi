package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏接收过滤器方块实体。
 * <p>
 * 复用普通接收过滤器的配置、真值与运行态逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideReceiveFilterBlockEntity extends LinkReceiveFilterBlockEntity {
	public HideReceiveFilterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_RECEIVE_FILTER, blockPos, blockState);
	}
}
