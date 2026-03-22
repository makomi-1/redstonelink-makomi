package com.makomi.block;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.util.SignalStrengths;
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
	protected boolean shouldTriggerOnSignalUpdate(
		BlockState state,
		Level level,
		BlockPos pos,
		boolean wasPowered,
		boolean hasSignal,
		int signalStrength
	) {
		if (!(level.getBlockEntity(pos) instanceof LinkSyncEmitterBlockEntity blockEntity)) {
			return false;
		}
		// 同步模式统一按“输入强度变化”触发，边沿变化属于强度变化子集。
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
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
			int normalizedStrength = SignalStrengths.clamp(signalStrength);
			blockEntity.setLastObservedSignalStrength(normalizedStrength);
			blockEntity.recordReplaySyncSnapshot(normalizedStrength, EventMeta.now(level));
			blockEntity.forwardLinkedSignal(null, normalizedStrength);
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
			buttonBlockEntity.forwardLinkedSignal(null, SignalStrengths.clamp(signalStrength));
		}
	}
}
