package com.makomi.block.entity;

import com.makomi.util.SignalStrengths;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 同步发射器方块实体：复用 triggerSource 公共实体层，供同步发射器转发输入电平。
 */
public class LinkSyncEmitterBlockEntity extends SyncReplaySourceBlockEntity {
	private static final String KEY_LAST_OBSERVED_SIGNAL_STRENGTH = "LastObservedSignalStrength";

	private int lastObservedSignalStrength;

	public LinkSyncEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_SYNC_EMITTER, blockPos, blockState);
	}

	/**
	 * 允许派生类切换为自定义实体类型，同时复用同步 `triggerSource` 运行态逻辑。
	 */
	protected LinkSyncEmitterBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
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
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		if (lastObservedSignalStrength == normalizedStrength) {
			return;
		}
		lastObservedSignalStrength = normalizedStrength;
		// 强度变化不一定伴随方块状态变化，需显式标脏保证重启后可恢复“最近已转发值”。
		setChanged();
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		lastObservedSignalStrength = SignalStrengths.clamp(input.getIntOr(KEY_LAST_OBSERVED_SIGNAL_STRENGTH, 0));
		refreshPendingLoadInputStateResyncFlag();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt(KEY_LAST_OBSERVED_SIGNAL_STRENGTH, SignalStrengths.clamp(lastObservedSignalStrength));
	}

	@Override
	protected boolean shouldQueueLoadInputStateResync() {
		if (super.shouldQueueLoadInputStateResync()) {
			return true;
		}
		if (lastObservedSignalStrength > 0) {
			return true;
		}
		return replaySyncSnapshot().map(snapshot -> snapshot.signalStrength() > 0).orElse(false);
	}
}
