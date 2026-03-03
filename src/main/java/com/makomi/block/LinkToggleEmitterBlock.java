package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkToggleEmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 切换发射器：接收红石输入上升沿后，以 TOGGLE 语义触发绑定核心。
 */
public class LinkToggleEmitterBlock extends LinkSignalEmitterBlock {
	public LinkToggleEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected LinkButtonBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkToggleEmitterBlockEntity(blockPos, blockState);
	}
}
