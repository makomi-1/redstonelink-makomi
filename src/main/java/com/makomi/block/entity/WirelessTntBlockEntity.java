package com.makomi.block.entity;

import com.makomi.block.WirelessTntBlock;
import com.makomi.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化 TNT 方块实体。
 * <p>
 * 当 core 收到激活态时，按原版 TNT 受电逻辑点燃自身。
 * </p>
 */
public class WirelessTntBlockEntity extends WirelessCoreBlockEntity {
	public WirelessTntBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_TNT, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (!active || level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (state.getBlock() instanceof WirelessTntBlock wirelessTntBlock) {
			wirelessTntBlock.primeWireless(level, worldPosition);
		}
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		return false;
	}
}
