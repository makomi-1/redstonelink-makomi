package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.SourceKey;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SignalStrengths;
import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

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

	static void writeSyncSourceStrengths(ValueOutput output, Map<Long, Integer> strengthBySource) {
		if (strengthBySource == null || strengthBySource.isEmpty()) {
			return;
		}
		ValueOutput.ValueOutputList sourceList = output.childrenList(KEY_SYNC_SOURCE_STRENGTHS);
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
				ValueOutput sourceTag = sourceList.addChild();
				sourceTag.putLong(KEY_SYNC_SOURCE_SERIAL, sourceSerial);
				sourceTag.putInt(KEY_SYNC_SOURCE_STRENGTH, SignalStrengths.clamp(strength));
			});
	}

	static void loadSyncSourceStrengths(ValueInput input, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		concurrentComponent.syncSignalStrengthBySource().clear();
		for (ValueInput sourceTag : input.childrenListOrEmpty(KEY_SYNC_SOURCE_STRENGTHS).stream().toList()) {
			long sourceSerial = sourceTag.getLongOr(KEY_SYNC_SOURCE_SERIAL, 0L);
			int strength = SignalStrengths.clamp(sourceTag.getIntOr(KEY_SYNC_SOURCE_STRENGTH, 0));
			if (sourceSerial <= 0L || strength <= 0) {
				continue;
			}
			concurrentComponent.syncSignalStrengthBySource().put(sourceSerial, strength);
		}
	}

	static boolean loadConcurrentBuckets(ValueInput input, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
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
		loaded |= loadSyncConcurrentEntries(input.childrenListOrEmpty(KEY_SYNC_CONCURRENT_ENTRIES), concurrentComponent);
		loaded |= loadPulseSnapshot(input, concurrentComponent);
		loaded |= loadToggleSnapshot(input, concurrentComponent);
		return loaded;
	}

	static void writeConcurrentBuckets(ValueOutput output, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		appendSyncConcurrentEntries(output.childrenList(KEY_SYNC_CONCURRENT_ENTRIES), concurrentComponent);
		if (concurrentComponent.pulseSnapshotRecorded() && concurrentComponent.pulseUntilGameTime() > 0L) {
			output.putBoolean(KEY_PULSE_EVENT_RECORDED, true);
			output.putLong(KEY_PULSE_EVENT_TICK, Math.max(0L, concurrentComponent.pulseEventTimeKey().tick()));
			output.putInt(KEY_PULSE_EVENT_SLOT, Math.max(0, concurrentComponent.pulseEventTimeKey().slot()));
			output.putLong(KEY_PULSE_EVENT_SEQ, Math.max(0L, concurrentComponent.pulseEventSeq()));
		}
		if (concurrentComponent.toggleSnapshotRecorded()) {
			output.putBoolean(KEY_TOGGLE_EVENT_RECORDED, true);
			output.putLong(KEY_TOGGLE_EVENT_TICK, Math.max(0L, concurrentComponent.toggleEventTimeKey().tick()));
			output.putInt(KEY_TOGGLE_EVENT_SLOT, Math.max(0, concurrentComponent.toggleEventTimeKey().slot()));
			output.putLong(KEY_TOGGLE_EVENT_SEQ, Math.max(0L, concurrentComponent.toggleEventSeq()));
		}
	}

	private static boolean loadPulseSnapshot(ValueInput input, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		boolean recordedFromNewKeys = input.getBooleanOr(KEY_PULSE_EVENT_RECORDED, false);
		if (recordedFromNewKeys) {
			concurrentComponent.setPulseSnapshotRecorded(true);
			concurrentComponent.setPulseEventTimeKey(
				TimeKey.of(
					Math.max(0L, input.getLongOr(KEY_PULSE_EVENT_TICK, 0L)),
					Math.max(0, input.getIntOr(KEY_PULSE_EVENT_SLOT, 0))
				)
			);
			concurrentComponent.setPulseEventSeq(Math.max(0L, input.getLongOr(KEY_PULSE_EVENT_SEQ, 0L)));
			return true;
		}
		return loadPulseSnapshotFromLegacyConcurrentEntries(input.childrenListOrEmpty(KEY_PULSE_CONCURRENT_ENTRIES), concurrentComponent);
	}

	private static boolean loadToggleSnapshot(ValueInput input, ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		boolean recordedFromNewKeys = input.getBooleanOr(KEY_TOGGLE_EVENT_RECORDED, false);
		if (recordedFromNewKeys) {
			concurrentComponent.setToggleSnapshotRecorded(true);
			concurrentComponent.setToggleEventTimeKey(
				TimeKey.of(
					Math.max(0L, input.getLongOr(KEY_TOGGLE_EVENT_TICK, 0L)),
					Math.max(0, input.getIntOr(KEY_TOGGLE_EVENT_SLOT, 0))
				)
			);
			concurrentComponent.setToggleEventSeq(Math.max(0L, input.getLongOr(KEY_TOGGLE_EVENT_SEQ, 0L)));
			return true;
		}
		return loadToggleSnapshotFromLegacyConcurrentEntries(input, concurrentComponent);
	}

	private static boolean loadSyncConcurrentEntries(
		ValueInput.ValueInputList listTag,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		boolean loaded = false;
		for (ValueInput entryTag : listTag.stream().toList()) {
			Optional<SourceKey> sourceKey = parseConcurrentSourceKey(entryTag);
			if (sourceKey.isEmpty()) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_TICK, 0L)),
				Math.max(0, entryTag.getIntOr(KEY_CONCURRENT_SLOT, 0))
			);
			int strength = SignalStrengths.clamp(entryTag.getIntOr(KEY_CONCURRENT_STRENGTH, 0));
			if (strength <= 0) {
				continue;
			}
			long seq = Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_SEQ, 0L));
			concurrentComponent
				.syncConcurrentBuckets()
				.computeIfAbsent(timeKey, ignored -> new java.util.TreeMap<>())
				.put(sourceKey.get(), new ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry(strength, seq));
			loaded = true;
		}
		return loaded;
	}

	private static boolean loadPulseSnapshotFromLegacyConcurrentEntries(
		ValueInput.ValueInputList listTag,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		TimeKey latestTimeKey = TimeKey.minValue();
		long latestSeq = 0L;
		boolean found = false;
		for (ValueInput entryTag : listTag.stream().toList()) {
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_TICK, 0L)),
				Math.max(0, entryTag.getIntOr(KEY_CONCURRENT_SLOT, 0))
			);
			long seq = Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_SEQ, 0L));
			long untilTick = Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_UNTIL_TICK, 0L));
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
		ValueInput input,
		ActivatableTargetConcurrentBucketComponent concurrentComponent
	) {
		ValueInput.ValueInputList listTag = input.childrenListOrEmpty(KEY_TOGGLE_CONCURRENT_ENTRIES);
		TimeKey latestTimeKey = TimeKey.minValue();
		long latestSeq = 0L;
		boolean found = false;
		for (ValueInput entryTag : listTag.stream().toList()) {
			if (!entryTag.getBooleanOr(KEY_CONCURRENT_CONTRIBUTES, false)) {
				continue;
			}
			TimeKey timeKey = TimeKey.of(
				Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_TICK, 0L)),
				Math.max(0, entryTag.getIntOr(KEY_CONCURRENT_SLOT, 0))
			);
			long seq = Math.max(0L, entryTag.getLongOr(KEY_CONCURRENT_SEQ, 0L));
			if (!found || timeKey.compareTo(latestTimeKey) > 0 || (timeKey.compareTo(latestTimeKey) == 0 && seq > latestSeq)) {
				found = true;
				latestTimeKey = timeKey;
				latestSeq = seq;
			}
		}
		boolean legacyRecorded = found
			|| input.getBooleanOr(KEY_TOGGLE_STATE, false)
			|| input.getIntOr(KEY_TOGGLE_CONCURRENT_COUNT, 0) > 0;
		if (!legacyRecorded) {
			return false;
		}
		concurrentComponent.setToggleSnapshotRecorded(true);
		concurrentComponent.setToggleEventTimeKey(latestTimeKey);
		concurrentComponent.setToggleEventSeq(latestSeq);
		return true;
	}

	private static Optional<SourceKey> parseConcurrentSourceKey(ValueInput entryTag) {
		long sourceSerial = entryTag.getLongOr(KEY_CONCURRENT_SOURCE_SERIAL, 0L);
		if (sourceSerial <= 0L) {
			return Optional.empty();
		}
		String rawType = entryTag.getStringOr(KEY_CONCURRENT_SOURCE_TYPE, "");
		Optional<LinkNodeType> sourceType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (sourceType.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new SourceKey(sourceType.get(), sourceSerial));
	}

	private static void appendSyncConcurrentEntries(
		ValueOutput.ValueOutputList targetList,
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
				ValueOutput entryTag = targetList.addChild();
				writeConcurrentSourceKey(entryTag, sourceKey, timeKey, concurrentEntry.seq());
				entryTag.putInt(KEY_CONCURRENT_STRENGTH, SignalStrengths.clamp(concurrentEntry.strength()));
			}
		}
	}

	private static void writeConcurrentSourceKey(ValueOutput entryTag, SourceKey sourceKey, TimeKey timeKey, long seq) {
		entryTag.putString(KEY_CONCURRENT_SOURCE_TYPE, LinkNodeSemantics.toSemanticName(sourceKey.sourceType()));
		entryTag.putLong(KEY_CONCURRENT_SOURCE_SERIAL, sourceKey.sourceSerial());
		entryTag.putLong(KEY_CONCURRENT_TICK, Math.max(0L, timeKey.tick()));
		entryTag.putInt(KEY_CONCURRENT_SLOT, Math.max(0, timeKey.slot()));
		entryTag.putLong(KEY_CONCURRENT_SEQ, Math.max(0L, seq));
	}
}
