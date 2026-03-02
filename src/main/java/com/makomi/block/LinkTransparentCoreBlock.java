package com.makomi.block;

import com.makomi.block.entity.LinkTransparentCoreBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 透明红石块核心方块，保留核心行为并切换到透明实体类型。
 */
public class LinkTransparentCoreBlock extends LinkCoreBlock {
	public static final MapCodec<LinkTransparentCoreBlock> CODEC = simpleCodec(LinkTransparentCoreBlock::new);

	public LinkTransparentCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkTransparentCoreBlockEntity(blockPos, blockState);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
