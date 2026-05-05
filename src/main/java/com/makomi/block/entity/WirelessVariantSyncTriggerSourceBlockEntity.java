package com.makomi.block.entity;

import com.makomi.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化按钮/压力板通用同步 triggerSource 方块实体。
 * <p>
 * 统一复用同步 triggerSource 的信号缓存与回放能力，
 * 供同一批原版红石分类按钮、压力板变种共享。
 * </p>
 */
public class WirelessVariantSyncTriggerSourceBlockEntity extends WirelessSyncTriggerSourceBlockEntity {
	public WirelessVariantSyncTriggerSourceBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_VARIANT_SYNC_TRIGGER_SOURCE, blockPos, blockState);
	}
}
