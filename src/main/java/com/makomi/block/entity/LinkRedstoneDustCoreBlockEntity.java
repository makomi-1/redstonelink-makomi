package com.makomi.block.entity;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 核心红石粉节点方块实体。
 * <p>
 * 负责把 active 状态同步到 {@link LinkRedstoneDustCoreBlock}，
 * </p>
 */
public class LinkRedstoneDustCoreBlockEntity extends ActivatableTargetBlockEntity {
	public LinkRedstoneDustCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_DUST_CORE, blockPos, blockState);
	}

	protected LinkRedstoneDustCoreBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	protected void onActiveChanged(boolean active) {
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock coreBlock)) {
			return;
		}

		// 所有附着面统一委托方块刷新 active->power 状态。
		coreBlock.onCoreActivationStateChanged(level, worldPosition);

		BlockState updatedState = level.getBlockState(worldPosition);
		if (!(updatedState.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			return;
		}

		// 顶面与非顶面统一补一次周边刷新，避免通知强度分叉。
		Block block = updatedState.getBlock();
		level.updateNeighborsAt(worldPosition, block);
		for (Direction direction : Direction.values()) {
			level.updateNeighborsAt(worldPosition.relative(direction), block);
		}
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			// 复用方块 tick 回调做脉冲回落。
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}
}
