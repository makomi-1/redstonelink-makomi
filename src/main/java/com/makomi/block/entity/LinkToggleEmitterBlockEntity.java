package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切换发射器方块实体：复用 triggerSource 公共实体层，保持默认 TOGGLE 触发模式。
 */
public class LinkToggleEmitterBlockEntity extends LinkTriggerSourceBlockEntity {
	public LinkToggleEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_TOGGLE_EMITTER, blockPos, blockState);
	}

	/**
	 * 允许 hide 变种切换为自定义实体类型，同时保持 TOGGLE 触发语义不变。
	 */
	protected LinkToggleEmitterBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}
}
