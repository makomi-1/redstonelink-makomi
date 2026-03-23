package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切换发射器方块实体：复用 triggerSource 公共实体层，保持默认 TOGGLE 触发模式。
 */
public class LinkToggleEmitterBlockEntity extends LinkTriggerSourceBlockEntity {
	public LinkToggleEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_EMITTER, blockPos, blockState);
	}
}
