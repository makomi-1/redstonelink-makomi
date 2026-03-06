package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步发射器：接收红石输入后，仅转发当前输入电平，不做切换或脉冲转换。
 */
public class LinkSyncEmitterBlock extends LinkSignalEmitterBlock {
	public LinkSyncEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected LinkButtonBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkSyncEmitterBlockEntity(blockPos, blockState);
	}

	@Override
	protected boolean shouldTriggerOnPoweredChange(boolean wasPowered, boolean hasSignal) {
		// 同步模式需同时处理上升沿与下降沿，确保目标状态可及时对齐输入。
		return true;
	}

	@Override
	protected void onSignalTriggered(Level level, BlockPos pos, BlockState updatedState, boolean wasPowered, boolean hasSignal) {
		if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
			buttonBlockEntity.forwardLinkedSignal(null, hasSignal);
		}
	}
}
