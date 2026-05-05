package com.makomi.block.entity;

import com.makomi.block.WirelessNoteBlock;
import com.makomi.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化音符盒方块实体。
 * <p>
 * 保留原版调音与演奏外观，仅把无线输入转接成原版受电触发。
 * </p>
 */
public class WirelessNoteBlockEntity extends WirelessCoreBlockEntity {
	public WirelessNoteBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_NOTE_BLOCK, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		if (state.getBlock() instanceof WirelessNoteBlock wirelessNoteBlock) {
			wirelessNoteBlock.syncWirelessPoweredState(level, worldPosition, state, active);
		}
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		return false;
	}
}
