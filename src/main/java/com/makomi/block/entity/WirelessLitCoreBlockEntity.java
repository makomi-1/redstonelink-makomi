package com.makomi.block.entity;

import com.makomi.block.WirelessLitCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化可亮灭 `core` 方块实体基类。
 * <p>
 * 统一把 `core` 激活态映射到方块的亮灭外显。
 * </p>
 */
public class WirelessLitCoreBlockEntity extends WirelessCoreBlockEntity {
	public WirelessLitCoreBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected void syncWirelessBlockState(boolean active) {
		if (level == null || !(getBlockState().getBlock() instanceof WirelessLitCoreBlock wirelessLitCoreBlock)) {
			return;
		}
		wirelessLitCoreBlock.syncWirelessLitState(level, worldPosition, active);
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		return level != null
			&& getBlockState().getBlock() instanceof WirelessLitCoreBlock wirelessLitCoreBlock
			&& !wirelessLitCoreBlock.isWirelessLitStateAligned(level, worldPosition, active);
	}
}
