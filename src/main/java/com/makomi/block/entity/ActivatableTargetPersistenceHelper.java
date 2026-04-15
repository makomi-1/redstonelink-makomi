package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.SourceKey;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SignalStrengths;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * `core` 目标端持久化辅助。
 * <p>
 * 统一维护 NBT 键定义，以及 `sync` 来源快照与 `pulse/toggle` 事件快照的序列化细节。
 * </p>
 */
final class ActivatableTargetPersistenceHelper {
	static final String KEY_ACTIVE = "Active";
	static final String KEY_CONFIGURED_MODE = "ConfiguredMode";
	static final String KEY_PULSE_UNTIL_GAME_TIME = "PulseExpireGameTime";
	static final String KEY_PULSE_EPOCH = "PulseEpoch";
	static final String KEY_TOGGLE_STATE = "ToggleState";
	static final String KEY_RESOLVED_OUTPUT_POWER = "ResolvedOutputPower";
	static final String KEY_SYNC_SOURCE_STRENGTHS = "SyncSourceStrengths";
	static final String KEY_SYNC_SOURCE_SERIAL = "Serial";
	static final String KEY_SYNC_SOURCE_STRENGTH = "Strength";
	static final String KEY_SYNC_MAX_SOURCES = "SyncMaxSources";
	static final String KEY_AUTHORITY_MODE = "AuthorityMode";
	static final String KEY_AUTHORITY_TICK = "AuthorityTick";
	static final String KEY_AUTHORITY_SLOT = "AuthoritySlot";
	static final String KEY_AUTHORITY_SEQ = "AuthoritySeq";
	static final String KEY_SYNC_CONCURRENT_ENTRIES = "SyncConcurrentEntries";
	static final String KEY_PULSE_CONCURRENT_ENTRIES = "PulseConcurrentEntries";
	static final String KEY_TOGGLE_CONCURRENT_ENTRIES = "ToggleConcurrentEntries";
	static final String KEY_PULSE_EVENT_RECORDED = "PulseEventRecorded";
	static final String KEY_PULSE_EVENT_TICK = "PulseEventTick";
	static final String KEY_PULSE_EVENT_SLOT = "PulseEventSlot";
	static final String KEY_PULSE_EVENT_SEQ = "PulseEventSeq";
	static final String KEY_TOGGLE_EVENT_RECORDED = "ToggleEventRecorded";
	static final String KEY_TOGGLE_EVENT_TICK = "ToggleEventTick";
	static final String KEY_TOGGLE_EVENT_SLOT = "ToggleEventSlot";
	static final String KEY_TOGGLE_EVENT_SEQ = "ToggleEventSeq";
	static final String KEY_CONCURRENT_SOURCE_TYPE = "SourceType";
	static final String KEY_CONCURRENT_SOURCE_SERIAL = "SourceSerial";
	static final String KEY_CONCURRENT_TICK = "Tick";
	static final String KEY_CONCURRENT_SLOT = "Slot";
	static final String KEY_CONCURRENT_SEQ = "Seq";
	static final String KEY_CONCURRENT_STRENGTH = "Strength";
	static final String KEY_CONCURRENT_UNTIL_TICK = "UntilTick";
	static final String KEY_CONCURRENT_CONTRIBUTES = "Contributes";
	static final String KEY_TOGGLE_CONCURRENT_COUNT = "ToggleConcurrentCount";

	private ActivatableTargetPersistenceHelper() {}

	static EffectiveMode parseEffectiveMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return EffectiveMode.NONE;
		}
		for (EffectiveMode mode : EffectiveMode.values()) {
			if (mode.name().equalsIgnoreCase(raw.trim())) {
				return mode;
			}
		}
		return EffectiveMode.NONE;
	}

	static void writeSyncSourceStrengths(CompoundTag tag, Map<Long, Integer> strengthBySource) {
		if (strengthBySource == null || strengthBySource.isEmpty()) {
			return;
		}
		ListTag sourceList = new ListTag();
		strengthBySource
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				long sourceSerial = entry.getKey() == null ? 0L : entry.getKey();
				int strength = entry.getValue() == null ? 0 : entry.getValue();
				if (sourceSerial <= 0L || strength <= 0) {
					return;
				}
				CompoundTag sourceTag = new CompoundTag();
				sourceTag.putLong(KEY_SYNC_SOURCE_SERIAL, sourceSerial);
				sourceTag.putInt(KEY_SYNC_SOURCE_STRENGTH, SignalStrengths.clamp(strength));
				sourceList.add(sourceTag);
			});
		if (!sourceList.isEmpty()) {
			tag.put(KEY_SYNC_SOURCE_STRENGTHS, sourceList);
		}
	}

	static void loadSyncSourceStrengths(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		concurrentComponent.syncSignalStrengthBySource().clear();
		if (!tag.contains(KEY_SYNC_SOURCE_STRENGTHS, Tag.TAG_LIST)) {
			return;
		}
		ListTag sourceList = tag.getList(KEY_SYNC_SOURCE_STRENGTHS, Tag.TAG_COMPOUND);
		for (int index = 0; index < sourceList.size(); index++) {
			CompoundTag sourceTag = sourceList.getCompound(index);
			long sourceSerial = sourceTag.getLong(KEY_SYNC_SOURCE_SERIAL);
			int strength = SignalStrengths.clamp(sourceTag.getInt(KEY_SYNC_SOURCE_STRENGTH));
			if (sourceSerial <= 0L || strength <= 0) {
				continue;
			}
			concurrentComponent.syncSignalStrengthBySource().put(sourceSerial, strength);
		}
	}

	static boolean loadConcurrentBuckets(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		concurrentComponent.syncConcurrentBuckets().clear();
		concurrentComponent.pulseConcurrentBuckets().clear();
		concurrentComponent.toggleConcurrentBuckets().clear();
		concurrentComponent.setPulseSnapshotRecorded(false);
		concurrentComponent.setPulseEventTimeKey(TimeKey.minValue());
		concurrentComponent.setPulseEventSeq(0L);
		concurrentComponent.setToggleSnapshotRecorded(false);
		concurrentComponent.setToggleEventTimeKey(TimeKey.minValue());
		concurrentComponent.setToggleEventSeq(0L);
		boolean loaded = false;
		loaded |= loadSyncConcurrentEntries(tag.getList(KEY_SYNC_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND), concurrentComponent);
		loaded |= loadPulseSnapshot(tag, concurrentComponent);
		loaded |= loadToggleSnapshot(tag, concurrentComponent);
		return loaded;
	}

	static void writeConcurrentBuckets(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		ListTag syncList = new ListTag();
		appendSyncConcurrentEntries(syncList, concurrentComponent);
		if (!syncList.isEmpty()) {
			tag.put(KEY_SYNC_CONCURRENT_ENTRIES, syncList);
		}
		if (concurrentComponent.pulseSnapshotRecorded() && concurrentComponent.pulseUntilGameTime() > 0L) {
			tag.putBoolean(KEY_PULSE_EVENT_RECORDED, true);
			tag.putLong(KEY_PULSE_EVENT_TICK, Math.max(0L, concurrentComponent.pulseEventTimeKey().tick()));
			tag.putInt(KEY_PULSE_EVENT_SLOT, Math.max(0, concurrentComponent.pulseEventTimeKey().slot()));
			tag.putLong(KEY_PULSE_EVENT_SEQ, Math.max(0L, concurrentComponent.pulseEventSeq()));
		}
		if (concurrentComponent.toggleSnapshotRecorded()) {
			tag.putBoolean(KEY_TOGGLE_EVENT_RECORDED, true);
			tag.putLong(KEY_TOGGLE_EVENT_TICK, Math.max(0L, concurrentComponent.toggleEventTimeKey().tick()));
			tag.putInt(KEY_TOGGLE_EVENT_SLOT, Math.max(0, concurrentComponent.toggleEventTimeKey().slot()));
			tag.putLong(KEY_TOGGLE_EVENT_SEQ, Math.max(0L, concurrentComponent.toggleEventSeq()));
		}
	}

	private static boolean loadPulseSnapshot(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		boolean recordedFromNewKeys = tag.contains(KEY_PULSE_EVENT_RECORDED, Tag.TAG_BYTE) && tag.getBoolean(KEY_PULSE_EVENT_RECORDED);
		if (recordedFromNewKeys) {
			concurrentComponent.setPulseSnapshotRecorded(true);
			concurrentComponent.setPulseEventTimeKey(
				TimeKey.of(
					Math.max(0L, tag.getLong(KEY_PULSE_EVENT_TICK)),
					Math.max(0, tag.getInt(KEY_PULSE_EVENT_SLOT))
				)
			);
			concurrentComponent.setPulseEventSeq(Math.max(0L, tag.getLong(KEY_PULSE_EVENT_SEQ)));
			return true;
		}
		return loadPulseSnapshotFromLegacyConcurrentEntries(tag.getList(KEY_PULSE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND), concurrentComponent);
	}

	private static boolean loadToggleSnapshot(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		boolean recordedFromNewKeys = tag.contains(KEY_TOGGLE_EVENT_RECORDED, Tag.TAG_BYTE) && tag.getBoolean(KEY_TOGGLE_EVENT_RECORDED);
		if (recordedFromNewKeys) {
			concurrentComponent.setToggleSnapshotRecorded(true);
			concurrentComponent.setToggleEventTimeKey(
				TimeKey.of(
					Math.max(0L, tag.getLong(KEY_TOGGLE_EVENT_TICK)),
					Math.max(0, tag.getInt(KEY_TOGGLE_EVENT_SLOT))
				)
			);
			concurrentComponent.setToggleEventSeq(Math.max(0L, tag.getLong(KEY_TOGGLE_EVENT_SEQ)));
			return true;
		}
		return loadToggleSnapshotFromLegacyConcurrentEntries(tag, concurrentComponent);
	}

	private static boolean loadSyncConcurrentEntries(
		ListTag listTag,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		boolean loaded = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			Optional<SourceKey> sourceKey = parseConcurrentSourceKey(entryTag);
			if (sourceKey.isEmpty()) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			int strength = SignalStrengths.clamp(entryTag.getInt(KEY_CONCURRENT_STRENGTH));
			if (strength <= 0) {
				continue;
			}
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			concurrentComponent
				.syncConcurrentBuckets()
				.computeIfAbsent(timeKey, ignored -> new java.util.TreeMap<>())
				.put(sourceKey.get(), new ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry(strength, seq));
			loaded = true;
		}
		return loaded;
	}

	private static boolean loadPulseSnapshotFromLegacyConcurrentEntries(
		ListTag listTag,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		TimeKey latestTimeKey = TimeKey.minValue();
		long latestSeq = 0L;
		boolean found = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			long untilTick = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_UNTIL_TICK));
			if (untilTick <= 0L) {
				continue;
			}
			if (!found || timeKey.compareTo(latestTimeKey) > 0 || (timeKey.compareTo(latestTimeKey) == 0 && seq > latestSeq)) {
				found = true;
				latestTimeKey = timeKey;
				latestSeq = seq;
			}
		}
		if (!found) {
			return false;
		}
		concurrentComponent.setPulseSnapshotRecorded(true);
		concurrentComponent.setPulseEventTimeKey(latestTimeKey);
		concurrentComponent.setPulseEventSeq(latestSeq);
		return true;
	}

	private static boolean loadToggleSnapshotFromLegacyConcurrentEntries(
		CompoundTag tag,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		ListTag listTag = tag.getList(KEY_TOGGLE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND);
		TimeKey latestTimeKey = TimeKey.minValue();
		long latestSeq = 0L;
		boolean found = false;
		for (int index = 0; index < listTag.size(); index++) {
			CompoundTag entryTag = listTag.getCompound(index);
			if (!entryTag.getBoolean(KEY_CONCURRENT_CONTRIBUTES)) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			if (!found || timeKey.compareTo(latestTimeKey) > 0 || (timeKey.compareTo(latestTimeKey) == 0 && seq > latestSeq)) {
				found = true;
				latestTimeKey = timeKey;
				latestSeq = seq;
			}
		}
		boolean legacyRecorded = found
			|| tag.contains(KEY_TOGGLE_STATE, Tag.TAG_BYTE)
			|| tag.getInt(KEY_TOGGLE_CONCURRENT_COUNT) > 0;
		if (!legacyRecorded) {
			return false;
		}
		concurrentComponent.setToggleSnapshotRecorded(true);
		concurrentComponent.setToggleEventTimeKey(latestTimeKey);
		concurrentComponent.setToggleEventSeq(latestSeq);
		return true;
	}

	private static Optional<SourceKey> parseConcurrentSourceKey(CompoundTag entryTag) {
		long sourceSerial = entryTag.getLong(KEY_CONCURRENT_SOURCE_SERIAL);
		if (sourceSerial <= 0L) {
			return Optional.empty();
		}
		String rawType = entryTag.getString(KEY_CONCURRENT_SOURCE_TYPE);
		Optional<LinkNodeType> sourceType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (sourceType.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new SourceKey(sourceType.get(), sourceSerial));
	}

	private static void appendSyncConcurrentEntries(
		ListTag targetList,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		for (Map.Entry<TimeKey, Map<SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry>> bucketEntry : concurrentComponent
			.syncConcurrentBuckets()
			.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || concurrentEntry.strength() <= 0) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putInt(KEY_CONCURRENT_STRENGTH, SignalStrengths.clamp(concurrentEntry.strength()));
				targetList.add(entryTag);
			}
		}
	}

	private static void writeConcurrentSourceKey(CompoundTag entryTag, SourceKey sourceKey, TimeKey timeKey, long seq) {
		entryTag.putString(KEY_CONCURRENT_SOURCE_TYPE, LinkNodeSemantics.toSemanticName(sourceKey.sourceType()));
		entryTag.putLong(KEY_CONCURRENT_SOURCE_SERIAL, sourceKey.sourceSerial());
		entryTag.putLong(KEY_CONCURRENT_TICK, Math.max(0L, timeKey.tick()));
		entryTag.putInt(KEY_CONCURRENT_SLOT, Math.max(0, timeKey.slot()));
		entryTag.putLong(KEY_CONCURRENT_SEQ, Math.max(0L, seq));
	}
}
