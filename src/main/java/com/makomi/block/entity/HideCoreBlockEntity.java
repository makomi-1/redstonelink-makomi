package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏核心块方块实体。
 * <p>
 * 复用普通核心块的真值、序号与生命周期逻辑，只切换为隐藏核心块实体类型。
 * </p>
 */
public class HideCoreBlockEntity extends LinkCoreBlockEntity {
	public HideCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_CORE, blockPos, blockState);
	}
}
