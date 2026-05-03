package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化同步 triggerSource 方块实体基类。
 * <p>
 * 统一复用：
 * 1. sync 回放快照；
 * 2. 最近一次已转发输入强度缓存；
 * 3. 加载后输入状态静默重采样判定。
 * </p>
 */
public abstract class WirelessSyncTriggerSourceBlockEntity extends LinkSyncEmitterBlockEntity {
	protected WirelessSyncTriggerSourceBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}
}
