package com.makomi.block.entity;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

		// 所有附着面统一委托方块刷新 active->power 状态，
		// 邻居通知由方块侧统一控制，避免重复扇出。
		coreBlock.onCoreActivationStateChanged(level, worldPosition);
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			// 复用方块 tick 回调做脉冲回落。
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}
}
