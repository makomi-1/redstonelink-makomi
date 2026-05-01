package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏脉冲发射器方块实体。
 * <p>
 * 复用普通脉冲发射器的 `triggerSource` 运行态逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HidePulseEmitterBlockEntity extends LinkPulseEmitterBlockEntity {
	public HidePulseEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_PULSE_EMITTER, blockPos, blockState);
	}
}
