package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 脉冲发射器方块实体：复用 triggerSource 公共实体层，并覆写为 PULSE 触发模式。
 */
public class LinkPulseEmitterBlockEntity extends LinkTriggerSourceBlockEntity {
	public LinkPulseEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_PULSE_EMITTER, blockPos, blockState);
	}

	/**
	 * 允许 hide 变种切换为自定义实体类型，同时保持 PULSE 触发语义不变。
	 */
	protected LinkPulseEmitterBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected ActivationMode getTriggerActivationMode() {
		return ActivationMode.PULSE;
	}
}
