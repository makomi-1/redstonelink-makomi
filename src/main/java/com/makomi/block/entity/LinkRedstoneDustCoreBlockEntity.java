package com.makomi.block.entity;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.data.LinkNodeType;
import com.makomi.util.NeighborFanoutUtil;
import net.minecraft.core.BlockPos;
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
		if (state.getBlock() instanceof LinkRedstoneDustCoreBlock && state.getValue(LinkRedstoneDustCoreBlock.ACTIVE) != active) {
			level.setBlock(worldPosition, state.setValue(LinkRedstoneDustCoreBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
		}
		if (!shouldFanoutByResolvedOutput(active)) {
			return;
		}

		// 与核心块对齐：无论 active 变化还是仅功率变化，统一走中心+六方向扇出。
		// 工具层会补充已加载守卫与时间粒度去重，降低无效邻居通知。
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
	protected boolean shouldSyncClientOnPowerChanged() {
		// 核心粉功率/激活外显完全由 blockstate 承担，跳过额外方块实体同步以降低广播量。
		return false;
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			// 复用方块 tick 回调做脉冲回落。
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}
}
