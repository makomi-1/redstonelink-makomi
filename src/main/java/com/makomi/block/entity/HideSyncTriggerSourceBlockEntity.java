package com.makomi.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏同步 `triggerSource` 方块实体。
 * <p>
 * 复用普通同步 `triggerSource` 的输入转发与回放快照逻辑，只切换为隐藏节点实体类型。
 * </p>
 */
public class HideSyncTriggerSourceBlockEntity extends LinkSyncEmitterBlockEntity {
	public HideSyncTriggerSourceBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.HIDE_SYNC_TRIGGER_SOURCE, blockPos, blockState);
	}
}
