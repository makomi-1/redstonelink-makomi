package com.makomi.block.entity;

import com.makomi.util.SignalStrengths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同步发射器方块实体：复用按钮节点链路，供同步发射器转发输入电平。
 */
public class LinkSyncEmitterBlockEntity extends SyncReplaySourceBlockEntity {
	private static final String KEY_LAST_OBSERVED_SIGNAL_STRENGTH = "LastObservedSignalStrength";

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
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		if (lastObservedSignalStrength == normalizedStrength) {
			return;
		}
		lastObservedSignalStrength = normalizedStrength;
		// 强度变化不一定伴随方块状态变化，需显式标脏保证重启后可恢复“最近已转发值”。
		setChanged();
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		if (tag.contains(KEY_LAST_OBSERVED_SIGNAL_STRENGTH, Tag.TAG_INT)) {
			lastObservedSignalStrength = SignalStrengths.clamp(tag.getInt(KEY_LAST_OBSERVED_SIGNAL_STRENGTH));
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		tag.putInt(KEY_LAST_OBSERVED_SIGNAL_STRENGTH, SignalStrengths.clamp(lastObservedSignalStrength));
	}
}
