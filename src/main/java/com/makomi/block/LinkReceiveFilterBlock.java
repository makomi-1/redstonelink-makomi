package com.makomi.block;

import com.makomi.block.entity.LinkReceiveFilterBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 接收过滤器方块。
 */
public class LinkReceiveFilterBlock extends AbstractLinkFilterBlock {
	public static final MapCodec<LinkReceiveFilterBlock> CODEC = simpleCodec(LinkReceiveFilterBlock::new);

	public LinkReceiveFilterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkReceiveFilterBlockEntity(blockPos, blockState);
	}
}
