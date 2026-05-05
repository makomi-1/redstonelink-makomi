package com.makomi.block.entity;

import com.makomi.block.WirelessOpenableCoreBlock;
import com.makomi.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化可开合 core 通用方块实体。
 * <p>
 * 统一把无线输入激活态映射为门、活板门、栅栏门等原版“受电后开合”的表现。
 * </p>
 */
public class WirelessOpenableCoreBlockEntity extends WirelessCoreBlockEntity {
	public WirelessOpenableCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_OPENABLE_CORE, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (state.getBlock() instanceof WirelessOpenableCoreBlock wirelessOpenableCoreBlock) {
			wirelessOpenableCoreBlock.syncWirelessOpenState(level, worldPosition, state, active);
		}
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		if (level == null) {
			return false;
		}
		BlockState state = getBlockState();
		return state.getBlock() instanceof WirelessOpenableCoreBlock wirelessOpenableCoreBlock
			&& !wirelessOpenableCoreBlock.isWirelessOpenStateAligned(level, worldPosition, state, active);
	}
}
