package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 脉冲按钮方块实体：复用 triggerSource 公共实体层，并覆写为 PULSE 触发模式。
 */
public class LinkPulseButtonBlockEntity extends LinkTriggerSourceBlockEntity {
	public LinkPulseButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_PUSH_BUTTON, blockPos, blockState);
	}

	@Override
	protected ActivationMode getTriggerActivationMode() {
		return ActivationMode.PULSE;
	}
}
