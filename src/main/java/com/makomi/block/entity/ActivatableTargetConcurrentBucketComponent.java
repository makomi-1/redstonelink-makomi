package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.SourceKey;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.world.level.Level;

/**
 * `core` 目标端并发来源桶组件。
 * <p>
 * 统一维护 `sync/pulse/toggle` 三类持久化来源桶、运行态模拟 `sync` 桶、
 * 以及由桶重建出的结构真值与快照。
 * </p>
 */
final class ActivatableTargetConcurrentBucketComponent {
	private long pulseUntilGameTime;
	private long pulseEpoch;
	private boolean toggleState;
	private boolean pulseResetArmed;

	private final Map<Long, Integer> syncSignalStrengthBySource = new HashMap<>();
	private int syncSignalMaxStrength;
	private final Set<Long> syncSignalMaxSources = new TreeSet<>();

	private final NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> syncConcurrentBuckets = new TreeMap<>();
	private final NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> runtimeSimulatedSyncConcurrentBuckets = new TreeMap<>();
	private final NavigableMap<TimeKey, Map<SourceKey, PulseConcurrentEntry>> pulseConcurrentBuckets = new TreeMap<>();
	private final NavigableMap<TimeKey, Map<SourceKey, ToggleConcurrentEntry>> toggleConcurrentBuckets = new TreeMap<>();

	private int toggleConcurrentCount;
	private final Set<SourceKey> toggleFrameStartContributors = new TreeSet<>();
	private final Set<SourceKey> toggleSourcesTouchedInCurrentFrame = new TreeSet<>();

	long pulseUntilGameTime() {
		return pulseUntilGameTime;
	}

	void setPulseUntilGameTime(long pulseUntilGameTime) {
		this.pulseUntilGameTime = Math.max(0L, pulseUntilGameTime);
	}

	long pulseEpoch() {
		return pulseEpoch;
	}

	void setPulseEpoch(long pulseEpoch) {
		this.pulseEpoch = Math.max(0L, pulseEpoch);
	}

	boolean toggleState() {
		return toggleState;
	}

	void setToggleState(boolean toggleState) {
		this.toggleState = toggleState;
	}

	boolean pulseResetArmed() {
		return pulseResetArmed;
	}

	void setPulseResetArmed(boolean pulseResetArmed) {
		this.pulseResetArmed = pulseResetArmed;
	}

	int syncSignalMaxStrength() {
		return syncSignalMaxStrength;
	}

	void setSyncSignalMaxStrength(int syncSignalMaxStrength) {
		this.syncSignalMaxStrength = SignalStrengths.clamp(syncSignalMaxStrength);
	}

	int toggleConcurrentCount() {
		return toggleConcurrentCount;
	}

	void setToggleConcurrentCount(int toggleConcurrentCount) {
		this.toggleConcurrentCount = Math.max(0, toggleConcurrentCount);
	}

	Map<Long, Integer> syncSignalStrengthBySource() {
		return syncSignalStrengthBySource;
	}

	Set<Long> syncSignalMaxSources() {
		return syncSignalMaxSources;
	}

	NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> syncConcurrentBuckets() {
		return syncConcurrentBuckets;
	}

	NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> runtimeSimulatedSyncConcurrentBuckets() {
		return runtimeSimulatedSyncConcurrentBuckets;
	}

	NavigableMap<TimeKey, Map<SourceKey, PulseConcurrentEntry>> pulseConcurrentBuckets() {
		return pulseConcurrentBuckets;
	}

	NavigableMap<TimeKey, Map<SourceKey, ToggleConcurrentEntry>> toggleConcurrentBuckets() {
		return toggleConcurrentBuckets;
	}

	List<Long> syncMaxSourceSerialsSnapshot() {
		if (syncSignalMaxSources.isEmpty()) {
			return List.of();
		}
		return List.copyOf(new ArrayList<>(syncSignalMaxSources));
	}

	boolean updateSyncSignalStrength(long sourceSerial, int signalStrength, TimeKey authorityTimeKey, long authoritySeq) {
		SourceKey sourceKey = new SourceKey(com.makomi.data.LinkNodeType.TRIGGER_SOURCE, sourceSerial);
		boolean bucketChanged;
		if (sourceSerial <= 0L) {
			bucketChanged = !syncConcurrentBuckets.isEmpty() || !runtimeSimulatedSyncConcurrentBuckets.isEmpty();
			syncConcurrentBuckets.clear();
			runtimeSimulatedSyncConcurrentBuckets.clear();
		} else if (SignalStrengths.clamp(signalStrength) <= 0) {
			bucketChanged = removeSyncConcurrentSource(sourceKey);
		} else {
			bucketChanged = upsertSyncConcurrentSource(sourceKey, authorityTimeKey, signalStrength, authoritySeq);
		}
		recomputeSyncTruthFromConcurrentBuckets();
		return bucketChanged;
	}

	boolean pruneOlderFramesForIncoming(TimeKey incomingTimeKey, EffectiveMode incomingMode) {
		TimeKey normalizedTimeKey = incomingTimeKey == null ? TimeKey.of(0L, 0) : incomingTimeKey;
		boolean changed = removeConcurrentBucketsBefore(syncConcurrentBuckets, normalizedTimeKey);
		changed |= removeConcurrentBucketsBefore(runtimeSimulatedSyncConcurrentBuckets, normalizedTimeKey);
		changed |= removeConcurrentBucketsBefore(toggleConcurrentBuckets, normalizedTimeKey);
		if (incomingMode == EffectiveMode.SYNC) {
			changed |= clearPulseTruth();
			return changed;
		}
		if (incomingMode == EffectiveMode.PULSE) {
			changed |= removeConcurrentBucketsBefore(pulseConcurrentBuckets, normalizedTimeKey);
		}
		return changed;
	}

	boolean clearPulseTruth() {
		boolean changed = !pulseConcurrentBuckets.isEmpty() || pulseUntilGameTime > 0L || pulseResetArmed;
		pulseConcurrentBuckets.clear();
		pulseUntilGameTime = 0L;
		pulseResetArmed = false;
		return changed;
	}

	boolean upsertSyncConcurrentSource(SourceKey sourceKey, TimeKey timeKey, int strength, long seq) {
		SyncConcurrentEntry nextEntry = new SyncConcurrentEntry(strength, seq);
		if (hasExactConcurrentEntry(syncConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(syncConcurrentBuckets, sourceKey);
		Map<SourceKey, SyncConcurrentEntry> bucket = syncConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		return true;
	}

	boolean removeSyncConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(syncConcurrentBuckets, sourceKey);
	}

	boolean upsertRuntimeSimulatedSyncConcurrentSource(SourceKey sourceKey, TimeKey timeKey, int strength, long seq) {
		SyncConcurrentEntry nextEntry = new SyncConcurrentEntry(strength, seq);
		if (hasExactConcurrentEntry(runtimeSimulatedSyncConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(runtimeSimulatedSyncConcurrentBuckets, sourceKey);
		Map<SourceKey, SyncConcurrentEntry> bucket = runtimeSimulatedSyncConcurrentBuckets.computeIfAbsent(
			timeKey,
			ignored -> new TreeMap<>()
		);
		bucket.put(sourceKey, nextEntry);
		return true;
	}

	boolean removeRuntimeSimulatedSyncConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(runtimeSimulatedSyncConcurrentBuckets, sourceKey);
	}

	boolean upsertPulseConcurrentSource(
		ActivatableTargetBlockEntity owner,
		SourceKey sourceKey,
		TimeKey timeKey,
		long seq
	) {
		int pulseTicks = Math.max(1, owner.getPulseDurationTicks());
		Level level = owner.getLevel();
		long now = level == null ? 0L : level.getGameTime();
		long untilTick = now + pulseTicks;
		PulseConcurrentEntry nextEntry = new PulseConcurrentEntry(untilTick, seq);
		if (hasExactConcurrentEntry(pulseConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(pulseConcurrentBuckets, sourceKey);
		Map<SourceKey, PulseConcurrentEntry> bucket = pulseConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		pulseEpoch++;
		if (level != null) {
			owner.schedulePulseReset(pulseTicks);
		}
		return true;
	}

	boolean removePulseConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(pulseConcurrentBuckets, sourceKey);
	}

	boolean upsertToggleConcurrentSource(SourceKey sourceKey, TimeKey timeKey, long seq, boolean hadContributionBeforePrune) {
		boolean next = !hadContributionBeforePrune;
		if (!next) {
			return removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
		}
		ToggleConcurrentEntry nextEntry = new ToggleConcurrentEntry(true, seq);
		if (hasExactConcurrentEntry(toggleConcurrentBuckets, sourceKey, timeKey, nextEntry)) {
			return false;
		}
		removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
		Map<SourceKey, ToggleConcurrentEntry> bucket = toggleConcurrentBuckets.computeIfAbsent(timeKey, ignored -> new TreeMap<>());
		bucket.put(sourceKey, nextEntry);
		return true;
	}

	boolean removeToggleConcurrentSource(SourceKey sourceKey) {
		return removeSourceFromConcurrentBuckets(toggleConcurrentBuckets, sourceKey);
	}

	void beginToggleFrame() {
		toggleFrameStartContributors.clear();
		toggleSourcesTouchedInCurrentFrame.clear();
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, ToggleConcurrentEntry> entry : bucket.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null && entry.getValue().contributes()) {
					toggleFrameStartContributors.add(entry.getKey());
				}
			}
		}
	}

	boolean resolveToggleContributionBeforePrune(SourceKey sourceKey) {
		if (sourceKey == null) {
			return false;
		}
		if (toggleSourcesTouchedInCurrentFrame.contains(sourceKey)) {
			return findToggleContribution(sourceKey);
		}
		return toggleFrameStartContributors.contains(sourceKey) || findToggleContribution(sourceKey);
	}

	void markToggleSourceTouchedInCurrentFrame(SourceKey sourceKey) {
		if (sourceKey != null) {
			toggleSourcesTouchedInCurrentFrame.add(sourceKey);
		}
	}

	void recomputeSyncTruthFromConcurrentBuckets() {
		syncSignalStrengthBySource.clear();
		mergeSyncTruthFromBuckets(syncConcurrentBuckets, syncSignalStrengthBySource);
		mergeSyncTruthFromBuckets(runtimeSimulatedSyncConcurrentBuckets, syncSignalStrengthBySource);
		syncSignalMaxStrength = recalculateSyncMaxStrengthAndSources();
	}

	PersistentSyncSnapshot buildPersistentSyncSnapshot() {
		Map<Long, Integer> persistentStrengthBySource = new TreeMap<>();
		mergeSyncTruthFromBuckets(syncConcurrentBuckets, persistentStrengthBySource);
		int maxStrength = 0;
		Set<Long> persistentMaxSources = new TreeSet<>();
		for (Map.Entry<Long, Integer> entry : persistentStrengthBySource.entrySet()) {
			Long sourceSerial = entry.getKey();
			Integer strength = entry.getValue();
			if (sourceSerial == null || sourceSerial <= 0L || strength == null || strength <= 0) {
				continue;
			}
			if (strength > maxStrength) {
				maxStrength = strength;
				persistentMaxSources.clear();
				persistentMaxSources.add(sourceSerial);
				continue;
			}
			if (strength == maxStrength) {
				persistentMaxSources.add(sourceSerial);
			}
		}
		return new PersistentSyncSnapshot(persistentStrengthBySource, persistentMaxSources);
	}

	boolean recomputePulseTruthFromConcurrentBuckets(ActivatableTargetBlockEntity owner) {
		Level level = owner.getLevel();
		long now = level == null ? 0L : level.getGameTime();
		List<TimeKey> emptyKeys = new ArrayList<>();
		long maxUntilTick = 0L;
		boolean changed = false;
		for (Map.Entry<TimeKey, Map<SourceKey, PulseConcurrentEntry>> bucketEntry : pulseConcurrentBuckets.entrySet()) {
			Map<SourceKey, PulseConcurrentEntry> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				changed = true;
				continue;
			}
			if (bucket.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().untilGameTick() <= now)) {
				changed = true;
			}
			if (bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				continue;
			}
			for (PulseConcurrentEntry pulseEntry : bucket.values()) {
				if (pulseEntry == null) {
					continue;
				}
				maxUntilTick = Math.max(maxUntilTick, pulseEntry.untilGameTick());
			}
		}
		for (TimeKey emptyKey : emptyKeys) {
			if (pulseConcurrentBuckets.remove(emptyKey) != null) {
				changed = true;
			}
		}
		if (pulseUntilGameTime != maxUntilTick) {
			changed = true;
		}
		pulseUntilGameTime = maxUntilTick;
		boolean nextPulseResetArmed = maxUntilTick > now;
		if (pulseResetArmed != nextPulseResetArmed) {
			changed = true;
		}
		pulseResetArmed = nextPulseResetArmed;
		if (pulseResetArmed && level != null) {
			long remaining = Math.max(1L, maxUntilTick - now);
			owner.schedulePulseReset((int) remaining);
		}
		return changed;
	}

	void recomputeToggleTruthFromConcurrentBuckets() {
		int activeContributors = 0;
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (ToggleConcurrentEntry entry : bucket.values()) {
				if (entry != null && entry.contributes()) {
					activeContributors++;
				}
			}
		}
		toggleConcurrentCount = activeContributors;
	}

	boolean hasAnyConcurrentBuckets() {
		return !syncConcurrentBuckets.isEmpty()
			|| !runtimeSimulatedSyncConcurrentBuckets.isEmpty()
			|| !pulseConcurrentBuckets.isEmpty()
			|| !toggleConcurrentBuckets.isEmpty();
	}

	boolean isPulseTruthActive(ActivatableTargetBlockEntity owner) {
		if (pulseUntilGameTime <= 0L) {
			return false;
		}
		Level level = owner.getLevel();
		if (level == null) {
			return true;
		}
		return level.getGameTime() < pulseUntilGameTime;
	}

	void resetRuntimeTransientAfterLoad() {
		runtimeSimulatedSyncConcurrentBuckets.clear();
		toggleFrameStartContributors.clear();
		toggleSourcesTouchedInCurrentFrame.clear();
	}

	int recalculateSyncMaxStrengthAndSources() {
		int maxStrength = 0;
		syncSignalMaxSources.clear();
		for (Map.Entry<Long, Integer> entry : syncSignalStrengthBySource.entrySet()) {
			Long sourceSerial = entry.getKey();
			Integer strength = entry.getValue();
			if (sourceSerial == null || sourceSerial <= 0L || strength == null || strength <= 0) {
				continue;
			}
			if (strength > maxStrength) {
				maxStrength = strength;
				syncSignalMaxSources.clear();
				syncSignalMaxSources.add(sourceSerial);
				continue;
			}
			if (strength == maxStrength) {
				syncSignalMaxSources.add(sourceSerial);
			}
		}
		return maxStrength;
	}

	private boolean findToggleContribution(SourceKey sourceKey) {
		for (Map<SourceKey, ToggleConcurrentEntry> bucket : toggleConcurrentBuckets.values()) {
			ToggleConcurrentEntry entry = bucket == null ? null : bucket.get(sourceKey);
			if (entry != null) {
				return entry.contributes();
			}
		}
		return false;
	}

	private static void mergeSyncTruthFromBuckets(
		NavigableMap<TimeKey, Map<SourceKey, SyncConcurrentEntry>> buckets,
		Map<Long, Integer> targetStrengthBySource
	) {
		if (targetStrengthBySource == null) {
			return;
		}
		for (Map<SourceKey, SyncConcurrentEntry> bucket : buckets.values()) {
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (Map.Entry<SourceKey, SyncConcurrentEntry> sourceEntry : bucket.entrySet()) {
				SourceKey sourceKey = sourceEntry.getKey();
				SyncConcurrentEntry concurrentEntry = sourceEntry.getValue();
				if (sourceKey == null || sourceKey.sourceSerial() <= 0L || concurrentEntry == null) {
					continue;
				}
				int strength = SignalStrengths.clamp(concurrentEntry.strength());
				if (strength <= 0) {
					continue;
				}
				targetStrengthBySource.merge(sourceKey.sourceSerial(), strength, Math::max);
			}
		}
	}

	private static <V> boolean removeSourceFromConcurrentBuckets(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		SourceKey sourceKey
	) {
		if (buckets.isEmpty() || sourceKey == null) {
			return false;
		}
		boolean changed = false;
		List<TimeKey> emptyKeys = new ArrayList<>();
		for (Map.Entry<TimeKey, Map<SourceKey, V>> bucketEntry : buckets.entrySet()) {
			Map<SourceKey, V> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
				continue;
			}
			if (bucket.remove(sourceKey) != null) {
				changed = true;
			}
			if (bucket.isEmpty()) {
				emptyKeys.add(bucketEntry.getKey());
			}
		}
		for (TimeKey emptyKey : emptyKeys) {
			if (buckets.remove(emptyKey) != null) {
				changed = true;
			}
		}
		return changed;
	}

	private static <V> boolean hasExactConcurrentEntry(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		SourceKey sourceKey,
		TimeKey timeKey,
		V expectedValue
	) {
		boolean foundExpected = false;
		for (Map.Entry<TimeKey, Map<SourceKey, V>> bucketEntry : buckets.entrySet()) {
			Map<SourceKey, V> bucket = bucketEntry.getValue();
			if (bucket == null || bucket.isEmpty() || !bucket.containsKey(sourceKey)) {
				continue;
			}
			if (!java.util.Objects.equals(bucketEntry.getKey(), timeKey) || !java.util.Objects.equals(bucket.get(sourceKey), expectedValue) || foundExpected) {
				return false;
			}
			foundExpected = true;
		}
		return foundExpected;
	}

	private static <V> boolean removeConcurrentBucketsBefore(
		NavigableMap<TimeKey, Map<SourceKey, V>> buckets,
		TimeKey cutoffTimeKey
	) {
		if (buckets.isEmpty() || cutoffTimeKey == null) {
			return false;
		}
		List<TimeKey> staleKeys = new ArrayList<>();
		for (TimeKey timeKey : buckets.keySet()) {
			if (timeKey == null || timeKey.compareTo(cutoffTimeKey) < 0) {
				staleKeys.add(timeKey);
				continue;
			}
			break;
		}
		boolean changed = false;
		for (TimeKey staleKey : staleKeys) {
			if (buckets.remove(staleKey) != null) {
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * `sync` 来源桶中的单来源条目。
	 */
	record SyncConcurrentEntry(int strength, long seq) {
		SyncConcurrentEntry {
			strength = SignalStrengths.clamp(strength);
			seq = Math.max(0L, seq);
		}
	}

	/**
	 * `pulse` 来源桶中的单来源条目。
	 */
	record PulseConcurrentEntry(long untilGameTick, long seq) {
		PulseConcurrentEntry {
			untilGameTick = Math.max(0L, untilGameTick);
			seq = Math.max(0L, seq);
		}
	}

	/**
	 * `toggle` 来源桶中的单来源条目。
	 */
	record ToggleConcurrentEntry(boolean contributes, long seq) {
		ToggleConcurrentEntry {
			seq = Math.max(0L, seq);
		}
	}

	/**
	 * 供持久化层使用的 `sync` 快照。
	 */
	record PersistentSyncSnapshot(Map<Long, Integer> strengthBySource, Set<Long> maxSources) {
		PersistentSyncSnapshot {
			strengthBySource = strengthBySource == null ? Map.of() : Map.copyOf(strengthBySource);
			maxSources = maxSources == null ? Set.of() : Set.copyOf(maxSources);
		}
	}
}
