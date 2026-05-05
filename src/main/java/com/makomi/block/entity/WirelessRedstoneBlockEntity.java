package com.makomi.block.entity;

import com.makomi.block.WirelessRedstoneBlock;
import com.makomi.registry.ModBlockEntities;
import com.makomi.util.NeighborFanoutUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化红石块方块实体。
 * <p>
 * 负责把 core 激活态映射为原版红石块式的持续邻居输出。
 * </p>
 */
public class WirelessRedstoneBlockEntity extends WirelessCoreBlockEntity {
	public WirelessRedstoneBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_REDSTONE_BLOCK, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof WirelessRedstoneBlock) || state.getValue(WirelessRedstoneBlock.ACTIVE) == active) {
			return;
		}
		level.setBlock(worldPosition, state.setValue(WirelessRedstoneBlock.ACTIVE, active), 2);
		NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, worldPosition, state.getBlock());
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessRedstoneBlock && state.getValue(WirelessRedstoneBlock.ACTIVE) != active;
	}
}
