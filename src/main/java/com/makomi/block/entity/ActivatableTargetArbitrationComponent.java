package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EffectiveMode;
import com.makomi.block.entity.ActivatableTargetBlockEntity.TimeKey;
import java.util.Map;
import java.util.NavigableMap;

/**
 * `core` 目标端仲裁组件。
 * <p>
 * 统一维护 authority、同时间粒度优先级裁决与 `toggle` 轻量合并状态，
 * 但不直接持有结构真值；结构真值由并发来源桶组件提供。
 * </p>
 */
final class ActivatableTargetArbitrationComponent {
	private static final int PRIORITY_TOGGLE = 1;
	private static final int PRIORITY_PULSE = 2;
	private static final int PRIORITY_SYNC = 3;

	private TimeKey authorityTimeKey = TimeKey.minValue();
	private EffectiveMode authorityMode = EffectiveMode.NONE;
	private long authoritySeq;

	private TimeKey arbitrationTimeKey = TimeKey.minValue();
	private int arbitrationPriority = Integer.MIN_VALUE;

	private boolean toggleMergeInitialized;
	private boolean toggleMergeBaseActive;
	private boolean toggleMergeParity;

	TimeKey authorityTimeKey() {
		return authorityTimeKey;
	}

	void setAuthorityTimeKey(TimeKey authorityTimeKey) {
		this.authorityTimeKey = authorityTimeKey == null ? TimeKey.of(0L, 0) : authorityTimeKey;
	}

	EffectiveMode authorityMode() {
		return authorityMode;
	}

	void setAuthorityMode(EffectiveMode authorityMode) {
		this.authorityMode = authorityMode == null ? EffectiveMode.NONE : authorityMode;
	}

	long authoritySeq() {
		return authoritySeq;
	}

	void setAuthoritySeq(long authoritySeq) {
		this.authoritySeq = Math.max(0L, authoritySeq);
	}

	TimeKey arbitrationTimeKey() {
		return arbitrationTimeKey;
	}

	void setArbitrationTimeKey(TimeKey arbitrationTimeKey) {
		this.arbitrationTimeKey = arbitrationTimeKey == null ? TimeKey.of(0L, 0) : arbitrationTimeKey;
	}

	int arbitrationPriority() {
		return arbitrationPriority;
	}

	void setArbitrationPriority(int arbitrationPriority) {
		this.arbitrationPriority = arbitrationPriority;
	}

	boolean toggleMergeInitialized() {
		return toggleMergeInitialized;
	}

	void setToggleMergeInitialized(boolean toggleMergeInitialized) {
		this.toggleMergeInitialized = toggleMergeInitialized;
	}

	boolean toggleMergeParity() {
		return toggleMergeParity;
	}

	void setToggleMergeParity(boolean toggleMergeParity) {
		this.toggleMergeParity = toggleMergeParity;
	}

	boolean acceptByPriority(
		ActivatableTargetBlockEntity owner,
		ActivatableTargetConcurrentBucketComponent concurrentComponent,
		TimeKey eventTimeKey,
		int incomingPriority,
		EffectiveMode incomingMode,
		long incomingSeq
	) {
		TimeKey normalizedTimeKey = eventTimeKey == null ? TimeKey.of(0L, 0) : eventTimeKey;
		long normalizedSeq = Math.max(0L, incomingSeq);
		int timeCompare = normalizedTimeKey.compareTo(authorityTimeKey);
		if (timeCompare < 0) {
			return false;
		}

		beginArbitrationFrame(concurrentComponent, normalizedTimeKey);
		if (timeCompare == 0 && incomingPriority < getPriorityOfEffectiveMode(resolveAuthorityEffectiveMode(concurrentComponent, owner))) {
			return false;
		}

		if (timeCompare > 0 || incomingPriority > arbitrationPriority || incomingMode != authorityMode) {
			authorityMode = incomingMode == null ? EffectiveMode.NONE : incomingMode;
			authorityTimeKey = normalizedTimeKey;
			authoritySeq = normalizedSeq;
			arbitrationPriority = incomingPriority;
			toggleMergeInitialized = false;
			toggleMergeParity = false;
			return true;
		}
		if (normalizedSeq > authoritySeq) {
			authoritySeq = normalizedSeq;
		}
		arbitrationPriority = Math.max(arbitrationPriority, incomingPriority);
		return true;
	}

	void beginArbitrationFrame(ActivatableTargetConcurrentBucketComponent concurrentComponent, TimeKey timeKey) {
		if (timeKey == null || timeKey.equals(arbitrationTimeKey)) {
			return;
		}
		concurrentComponent.beginToggleFrame();
		arbitrationTimeKey = timeKey;
		arbitrationPriority = Integer.MIN_VALUE;
		toggleMergeInitialized = false;
		toggleMergeParity = false;
	}

	boolean applyToggleMerged(ActivatableTargetConcurrentBucketComponent concurrentComponent, boolean currentActive) {
		if (!toggleMergeInitialized) {
			toggleMergeInitialized = true;
			toggleMergeBaseActive = currentActive;
			toggleMergeParity = false;
		}
		toggleMergeParity = !toggleMergeParity;
		boolean nextToggleState = toggleMergeParity ? !toggleMergeBaseActive : toggleMergeBaseActive;
		concurrentComponent.setToggleState(nextToggleState);
		return nextToggleState;
	}

	void recomputeAuthorityFromConcurrentBuckets(
		ActivatableTargetConcurrentBucketComponent concurrentComponent,
		TimeKey fallbackTimeKey,
		long fallbackSeq,
		ActivatableTargetBlockEntity owner
	) {
		if (concurrentComponent.hasEffectiveSyncTruth()) {
			Candidate syncCandidate = resolveSyncCandidate(concurrentComponent);
			if (syncCandidate != null) {
				authorityMode = EffectiveMode.SYNC;
				authorityTimeKey = syncCandidate.timeKey();
				authoritySeq = syncCandidate.seq();
				return;
			}
		}
		Candidate syncCandidate = resolveSyncCandidate(concurrentComponent);
		Candidate pulseCandidate = resolvePulseCandidate(concurrentComponent, owner);
		Candidate toggleCandidate = resolveToggleCandidate(concurrentComponent);
		Candidate winner = pickWinner(syncCandidate, pulseCandidate, toggleCandidate);
		if (winner == null) {
			authorityMode = EffectiveMode.NONE;
			authorityTimeKey = fallbackTimeKey == null ? TimeKey.of(0L, 0) : fallbackTimeKey;
			authoritySeq = Math.max(0L, fallbackSeq);
			return;
		}
		authorityMode = winner.mode();
		authorityTimeKey = winner.timeKey();
		authoritySeq = winner.seq();
	}

	EffectiveMode resolveAuthorityEffectiveMode(
		ActivatableTargetConcurrentBucketComponent concurrentComponent,
		ActivatableTargetBlockEntity owner
	) {
		return switch (authorityMode) {
			case SYNC -> concurrentComponent.syncSignalMaxStrength() > 0 ? EffectiveMode.SYNC : EffectiveMode.NONE;
			case PULSE -> owner != null && concurrentComponent.isPulseTruthActive(owner) ? EffectiveMode.PULSE : EffectiveMode.NONE;
			case TOGGLE -> (concurrentComponent.toggleSnapshotRecorded() || concurrentComponent.toggleState())
				? EffectiveMode.TOGGLE
				: EffectiveMode.NONE;
			case NONE -> EffectiveMode.NONE;
		};
	}

	void normalizeAuthorityByTruth(
		ActivatableTargetConcurrentBucketComponent concurrentComponent,
		ActivatableTargetBlockEntity owner
	) {
		if (resolveAuthorityEffectiveMode(concurrentComponent, owner) != EffectiveMode.NONE) {
			return;
		}
		if (concurrentComponent.hasAnyConcurrentBuckets()) {
			recomputeAuthorityFromConcurrentBuckets(concurrentComponent, authorityTimeKey, authoritySeq, owner);
			if (resolveAuthorityEffectiveMode(concurrentComponent, owner) != EffectiveMode.NONE) {
				return;
			}
		}
		if (authorityMode != EffectiveMode.NONE) {
			authorityMode = EffectiveMode.NONE;
			authoritySeq = 0L;
		}
	}

	static int priorityOfActivationMode(ActivationMode mode) {
		return mode == ActivationMode.PULSE ? PRIORITY_PULSE : PRIORITY_TOGGLE;
	}

	static EffectiveMode effectiveModeOfActivationMode(ActivationMode mode) {
		return mode == ActivationMode.PULSE ? EffectiveMode.PULSE : EffectiveMode.TOGGLE;
	}

	private Candidate resolveSyncCandidate(ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		if (concurrentComponent.syncSignalMaxStrength() <= 0) {
			return null;
		}
		Candidate persistentCandidate = resolveSyncCandidateFromBuckets(concurrentComponent.syncConcurrentBuckets());
		Candidate runtimeCandidate = resolveSyncCandidateFromBuckets(concurrentComponent.runtimeSimulatedSyncConcurrentBuckets());
		return pickMoreRecentCandidate(persistentCandidate, runtimeCandidate);
	}

	private Candidate resolveSyncCandidateFromBuckets(
		NavigableMap<TimeKey, Map<ActivatableTargetBlockEntity.SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry>> buckets
	) {
		if (buckets == null || buckets.isEmpty()) {
			return null;
		}
		TimeKey timeKey = latestNonEmptyTimeKey(buckets);
		if (timeKey == null) {
			return null;
		}
		Map<ActivatableTargetBlockEntity.SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry> bucket = buckets.get(timeKey);
		long seq = 0L;
		if (bucket != null) {
			for (ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry entry : bucket.values()) {
				if (entry != null) {
					seq = Math.max(seq, entry.seq());
				}
			}
		}
		return new Candidate(EffectiveMode.SYNC, timeKey, seq, PRIORITY_SYNC);
	}

	private static TimeKey latestNonEmptyTimeKey(
		NavigableMap<TimeKey, Map<ActivatableTargetBlockEntity.SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry>> buckets
	) {
		if (buckets == null || buckets.isEmpty()) {
			return null;
		}
		for (Map.Entry<TimeKey, Map<ActivatableTargetBlockEntity.SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry>> entry : buckets.descendingMap().entrySet()) {
			Map<ActivatableTargetBlockEntity.SourceKey, ActivatableTargetConcurrentBucketComponent.SyncConcurrentEntry> bucket = entry.getValue();
			if (bucket != null && !bucket.isEmpty()) {
				return entry.getKey();
			}
		}
		return null;
	}

	private Candidate resolvePulseCandidate(
		ActivatableTargetConcurrentBucketComponent concurrentComponent,
		ActivatableTargetBlockEntity owner
	) {
		if (
			owner == null
				|| !concurrentComponent.isPulseTruthActive(owner)
				|| !concurrentComponent.pulseSnapshotRecorded()
		) {
			return null;
		}
		return new Candidate(
			EffectiveMode.PULSE,
			concurrentComponent.pulseEventTimeKey(),
			concurrentComponent.pulseEventSeq(),
			PRIORITY_PULSE
		);
	}

	private Candidate resolveToggleCandidate(ActivatableTargetConcurrentBucketComponent concurrentComponent) {
		if (!concurrentComponent.toggleSnapshotRecorded()) {
			return null;
		}
		return new Candidate(
			EffectiveMode.TOGGLE,
			concurrentComponent.toggleEventTimeKey(),
			concurrentComponent.toggleEventSeq(),
			PRIORITY_TOGGLE
		);
	}

	private static Candidate pickWinner(Candidate syncCandidate, Candidate pulseCandidate, Candidate toggleCandidate) {
		Candidate winner = pickMoreRecentCandidate(syncCandidate, pulseCandidate);
		return pickMoreRecentCandidate(winner, toggleCandidate);
	}

	private static Candidate pickMoreRecentCandidate(Candidate left, Candidate right) {
		if (left == null) {
			return right;
		}
		if (right == null) {
			return left;
		}
		int timeCompare = right.timeKey().compareTo(left.timeKey());
		if (timeCompare > 0) {
			return right;
		}
		if (timeCompare < 0) {
			return left;
		}
		if (right.priority() > left.priority()) {
			return right;
		}
		if (right.priority() < left.priority()) {
			return left;
		}
		return right.seq() > left.seq() ? right : left;
	}

	private static int getPriorityOfEffectiveMode(EffectiveMode mode) {
		if (mode == null) {
			return Integer.MIN_VALUE;
		}
		return switch (mode) {
			case SYNC -> PRIORITY_SYNC;
			case PULSE -> PRIORITY_PULSE;
			case TOGGLE -> PRIORITY_TOGGLE;
			case NONE -> Integer.MIN_VALUE;
		};
	}

	private record Candidate(EffectiveMode mode, TimeKey timeKey, long seq, int priority) {
		private Candidate {
			timeKey = timeKey == null ? TimeKey.of(0L, 0) : timeKey;
			seq = Math.max(0L, seq);
		}
	}
}
