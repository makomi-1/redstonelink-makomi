package com.makomi.block;

import com.makomi.block.entity.LinkTransparentRedstoneDustCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 透明红石粉核心方块，保留核心逻辑并使用纯透明渲染。
 */
public class LinkTransparentRedstoneDustCoreBlock extends LinkRedstoneDustCoreBlock {
	public LinkTransparentRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkTransparentRedstoneDustCoreBlockEntity(blockPos, blockState);
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.INVISIBLE;
	}
}
