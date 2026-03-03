package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 脉冲发射器方块实体：复用按钮节点链路，并覆写为 PULSE 触发模式。
 */
public class LinkPulseEmitterBlockEntity extends LinkButtonBlockEntity {
	public LinkPulseEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_PULSE_EMITTER, blockPos, blockState);
	}

	@Override
	protected ActivationMode getTriggerActivationMode() {
		return ActivationMode.PULSE;
	}
}
