package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏切换发射器方块实体。
 * <p>
 * 复用普通切换发射器的 `triggerSource` 运行态逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideToggleEmitterBlockEntity extends LinkToggleEmitterBlockEntity {
	public HideToggleEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_TOGGLE_EMITTER, blockPos, blockState);
	}
}
