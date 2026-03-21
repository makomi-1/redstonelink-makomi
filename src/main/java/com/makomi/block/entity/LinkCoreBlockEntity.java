package com.makomi.block.entity;

import com.makomi.block.LinkCoreBlock;
import com.makomi.data.LinkNodeType;
import com.makomi.util.NeighborFanoutUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 核心接收节点方块实体。
 * <p>
 * 接收来自按钮/遥控器的触发并维护 ACTIVE 方块状态，同时主动刷新周围红石邻接。
 * </p>
 */
public class LinkCoreBlockEntity extends ActivatableTargetBlockEntity {
	public LinkCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_CORE, blockPos, blockState);
	}

	protected LinkCoreBlockEntity(
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
		if (state.getBlock() instanceof LinkCoreBlock && state.getValue(LinkCoreBlock.ACTIVE) != active) {
			level.setBlock(worldPosition, state.setValue(LinkCoreBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
		}
		if (!shouldFanoutByResolvedOutput(active)) {
			return;
		}

		// 主动刷新周围红石邻接；工具层会做已加载守卫与时间粒度去重。
		NeighborFanoutUtil.notifyCenterAndSixNeighbors(
			level,
			worldPosition,
			state.getBlock(),
			getFanoutTimeTick(),
			getFanoutTimeSlot(),
			active,
			getResolvedOutputPower()
		);
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}
}
