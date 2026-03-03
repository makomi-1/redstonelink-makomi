package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkPulseEmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 脉冲发射器：接收红石输入上升沿后，以 PULSE 语义触发绑定核心。
 */
public class LinkPulseEmitterBlock extends LinkSignalEmitterBlock {
	public LinkPulseEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected LinkButtonBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkPulseEmitterBlockEntity(blockPos, blockState);
	}
}
