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
     -> sync source buckets + pulse/toggle event snapshots
     -> time-priority + fixed same-tick priority arbitration
  -> block state, redstone output, observation snapshot

Lifecycle attachment / chunk loading
  -> LinkNodeLifecycleDispatchEvents
  -> target attach replay / source attach replay / post-load self-heal
```

## 4. Arbitration Model

### 4.1 Core Idea

Arbitration happens on each individual `core` target, not on a global bus.
The target no longer treats all three signal classes as the same kind of long-lived source contribution.
Instead, runtime truth is split into two layers:

- `sync`: a state signal that maintains source-level aggregated buckets.
- `pulse/toggle`: event signals that keep only target-local event-result snapshots.

Each event carries `EventMeta`. Its key time axis is `tick + slot`, with `seq` used to keep a stable order within the same time granularity.

### 4.2 Resolution Rules

- Layer 1: compare time first. Newer time overrides older time.
- Layer 2: within the same time granularity, use fixed priority: `SYNC > PULSE > TOGGLE`.
- Layer 3: within the same priority, use the newer `seq` to keep results deterministic.

### 4.3 How the Three Semantics Merge

- `sync`: a state signal. Within the same tick it aggregates source strengths and takes `max`, while across ticks only the latest sync frame remains effective; the tied max-source list is preserved for observation within that frame. It also has source-level invalidation, resend, and replay support, and is the default and recommended mainline for stable redstone machines.
- `pulse`: an event signal. It keeps a target-local effective pulse window. It overrides older event results, but a same-tick or later `sync` clears its persisted event result.
- `toggle`: an event signal whose semantic is "invert the current resolved target state". It keeps only the latest event result and no longer survives as a long-lived source-level fallback.
- `pulse/toggle` share one event domain: within the same tick only one event result is retained, with fixed priority `PULSE > TOGGLE`; a later event overrides an earlier one.
- When `sync` becomes invalid, it only falls back to remaining `sync`, and never re-exposes older `pulse/toggle` events that were already cleared.

### 4.4 Invalidation Semantics

- The default config is also the recommended model: `triggerSource` hard invalidation is always on; real source-offline cases such as offline / unlink / retire / delete remove only that source's `sync` contribution on targets.
- Chunk activity itself is not source logical invalidity: temporary chunk unload, temporary inactivity, or pure context detach does not automatically make the source invalid; the default recovery mainline is `sync` target-chunk-load replay.
- `pulse/toggle` do not participate in source-level relay/replay. Their relay paths stay disabled by default and remain only as compatibility or experimental entry points.

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

Tickets are keyed by the target center chunk, but this does not mean that only one physical chunk is loaded. Both `force-load` and `resident` currently call `addRegionTicket(..., radius=2, ...)` for the target center chunk. The intended center effect is to bring that chunk to `ENTITY_TICKING`; vanilla chunk loading may also bring up surrounding support chunks for the region ticket. Load cost should therefore be understood as "one center-chunk ticket plus vanilla support chunks", not as a strict single-chunk load. Changing the radius to `1` would only lower the center chunk's ticking requirement; it still would not guarantee that only one physical chunk is loaded.

Neighbor updates at chunk borders do not bypass this boundary. Cross-chunk neighbor fanout skips an unloaded neighbor chunk instead of force-loading it just because a block is on a chunk edge. If the neighbor chunk is actually updated, it was already loaded by a player, a ticket, or another loading source.

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
Under the default config, the actual cross-chunk recovery mainline is `sync` replay; `pulse/toggle` do not participate in that mainline.

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
For the default config, this is also the recommended relay/recovery path for `sync`; stable cross-chunk state chains do not need `pulse/toggle` relay as the mainline.

### 7.4 True Trigger for Automatic `sync` Replay

For `sync`, automatic replay is not triggered by "having resident / transient force-load" by itself. It is triggered only when a real offline `sync` propagation event has been generated:

- `force-load` / `resident` tickets only make the target chunk processable; what actually gets released / replayed is the offline `sync` event that was already queued or already generated.
- When a new link is created while the target is offline, the system attempts an attach replay first. If the target is still offline, the dispatch is converted into pending and replayed automatically later.
- If the source signal changes while the target is offline, including `0 -> 15`, `15 -> 0`, or any strength change, a new offline `sync` propagation event is generated. Once the target is brought up or comes back naturally, that update lands automatically.

### 7.5 Non-Blocking Rule

The replay path explicitly avoids blocking chunk access on critical startup paths:

- hot-path reads prefer non-blocking probes;
- if the chunk is not actually ready, defer/retry instead of waiting synchronously;
- startup must not be stalled in prepare/load because of replay.

This is a hard boundary in the current cross-chunk recovery design.

### 7.6 Runtime Filter Reconciliation

Send/receive filters are not another topology truth layer. They are a filtering layer attached to dispatch:

- a `send` filter serves only `triggerSource`;
- a `receive` filter serves only `core`;
- filters do not change the real write direction. They only decide whether a dispatch on the `triggerSource -> core` path is still allowed to continue.

Persistent placed-filter truth is maintained by `PlacedLinkFilterSavedData`, not by temporary "loaded chunk only" state:

- as long as a filter remains placed, it participates in evaluation through a persistent entry;
- its physical effect area is a cube centered on the filter block, with radius `8` on each `X/Y/Z` axis;
- runtime config changes and neighbor-input changes both trigger resampling and before/after comparison.

Filters also keep a separate config-snapshot path that is distinct from runtime truth:

- editing a handheld filter reads and writes the filter config snapshot stored in item NBT;
- placement restores that snapshot into the block entity and `PlacedLinkFilterSavedData`, while breaking the block writes the current effective config back into the dropped item;
- item tooltips show the same config snapshot, including the node set. It is only a config carrier, not another link-truth layer.

Current runtime compensation only applies to `sync`:

- previously allowed, now blocked: publish one `sync` invalidation and remove the old contribution;
- previously blocked, now allowed: resend one `sync` from the current replay/snapshot;
- `pulse/toggle` do not replay historical events and only affect later dispatches.

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

### 8.4 Filter Config Writes and Quick Link

Filter editing must stay separate from "rewire links":

- saving from the filter GUI, saving from the handheld filter editor, and quick-linking into a filter are all filter-config writes, not link-graph writes;
- for a handheld filter, the write target is the item-side config snapshot; for a placed filter, the write target is the block-entity config plus persisted filter truth;
- the written object is the filter snapshot (`serialExpression/nodeSetMode/signalThresholdSource/fixedSignalThreshold/signalMode`), not the `triggerSource -> core` topology;
- when quick-link hits a filter, it only overwrites `serialExpression` and keeps the rest of the filter config unchanged.
- because the write target is a config snapshot, filters support a closed loop of "edit in hand -> place into runtime truth -> break and keep config -> place again and restore", without touching link-graph revisions.

So the player-facing interaction is similar, but the permission/OCC boundary is different:

- hit a node: still go through `LinkSetExecutionService + LinkWriteControlService + LinkOccSupport`;
- hit a filter: validate against `server.command.permissionLevel`, skip link write control, and do not participate in link OCC;
- that difference exists because filter-config writes do not mutate link-graph revisions and only change filtering truth.

### 8.5 OCC Conflict Control

To prevent silent overwrite during "read first, write later", the project adds revision baselines:

- `graphRevision`: whole-graph revision
- `sourceRevision`: per-`triggerSource` revision
- `coreRevision`: per-`core` member-set revision

`LinkOccSupport` provides unified conflict checks:

- `triggerSource`-side submits compare `sourceRevision`;
- `core`-side submits compare `coreRevision`;
- on conflict, the submit is rejected and the user must reread, instead of silently replaying stale input.

So write control answers "are you allowed to write", while OCC answers "is the snapshot you based this on still valid". They have different responsibilities.

### 8.6 Smart Glasses and the `visualize` Observation Gate

`Quick Link Tool` `visualize` no longer uses "holding the QLT" as its display anchor. Observation is now carried by `Smart Glasses`:

- far/near overlays and visualize link rendering only appear when Smart Glasses are worn; this is a client-side observation gate, not another topology truth layer;
- add/remove displayed objects and `Shift + B` clear are still operation paths, so they still require an empty main hand; add/remove also require standing with `Ctrl` held, while rendering itself does not, which lets players keep watching links while holding other tools;
- once an object is added, the client keeps a local snapshot and refreshes it incrementally through `sourceRevision/coreRevision + runtimeNodeVersion`;
- aim-at tooltips and through-wall lines only read the local cache and do not send per-frame network queries. Network cost is concentrated in "object added" and periodic incremental refresh batches.

### 8.7 Item-Inventory Semantics of Smart Node Container

`Smart Node Container` is a standalone item-inventory system, not a GUI wrapper around the old aggregate stack idea:

- it stores full `ItemStack` snapshots instead of pure serial groups, so node-side item state stays with the container;
- it only accepts `core/triggerSource/repeater`, and additionally persists current placement type, auto-sort state, and its display state;
- on main-hand placement, it ejects the first matching item of the currently selected placement type and tries to place it; auto-sort only changes inventory organization, not node truth;
- if the item entity is truly destroyed, contained nodes do not drop out first. They retire recursively through the existing retire coordinator, preventing "allocated but no longer owned" ghost serials.

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
