package com.makomi.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无线化可开合 core 协议。
 * <p>
 * 供门、活板门、栅栏门等“原版受电后开合”的方块复用统一同步入口。
 * </p>
 */
public interface WirelessOpenableCoreBlock {
	/**
	 * 按当前无线激活态同步原版开合表现。
	 */
	void syncWirelessOpenState(Level level, BlockPos pos, BlockState state, boolean active);

	/**
	 * 判断当前外显是否已与目标激活态对齐。
	 */
	boolean isWirelessOpenStateAligned(Level level, BlockPos pos, BlockState state, boolean active);
}
