package com.makomi.block;

import com.makomi.block.entity.LinkSendFilterBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发送过滤器方块。
 */
public class LinkSendFilterBlock extends AbstractLinkFilterBlock {
	public static final MapCodec<LinkSendFilterBlock> CODEC = simpleCodec(LinkSendFilterBlock::new);

	public LinkSendFilterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkSendFilterBlockEntity(blockPos, blockState);
	}
}
