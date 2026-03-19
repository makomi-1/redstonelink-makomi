package com.makomi.block.entity;

import com.makomi.util.SignalStrengths;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步发射器方块实体：复用按钮节点链路，供同步发射器转发输入电平。
 */
public class LinkSyncEmitterBlockEntity extends LinkButtonBlockEntity {
	private int lastObservedSignalStrength;

	public LinkSyncEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_SYNC_EMITTER, blockPos, blockState);
	}

	/**
	 * @return 最近一次已转发的输入强度（0~15）
	 */
	public int getLastObservedSignalStrength() {
		return lastObservedSignalStrength;
	}

	/**
	 * 记录最近一次已转发的输入强度。
	 */
	public void setLastObservedSignalStrength(int signalStrength) {
		lastObservedSignalStrength = SignalStrengths.clamp(signalStrength);
	}
}
