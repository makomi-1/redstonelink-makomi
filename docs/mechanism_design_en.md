# RedstoneLink Unified Mechanism Design

## 1. Document Purpose

This document does not explain RedstoneLink by splitting it into isolated sections such as "commands", "blocks", or "configs". Instead, it follows one internal mainline:

`topology write -> event dispatch -> online direct hit / cross-chunk takeover -> target batch scheduling -> core arbitration -> state observation / replay recovery`

This makes it easier to understand the arbitration model, cross-chunk mechanism, batching, replay recovery, and read/write control as one continuous design, instead of treating them as unrelated parts.

## 2. Unified Semantic Baseline

### 2.1 Node Semantics

- `triggerSource`: source node. It only produces trigger or sync signals.
- `core`: target node. It only receives signals and resolves the final state.
- The real write direction is always `triggerSource -> core`.
- Editing from the `core` perspective, GUI operations, and quick-link batch rewrites are all just different ways to build forward write plans around a `core`; the actual truth still falls back to `triggerSource -> core`.

### 2.2 Truth-Layer Responsibilities

- `LinkSavedData`: world-level source of truth for link topology, serial allocation, runtime revisions, and sync replay snapshots.
- `CrossChunkDispatchQueueSavedData`: persistent source of truth for cross-chunk pending dispatch.
- `ActivatableTargetBlockEntity`: runtime source of truth for a single `core` target. It performs the actual arbitration and output updates.
- `NodeSnapshotQueryService`: external read model. It does not create truth data. It only assembles identity, links, runtime state, revisions, and cross-chunk identity into readable snapshots.

In other words, topology truth, cross-chunk truth, target runtime truth, and external read models are four separate layers and are not written into the same structure.

## 3. Overall Architecture

```text
Edit entry / input entry
  -> write control and OCC validation
  -> LinkSavedData topology mutation
  -> InternalDispatchDeltaEvents incremental events
  -> LinkedTargetDispatchService
     -> target loaded: direct hit / enter target batching
     -> target not loaded: CrossChunkDispatchService takeover
        -> pending queue / force-load / resident
  -> CoreDispatchBatchScheduler
  -> ActivatableTargetBlockEntity
     -> sync / pulse / toggle concurrent buckets
     -> time-priority + fixed same-tick priority arbitration
  -> block state, redstone output, observation snapshot

Lifecycle attachment / chunk loading
  -> LinkNodeLifecycleDispatchEvents
  -> target attach replay / source attach replay / post-load self-heal
```

## 4. Arbitration Model

### 4.1 Core Idea

Arbitration happens on each individual `core` target, not on a global bus.
The target maintains three concurrent source buckets:

- `sync` bucket
- `pulse` bucket
- `toggle` bucket

Each event carries `EventMeta`. Its key time axis is `tick + slot`, with `seq` used to keep a stable order within the same time granularity.

### 4.2 Resolution Rules

- Layer 1: compare time first. Newer time overrides older time.
- Layer 2: within the same time granularity, use fixed priority: `SYNC > PULSE > TOGGLE`.
- Layer 3: within the same priority, use the newer `seq` to keep results deterministic.

### 4.3 How the Three Semantics Merge

- `sync`: not "last write wins". It aggregates source strengths and takes `max` as the final sync strength, while preserving the tied max-source list for observation.
- `pulse`: maintains an effective pulse window. As long as the window has not expired, the pulse truth stays active.
- `toggle`: merges by "base state + parity". Multiple toggles in the same time window do not produce unbounded dirty flips; they converge into an odd/even result.

### 4.4 Invalidation Semantics

- `triggerSource` chunk unload invalidation: only removes that source's `sync` contribution, and does not roll back `pulse/toggle` event semantics.
- Other `triggerSource` invalidation: removes that source's `sync/pulse/toggle` contributions and forces the target to recompute authority.

This keeps "synchronized state" and "event semantics" from being collapsed into one coarse invalidation rule.

## 5. Cross-Chunk Mechanism

### 5.1 Takeover Principle

`LinkedTargetDispatchService` turns "source serial -> target set" into real dispatch work:

- If the target chunk is loaded, dispatch directly to the target entity.
- If the target chunk is not loaded, hand it to `CrossChunkDispatchService` to decide whether it should take over.

### 5.2 Takeover Forms

Cross-chunk takeover is not a single mechanism. It is a three-layer combination:

- `pending queue`: write waiting events into a persistent queue until the target comes back.
- `force-load`: attach temporary tickets to the target chunk so it can be brought up and delivered sooner.
- `resident`: long-term holding for whitelisted targets, suitable for always-on cross-chunk infrastructure.

### 5.3 Queue Truth

`CrossChunkDispatchQueueSavedData` stores pending entries using `sourceType/sourceSerial/targetType/targetSerial/dispatchKind` as the key:

- It performs upsert per key and keeps only the latest intent, instead of stacking duplicate events forever.
- It assigns a monotonic version to each key and filters stale packets and expired retries with accepted/issued version guards.
- Expired entries are cleaned up by a min-heap instead of full per-tick scans.

### 5.4 Lifecycle Coordination

The current version does not rely on global `CHUNK_LOAD/CHUNK_UNLOAD` scans. Node instances report attach/detach actively, and `LinkNodeLifecycleDispatchEvents` consumes them within a server-tick budget:

- register online presence after node attach;
- try replay and queue release on target attach;
- optionally perform one `sync` recovery on source attach according to config;
- publish source invalidation on detach according to config.

The core goal is to move expensive chunk-state checks and recovery logic out of the hot path.

## 6. Batch Dispatch Mechanism

### 6.1 Why Batching Is Needed

Directly sending each event to the target entity causes two problems:

- the same target can be recomputed many times in the same tick;
- same-tick behavior becomes hard to unify across loaded direct hits, cross-chunk ready release, and lifecycle replay.

`CoreDispatchBatchScheduler` exists to collapse all of those paths into target-level batch commits.

### 6.2 Scheduling Rules

- `window=0`: still aligns to the current tick, but also allows one extra flush for same-tick late arrivals after `END_SERVER_TICK`.
- `window>=1`: bucket by target-level `dueTick` and form fixed-delay batches.
- Within the same `dueTick`:
  - same source + same kind keeps only the latest entry;
  - `ACTIVATION` is further bucketed by `activationMode`;
  - `TRIGGER_SOURCE_INVALIDATION` can override earlier normal events in the same bucket.

### 6.3 Batch Landing Point

When a batch really lands, it always calls `core.applyDispatchBatch(...)`:

- sort by `timeKey -> seq -> deltaPriority`;
- recompute `sync/pulse/toggle` truth once at the end of the batch;
- write derived state and observation state back only once.

So batching is not just about reducing call count. It is how the arbitration semantics for one target are fixed into a stable rule.

## 7. Replay Mechanism

### 7.1 Replay Is Not Input Playback

The project contains at least three replay-like concepts that are easy to confuse:

- `target chunk load replay`: after a target chunk loads, resend the latest real `sync` state from the source.
- `source attach replay`: after the source reattaches, resend one current `sync` state according to config.
- `InputPlaybackService`: runtime/bench waveform playback used to inject simulated input jobs.

The first two are lifecycle recovery. The last one is test/runtime input injection.

### 7.2 `sync` Replay Snapshots

`SyncReplaySourceBlockEntity` records the latest real `sync` dispatch snapshot:

- `signalStrength`
- original time key `tick/slot/seq`

This snapshot is stored both in the block entity and in `LinkSavedData`, so it can be used when the source chunk is offline but the target chunk has just loaded.

### 7.3 Target Chunk Load Replay

When the target attaches, `LinkNodeLifecycleDispatchEvents` will:

1. read the current source set linked to that target;
2. filter replay-eligible `sync` sources through `TriggerSourceEffectiveActivationPolicy`;
3. replay the latest real snapshot from each source instead of pretending the target-load moment was the event time.

This ensures replay restores the original source state, not a fake "new event on load".

### 7.4 Non-Blocking Rule

The replay path explicitly avoids blocking chunk access on critical startup paths:

- hot-path reads prefer non-blocking probes;
- if the chunk is not actually ready, defer/retry instead of waiting synchronously;
- startup must not be stalled in prepare/load because of replay.

This is a hard boundary in the current cross-chunk recovery design.

## 8. Read/Write Control

### 8.1 Read Control

The read side is centralized in `CurrentLinksPrivacyService`, with three core modes:

- `plain`: visible in plaintext
- `masked`: partially hidden according to the controlled list
- `hidden`: hidden as a whole

`NodeSnapshotQueryService` only assembles privacy-filtered current links, node identity, runtime snapshots, revisions, and cross-chunk identity.
Item tooltip/NBT snapshots use a separate item-snapshot path and are not identical to real-time player read permissions.

### 8.2 Write Control

The write side is decided by `LinkWriteControlService`:

- `full`: fully allowed
- `limited`: restrict per-write set size; exceeding it requires higher permission
- `readonly`: reject real writes
- `protected` list: hitting protected serials requires extra permission

### 8.3 Unified Write Pipeline

Real topology overwrite writes are unified through `LinkSetExecutionService`:

- validate source and target legality;
- perform write-control checks;
- update `LinkSavedData`;
- publish attach/detach deltas;
- sync node snapshots and item snapshots;
- return structured feedback.

quick-link, pairing, and bench submissions do not each implement their own low-level writer. They reuse this shared write pipeline as much as possible.

### 8.4 OCC Conflict Control

To prevent silent overwrite during "read first, write later", the project adds revision baselines:

- `graphRevision`: whole-graph revision
- `sourceRevision`: per-`triggerSource` revision
- `coreRevision`: per-`core` member-set revision

`LinkOccSupport` provides unified conflict checks:

- `triggerSource`-side submits compare `sourceRevision`;
- `core`-side submits compare `coreRevision`;
- on conflict, the submit is rejected and the user must reread, instead of silently replaying stale input.

So write control answers "are you allowed to write", while OCC answers "is the snapshot you based this on still valid". They have different responsibilities.

## 9. Unified Workflow Examples

### 9.1 Rewire Links

`pairing / quick-link / command`
-> write-control validation
-> OCC validation
-> `LinkSetExecutionService`
-> `LinkSavedData`
-> attach/detach delta
-> follow-up dispatch and snapshot sync

### 9.2 Online Trigger

`triggerSource` produces an event
-> `LinkedTargetDispatchService`
-> loaded targets are hit directly
-> `CoreDispatchBatchScheduler`
-> `ActivatableTargetBlockEntity`
-> final redstone output

### 9.3 Offline Recovery

target not loaded
-> `CrossChunkDispatchService` takeover
-> pending queue / force-load / resident
-> target attach
-> queue release or target chunk load replay
-> re-enter batching and arbitration

## 10. Design Trade-Off Summary

The point of this mechanism system is not "feature stacking". It is to split different problems into different layers:

- `LinkSavedData` solves topology truth.
- `CrossChunkDispatchQueueSavedData` solves offline-target recovery.
- `CoreDispatchBatchScheduler` solves same-target same-window batch merging.
- `ActivatableTargetBlockEntity` solves final arbitration.
- `CurrentLinksPrivacyService + LinkWriteControlService + LinkOccSupport` solve read control, write control, and concurrency conflict control.

After unification, the whole system can be summarized in one sentence:

RedstoneLink uses `triggerSource -> core` as the only real direction, uses target-side arbitration as the final convergence point, uses "cross-chunk takeover + lifecycle replay" for recovery, and uses "read control / write control / OCC" to keep external access inside one verifiable boundary.
