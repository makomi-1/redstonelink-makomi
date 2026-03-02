package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkToggleButtonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;

public class LinkToggleButtonBlock extends LinkButtonBlock {
	public LinkToggleButtonBlock(BlockBehaviour.Properties properties) {
		super(BlockSetType.STONE, 20, properties);
	}

	@Override
	protected LinkButtonBlockEntity createButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkToggleButtonBlockEntity(blockPos, blockState);
	}
}
