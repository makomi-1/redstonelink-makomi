package com.makomi.block.entity;

import com.makomi.block.LinkCoreBlock;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class LinkCoreBlockEntity extends ActivatableTargetBlockEntity {
	public LinkCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_CORE, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	protected void onActiveChanged(boolean active) {
		BlockState state = level.getBlockState(worldPosition);
		if (state.getBlock() instanceof LinkCoreBlock && state.getValue(LinkCoreBlock.ACTIVE) != active) {
			level.setBlock(worldPosition, state.setValue(LinkCoreBlock.ACTIVE, active), Block.UPDATE_ALL);
		}

		// 主动刷新周围红石邻接，确保电路立即更新。
		Block block = state.getBlock();
		level.updateNeighborsAt(worldPosition, block);
		for (Direction direction : Direction.values()) {
			level.updateNeighborsAt(worldPosition.relative(direction), block);
		}
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}
}
