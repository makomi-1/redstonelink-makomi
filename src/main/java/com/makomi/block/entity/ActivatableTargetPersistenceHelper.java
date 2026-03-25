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
 * 统一维护 NBT 键定义，以及并发来源桶与 `sync` 快照的序列化细节。
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
		boolean loaded = false;
		loaded |= loadSyncConcurrentEntries(tag.getList(KEY_SYNC_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND), concurrentComponent);
		loaded |= loadPulseConcurrentEntries(tag.getList(KEY_PULSE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND), concurrentComponent);
		loaded |= loadToggleConcurrentEntries(tag.getList(KEY_TOGGLE_CONCURRENT_ENTRIES, Tag.TAG_COMPOUND), concurrentComponent);
		return loaded;
	}

	static void writeConcurrentBuckets(CompoundTag tag, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		ListTag syncList = new ListTag();
		appendSyncConcurrentEntries(syncList, concurrentComponent);
		if (!syncList.isEmpty()) {
			tag.put(KEY_SYNC_CONCURRENT_ENTRIES, syncList);
		}

		ListTag pulseList = new ListTag();
		appendPulseConcurrentEntries(pulseList, concurrentComponent);
		if (!pulseList.isEmpty()) {
			tag.put(KEY_PULSE_CONCURRENT_ENTRIES, pulseList);
		}

		ListTag toggleList = new ListTag();
		appendToggleConcurrentEntries(toggleList, concurrentComponent);
		if (!toggleList.isEmpty()) {
			tag.put(KEY_TOGGLE_CONCURRENT_ENTRIES, toggleList);
		}
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

	private static boolean loadPulseConcurrentEntries(
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
			long untilTick = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_UNTIL_TICK));
			if (untilTick <= 0L) {
				continue;
			}
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			concurrentComponent
				.pulseConcurrentBuckets()
				.computeIfAbsent(timeKey, ignored -> new java.util.TreeMap<>())
				.put(sourceKey.get(), new ActivatableTargetConcurrentBucketComponent.PulseConcurrentEntry(untilTick, seq));
			loaded = true;
		}
		return loaded;
	}

	private static boolean loadToggleConcurrentEntries(
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
			if (!entryTag.getBoolean(KEY_CONCURRENT_CONTRIBUTES)) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLong(KEY_CONCURRENT_TICK)),
				Math.max(0, entryTag.getInt(KEY_CONCURRENT_SLOT))
			);
			long seq = Math.max(0L, entryTag.getLong(KEY_CONCURRENT_SEQ));
			concurrentComponent
				.toggleConcurrentBuckets()
				.computeIfAbsent(timeKey, ignored -> new java.util.TreeMap<>())
				.put(sourceKey.get(), new ActivatableTargetConcurrentBucketComponent.ToggleConcurrentEntry(true, seq));
			loaded = true;
		}
		return loaded;
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

	private static void appendPulseConcurrentEntries(
		ListTag targetList,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		for (Map.Entry<TimeKey, Map<SourceKey, ActivatableTargetConcurrentBucketComponent.PulseConcurrentEntry>> bucketEntry : concurrentComponent
			.pulseConcurrentBuckets()
			.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, ActivatableTargetConcurrentBucketComponent.PulseConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ActivatableTargetConcurrentBucketComponent.PulseConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				ActivatableTargetConcurrentBucketComponent.PulseConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || concurrentEntry.untilGameTick() <= 0L) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putLong(KEY_CONCURRENT_UNTIL_TICK, Math.max(0L, concurrentEntry.untilGameTick()));
				targetList.add(entryTag);
			}
		}
	}

	private static void appendToggleConcurrentEntries(
		ListTag targetList,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		for (Map.Entry<TimeKey, Map<SourceKey, ActivatableTargetConcurrentBucketComponent.ToggleConcurrentEntry>> bucketEntry : concurrentComponent
			.toggleConcurrentBuckets()
			.entrySet()) {
			TimeKey timeKey = bucketEntry.getKey();
			Map<SourceKey, ActivatableTargetConcurrentBucketComponent.ToggleConcurrentEntry> bucket = bucketEntry.getValue();
			if (timeKey == null || bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ActivatableTargetConcurrentBucketComponent.ToggleConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				ActivatableTargetConcurrentBucketComponent.ToggleConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || concurrentEntry == null || !concurrentEntry.contributes()) {
					continue;
				}
				CompoundTag entryTag = new CompoundTag();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putBoolean(KEY_CONCURRENT_CONTRIBUTES, true);
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
