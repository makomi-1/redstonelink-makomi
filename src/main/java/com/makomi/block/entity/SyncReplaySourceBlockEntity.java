package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SignalStrengths;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 支持 sync 回放快照的 triggerSource 基类。
 * <p>
 * 用途：
 * 1. 在来源端真实派发 sync 时记录最近一次强度与时间键；
 * 2. 供目标区块 `CHUNK_LOAD` 回放时复用原始事件时间，而不是目标加载时刻；
 * 3. 未记录过快照时视为“不具备可回放 sync 状态”。
 * </p>
 */
public abstract class SyncReplaySourceBlockEntity extends LinkTriggerSourceBlockEntity {
	private static final String KEY_REPLAY_SYNC_SNAPSHOT_RECORDED = "ReplaySyncSnapshotRecorded";
	private static final String KEY_REPLAY_SYNC_SIGNAL_STRENGTH = "ReplaySyncSignalStrength";
	private static final String KEY_REPLAY_SYNC_TICK = "ReplaySyncTick";
	private static final String KEY_REPLAY_SYNC_SLOT = "ReplaySyncSlot";
	private static final String KEY_REPLAY_SYNC_SEQ = "ReplaySyncSeq";

	private boolean replaySyncSnapshotRecorded;
	private int replaySyncSignalStrength;
	private long replaySyncTick;
	private int replaySyncSlot;
	private long replaySyncSeq;
	private boolean runtimeReplaySyncSnapshotRecorded;
	private int runtimeReplaySyncSignalStrength;
	private long runtimeReplaySyncTick;
	private int runtimeReplaySyncSlot;
	private long runtimeReplaySyncSeq;

	protected SyncReplaySourceBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	/**
	 * 记录最近一次 sync 派发快照。
	 *
	 * @param signalStrength 最近一次派发强度（0~15）
	 * @param eventMeta 最近一次派发时间键
	 */
	public final void recordReplaySyncSnapshot(int signalStrength, EventMeta eventMeta) {
		recordReplaySyncSnapshot(signalStrength, eventMeta, false);
	}

	/**
	 * 记录当前进程内可见的运行时 sync 回放快照。
	 * <p>
	 * 该快照只在内存中生效，不会进入 NBT。
	 * </p>
	 */
	public final void recordRuntimeReplaySyncSnapshot(int signalStrength, EventMeta eventMeta) {
		recordReplaySyncSnapshot(signalStrength, eventMeta, true);
	}

	/**
	 * 清空当前进程内的运行时 sync 回放快照。
	 */
	public final void clearRuntimeReplaySyncSnapshot() {
		runtimeReplaySyncSnapshotRecorded = false;
		runtimeReplaySyncSignalStrength = 0;
		runtimeReplaySyncTick = 0L;
		runtimeReplaySyncSlot = 0;
		runtimeReplaySyncSeq = 0L;
	}

	private void recordReplaySyncSnapshot(int signalStrength, EventMeta eventMeta, boolean runtimeOnly) {
		EventMeta normalizedMeta = eventMeta;
		if (normalizedMeta == null) {
			if (level == null) {
				return;
			}
			normalizedMeta = EventMeta.now(level);
		}
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		long tick = normalizedMeta.timeKey().tick();
		int slot = normalizedMeta.timeKey().slot();
		long seq = normalizedMeta.seq();
		if (runtimeOnly) {
			if (
				runtimeReplaySyncSnapshotRecorded
					&& runtimeReplaySyncSignalStrength == normalizedStrength
					&& runtimeReplaySyncTick == tick
					&& runtimeReplaySyncSlot == slot
					&& runtimeReplaySyncSeq == seq
			) {
				return;
			}
			runtimeReplaySyncSnapshotRecorded = true;
			runtimeReplaySyncSignalStrength = normalizedStrength;
			runtimeReplaySyncTick = tick;
			runtimeReplaySyncSlot = slot;
			runtimeReplaySyncSeq = seq;
			return;
		}
		if (
			replaySyncSnapshotRecorded
				&& replaySyncSignalStrength == normalizedStrength
				&& replaySyncTick == tick
				&& replaySyncSlot == slot
				&& replaySyncSeq == seq
		) {
			return;
		}
		replaySyncSnapshotRecorded = true;
		replaySyncSignalStrength = normalizedStrength;
		replaySyncTick = tick;
		replaySyncSlot = slot;
		replaySyncSeq = seq;
		persistReplaySnapshotToLinkSavedData(normalizedStrength, normalizedMeta);
		setChanged();
	}

	/**
	 * 返回最近一次已记录的 sync 回放快照。
	 */
	public final Optional<ReplaySyncSnapshot> replaySyncSnapshot() {
		if (runtimeReplaySyncSnapshotRecorded) {
			return Optional.of(
				new ReplaySyncSnapshot(
					runtimeReplaySyncSignalStrength,
					EventMeta.of(runtimeReplaySyncTick, runtimeReplaySyncSlot, runtimeReplaySyncSeq)
				)
			);
		}
		if (!replaySyncSnapshotRecorded) {
			return Optional.empty();
		}
		return Optional.of(
			new ReplaySyncSnapshot(
				replaySyncSignalStrength,
				EventMeta.of(replaySyncTick, replaySyncSlot, replaySyncSeq)
			)
		);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		clearRuntimeReplaySyncSnapshot();
		replaySyncSnapshotRecorded = tag.contains(KEY_REPLAY_SYNC_SNAPSHOT_RECORDED, Tag.TAG_BYTE)
			&& tag.getBoolean(KEY_REPLAY_SYNC_SNAPSHOT_RECORDED);
		if (!replaySyncSnapshotRecorded) {
			replaySyncSignalStrength = 0;
			replaySyncTick = 0L;
			replaySyncSlot = 0;
			replaySyncSeq = 0L;
			return;
		}
		replaySyncSignalStrength = SignalStrengths.clamp(tag.getInt(KEY_REPLAY_SYNC_SIGNAL_STRENGTH));
		replaySyncTick = Math.max(0L, tag.getLong(KEY_REPLAY_SYNC_TICK));
		replaySyncSlot = Math.max(0, tag.getInt(KEY_REPLAY_SYNC_SLOT));
		replaySyncSeq = Math.max(0L, tag.getLong(KEY_REPLAY_SYNC_SEQ));
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (!replaySyncSnapshotRecorded) {
			return;
		}
		tag.putBoolean(KEY_REPLAY_SYNC_SNAPSHOT_RECORDED, true);
		tag.putInt(KEY_REPLAY_SYNC_SIGNAL_STRENGTH, replaySyncSignalStrength);
		tag.putLong(KEY_REPLAY_SYNC_TICK, replaySyncTick);
		tag.putInt(KEY_REPLAY_SYNC_SLOT, replaySyncSlot);
		tag.putLong(KEY_REPLAY_SYNC_SEQ, replaySyncSeq);
	}

	/**
	 * 最近一次可用于 `CHUNK_LOAD` replay 的 sync 快照。
	 *
	 * @param signalStrength 最近一次派发强度
	 * @param eventMeta 最近一次派发时间键
	 */
	public record ReplaySyncSnapshot(int signalStrength, EventMeta eventMeta) {
		public ReplaySyncSnapshot {
			signalStrength = SignalStrengths.clamp(signalStrength);
			eventMeta = eventMeta == null ? EventMeta.of(0L, 0, 0L) : eventMeta;
		}
	}

	/**
	 * 将最近一次真实 sync replay 快照同步到世界级存档，供来源区块离线后的 target chunk load replay 使用。
	 */
	private void persistReplaySnapshotToLinkSavedData(int signalStrength, EventMeta eventMeta) {
		if (level == null || level.isClientSide() || getSerial() <= 0L || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		LinkSavedData.get(serverLevel).putTriggerSourceReplaySyncSnapshot(getSerial(), eventMeta, signalStrength);
	}
}
