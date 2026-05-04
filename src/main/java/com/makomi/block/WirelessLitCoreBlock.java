package com.makomi.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 无线化可亮灭 `core` 方块接口。
 * <p>
 * 用于把不同原版外观方块统一收敛到“由无线输入控制亮灭”的同一同步协议上。
 * </p>
 */
public interface WirelessLitCoreBlock {
	/**
	 * 按当前无线激活态同步方块外显。
	 *
	 * @param level 当前世界
	 * @param pos 当前方块位置
	 * @param active 当前 `core` 是否处于激活态
	 */
	void syncWirelessLitState(Level level, BlockPos pos, boolean active);

	/**
	 * 判断当前方块外显是否与目标激活态一致。
	 *
	 * @param level 当前世界
	 * @param pos 当前方块位置
	 * @param active 目标激活态
	 * @return 一致时返回 `true`
	 */
	boolean isWirelessLitStateAligned(Level level, BlockPos pos, boolean active);
}
