package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import com.makomi.util.NeighborFanoutUtil;
import com.makomi.util.SignalStrengths;
import net.minecraft.world.level.Level;

/**
 * `core` 目标端同步观测组件。
 * <p>
 * 维护派生输出功率、同时间粒度结果写回去重、邻居扇出去重与加载后静默校正标记。
 * </p>
 */
final class ActivatableTargetObservationComponent {
	private int resolvedOutputPower;
	private boolean pendingLoadBlockStateSync;

	private boolean tickResolvedInitialized;
	private TimeKey tickResolvedTimeKey = TimeKey.minValue();
	private boolean tickResolvedState;
	private int tickResolvedPower;

	private boolean fanoutResolvedInitialized;
	private TimeKey fanoutResolvedTimeKey = TimeKey.minValue();
	private boolean fanoutResolvedState;
	private int fanoutResolvedPower;

	int resolvedOutputPower() {
		return resolvedOutputPower;
	}

	void setResolvedOutputPowerRaw(int resolvedOutputPower) {
		this.resolvedOutputPower = SignalStrengths.clamp(resolvedOutputPower);
	}

	boolean pendingLoadBlockStateSync() {
		return pendingLoadBlockStateSync;
	}

	void setPendingLoadBlockStateSync(boolean pendingLoadBlockStateSync) {
		this.pendingLoadBlockStateSync = pendingLoadBlockStateSync;
	}

	boolean tickResolvedInitialized() {
		return tickResolvedInitialized;
	}

	void setTickResolvedInitialized(boolean tickResolvedInitialized) {
		this.tickResolvedInitialized = tickResolvedInitialized;
	}

	boolean fanoutResolvedInitialized() {
		return fanoutResolvedInitialized;
	}

	void setFanoutResolvedInitialized(boolean fanoutResolvedInitialized) {
		this.fanoutResolvedInitialized = fanoutResolvedInitialized;
	}

	void invalidateTickResolvedCache() {
		tickResolvedInitialized = false;
	}

	long fanoutTimeTick(TimeKey authorityTimeKey) {
		return authorityTimeKey == null ? 0L : Math.max(0L, authorityTimeKey.tick());
	}

	int fanoutTimeSlot(TimeKey authorityTimeKey) {
		return authorityTimeKey == null ? 0 : Math.max(0, authorityTimeKey.slot());
	}

	boolean setResolvedOutputPower(ActivatableTargetBlockEntity owner, int outputPower) {
		int normalizedPower = SignalStrengths.clamp(outputPower);
		if (resolvedOutputPower == normalizedPower) {
			return false;
		}
		resolvedOutputPower = normalizedPower;
		owner.setChanged();
		return true;
	}

	void applyResolvedState(
		ActivatableTargetBlockEntity owner,
		TimeKey authorityTimeKey,
		boolean resolvedActive,
		int resolvedPower
	) {
		Level level = owner.getLevel();
		if (level == null || level.isClientSide) {
			return;
		}
		TimeKey normalizedTimeKey = authorityTimeKey == null ? TimeKey.of(0L, 0) : authorityTimeKey;
		if (!normalizedTimeKey.equals(tickResolvedTimeKey)) {
			tickResolvedTimeKey = normalizedTimeKey;
			tickResolvedInitialized = false;
			tickResolvedPower = 0;
		}
		int normalizedPower = SignalStrengths.clamp(resolvedPower);
		boolean powerChanged = setResolvedOutputPower(owner, normalizedPower);
		if (tickResolvedInitialized && tickResolvedState == resolvedActive && tickResolvedPower == normalizedPower) {
			return;
		}
		tickResolvedInitialized = true;
		tickResolvedState = resolvedActive;
		tickResolvedPower = normalizedPower;
		if (owner.isActive() != resolvedActive) {
			owner.setActive(resolvedActive);
			return;
		}
		if (powerChanged) {
			owner.onActiveChanged(owner.isActive());
			if (owner.shouldSyncClientOnPowerChanged()) {
				owner.syncToClient();
			}
		}
	}

	boolean shouldFanoutByResolvedOutput(
		EffectiveMode effectiveMode,
		TimeKey authorityTimeKey,
		boolean resolvedActive,
		int resolvedOutputPower
	) {
		if (effectiveMode != EffectiveMode.SYNC) {
			return true;
		}
		int normalizedPower = SignalStrengths.clamp(resolvedOutputPower);
		if (
			fanoutResolvedInitialized
				&& fanoutResolvedState == resolvedActive
				&& fanoutResolvedPower == normalizedPower
		) {
			NeighborFanoutUtil.recordFanoutDedupHit();
			return false;
		}
		fanoutResolvedInitialized = true;
		fanoutResolvedState = resolvedActive;
		fanoutResolvedPower = normalizedPower;
		fanoutResolvedTimeKey = authorityTimeKey == null ? TimeKey.of(0L, 0) : authorityTimeKey;
		return true;
	}

	void consumePendingLoadBlockStateSync(ActivatableTargetBlockEntity owner) {
		if (!pendingLoadBlockStateSync) {
			return;
		}
		pendingLoadBlockStateSync = false;
		owner.syncBlockStateFromDerivedState(owner.isActive());
	}

	void resetTransientAfterLoad() {
		tickResolvedInitialized = false;
		tickResolvedTimeKey = TimeKey.minValue();
		tickResolvedPower = 0;
		fanoutResolvedInitialized = false;
		fanoutResolvedTimeKey = TimeKey.minValue();
		fanoutResolvedPower = 0;
	}
}
