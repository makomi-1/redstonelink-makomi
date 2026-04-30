package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏转发器方块实体。
 * <p>
 * 复用普通转发器的双身份、延迟派发与配置逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideRepeaterBlockEntity extends LinkRepeaterBlockEntity {
	public HideRepeaterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_REPEATER, blockPos, blockState);
	}
}
