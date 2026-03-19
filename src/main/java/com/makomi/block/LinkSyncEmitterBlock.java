package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步发射器：接收红石输入后，仅转发当前输入电平，不做切换或脉冲转换。
 */
public class LinkSyncEmitterBlock extends LinkSignalEmitterBlock {
	public LinkSyncEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected LinkButtonBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkSyncEmitterBlockEntity(blockPos, blockState);
	}

	@Override
	protected boolean shouldTriggerOnPoweredChange(boolean wasPowered, boolean hasSignal) {
		// 同步模式需同时处理上升沿与下降沿，确保目标状态可及时对齐输入。
		return true;
	}

	@Override
	protected boolean shouldTriggerOnSignalStateUnchanged(
		BlockState state,
		Level level,
		BlockPos pos,
		boolean hasSignal,
		int signalStrength
	) {
		if (!hasSignal) {
			return false;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkSyncEmitterBlockEntity blockEntity)) {
			return false;
		}
		// POWERED 未变化但输入强度变化时，仍需按最新强度转发一次。
		int normalizedStrength = Math.max(0, Math.min(15, signalStrength));
		return blockEntity.getLastObservedSignalStrength() != normalizedStrength;
	}

	@Override
	protected void onSignalTriggered(
		Level level,
		BlockPos pos,
		BlockState updatedState,
		boolean wasPowered,
		boolean hasSignal,
		int signalStrength
	) {
		if (level.getBlockEntity(pos) instanceof LinkSyncEmitterBlockEntity blockEntity) {
			int normalizedStrength = Math.max(0, Math.min(15, signalStrength));
			blockEntity.setLastObservedSignalStrength(normalizedStrength);
			blockEntity.forwardLinkedSignal(null, normalizedStrength);
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
			buttonBlockEntity.forwardLinkedSignal(null, Math.max(0, Math.min(15, signalStrength)));
		}
	}
}
