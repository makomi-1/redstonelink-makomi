package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkPulseButtonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;

public class LinkPulseButtonBlock extends LinkButtonBlock {
	public LinkPulseButtonBlock(BlockBehaviour.Properties properties) {
		// 木按钮外观 + 原版木按钮按下时长；联动语义由方块实体覆写为 PULSE（先激活后熄灭）。
		super(BlockSetType.OAK, 30, properties);
	}

	@Override
	protected LinkButtonBlockEntity createButtonBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkPulseButtonBlockEntity(blockPos, blockState);
	}
}
