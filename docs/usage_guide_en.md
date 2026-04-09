# Rules
This document has been checked against the current source code, config templates, and bench scripts, and keeps only the instructions that are still valid for the current version.

# Guide
The content below is ordered as "common player workflows -> admin/ops -> diagnostics/automation", and split into grouped sections. Earlier sections are the ones most users should read first.

## Quick Navigation

- First time using the mod and just want to make links work: go straight to `I. Quick Start for Players`.
- Want serial overlays and client-side visuals: go straight to `II. Client Display and Observation`.
- Want commands, batch maintenance, or server-owner operations: go straight to `III. Commands and Server Administration`.
- Want permissions, privacy, write control, or cross-chunk strategy: read `IV. Permissions and Access Control` and `V. Cross-Chunk and Persistence` first.
- Want diagnostics, hotspot capture, regression, or performance tests: go straight to `VI. Diagnostics and Runtime Tools` and `VII. Automated Tests and Bench`.

## I. Quick Start for Players

- Best for: players using the mod for the first time and only wanting everyday linking plus state checks.
- Recommended reading order: `Quick Link Tool` -> `Linked Sync Linker` -> `State Panel Tool` -> `Batch Serial Input Format for Commands/GUI`.

### Quick Link Tool
- Item name: `Quick Link Tool`.
- In the current version, the usable workflow revolves around the serial cache. `channel` mode is only a reserved entry point. If you try to switch into it, the game will say it is a future expansion, so it is not part of the current usable feature set.
- Basic interaction:
1. Sneak and right-click with an empty offhand: open the Quick Link Tool cache editor.
2. Left-click a valid link node: collect that node into the current cache.
3. Right-click a valid link node while standing: apply the current cache to the hit node.
4. Middle-click (same binding as vanilla `pick item`): clear the current cache.
5. Quick Link mode key: default `B`. Only the mode-switch entry remains right now; `channel` mode is still reserved for future expansion.
- Collect rules:
1. After collecting, the tool automatically switches the current serial-cache type based on the hit node: hit `core` -> cache type becomes `core`; hit `triggerSource` -> cache type becomes `triggerSource`.
2. If the mode is still serial mode and the cache type matches, the collect action appends incrementally and deduplicates automatically.
3. If the cache type changes, the tool rebuilds the current serial cache first, then writes the newly collected result.
4. Collecting the same serial again does not duplicate it; the action bar will say that the serial is already in the cache.
- Apply rules:
1. The real write direction is always `triggerSource -> core`.
2. If the current cache type matches the type of the hit node, the direction is invalid and the apply is rejected.
3. Applying a `core` cache to a `triggerSource` overwrites that `triggerSource`'s whole target set with the cached `core` set.
4. Applying a `triggerSource` cache to a `core` overwrites every cached `triggerSource` so that each one links only to the currently hit `core`.
5. If the serial cache is empty, right-click apply fails immediately with feedback.
6. Applying a batch of `triggerSource` cache entries to a `core` is also limited by the server-side `server.maxTargetsPerSetLinks`.
7. Quick Link Tool apply is also governed by server-side write control. If the target is read-only, limited, or blocked by the protected list, the current unified feedback is "insufficient permission".
- Clear rules:
1. Middle-click clear only clears the serial cache and channel cache.
2. Clearing keeps the current mode and current serial-cache type. It does not forcibly reset back to the default type.
- UI and feedback:
1. The GUI allows manual editing of the serial cache. The channel input box is only a reserved display field and does not participate in the current real apply flow.
2. The latest collect/apply/clear/mode-limit message is shown in the action bar, in the same area used by the `B` mode-switch hint.
3. The GUI-side serial cache input length is controlled by client config `client.quickLinkSerialCacheMaxLength`, default `1024`.
4. On real apply, the server still performs another length validation for the cache expression using `server.command.linkSet.maxInputLength`.
5. While holding the Quick Link Tool and targeting a valid link node, an outline is shown: `core` is bright blue, `triggerSource` is bright orange.
- Recommended usage order:
1. Hold the Quick Link Tool in the main hand.
2. Left-click to collect a batch of `core` or `triggerSource` serials into the cache.
3. Right-click while standing to apply the cache to a valid target in the legal direction.
4. Press middle mouse to clear the cache if you want to start over.
- Crafting recipe:
1. `Redstone Link Component + Stick + Stick -> Quick Link Tool`
2. Pattern:
   `  C`
   ` S `
   `S  `
   `C = redstonelink:redstone_link_component`
   `S = minecraft:stick`

### Linked Sync Linker
- Item name: `Linked Sync Linker`.
- Basic interaction is the same as the other linkers:
1. Sneak and right-click with an empty offhand: open the pairing UI.
2. Right-click while standing with an empty offhand: toggle between `15/0` and dispatch the current sync strength to linked `core` nodes.
- Behavior:
1. The Linked Sync Linker always acts as a `triggerSource`, and the real write direction is still `triggerSource -> core`.
2. It uses the same dispatch path as the Linked Sync Lever. It synchronizes the current strength, not `TOGGLE/PULSE` activation semantics.
3. The item texture follows the current state: state `0` shows the off texture, state `15` shows the on texture.
- Crafting recipe:
1. `Linked Sync Lever + Lever -> Linked Sync Linker`
2. Pattern:
   `BL`
   `B = redstonelink:link_sync_lever`
   `L = minecraft:lever`

### Linked Sync Lever
- Its interaction semantic is `sync`: every toggle aligns linked targets to the lever's current state (`ON -> ON`, `OFF -> OFF`).
- This alignment reuses the sync dispatch path and does not depend on accumulated `toggle` counts.
- The Linked Sync Lever always forwards `ON=15` and `OFF=0`; a sync emitter inherits external input strength (`0~15`) and forwards that value.
- If one sync emitter receives multiple redstone inputs at once, it only forwards the current maximum input strength (`max`).
- If the emitter stays powered and the maximum input strength does not change, it will not resend. It dispatches again only when the powered state changes or the max strength changes.
- All trigger types (buttons / levers / emitters) send one instantaneous signal when a trigger event happens; they do not continuously send while staying active.

### State Panel Tool
- Item name: `State Panel Tool`.
- Basic interaction:
1. Hold it in the main hand and right-click: open the state panel.
2. The panel provides four action buttons: `Subscribe / Refresh / Record / Clear`. `Record` is only a reserved entry right now and immediately says it will open later.
3. The subscription type can switch between `core` and `triggerSource`.
- Input and validation rules:
1. The input box uses the same serial-expression grammar as the pairing GUI: `N` and `A:B`, separated by `/`.
2. New subscriptions only accept allocated and non-retired nodes.
3. Nodes blocked by privacy read control cannot be added as new subscriptions; the server returns a direct privacy-denied message.
4. Duplicate subscriptions that already exist are skipped automatically and do not take extra slots.
5. The max input length still reuses `server.command.linkSet.maxInputLength`, default `1024` characters.
- Refresh and display rules:
1. After a successful new subscription, the server automatically pushes back one latest snapshot. No extra manual refresh is needed.
2. Manual `Refresh` is throttled by `server.statePanel.refreshHz`, default `5 Hz`; if you spam it too quickly, the server returns a throttling message.
3. If an old subscription is now blocked by privacy read control, the row stays in the list but the status column shows `Hidden (privacy read control)`.
4. `Clear` removes all subscriptions stored on the current tool and refreshes the list to empty immediately.
- Config keys:
1. `server.statePanel.refreshHz`: overall refresh throttle for the state panel, default `5`.
2. `server.statePanel.maxSubscriptions`: total subscription cap (`core + triggerSource` combined), default `50`.

### Pairing Input Box Hint Behavior
- If the input box starts empty, it no longer steals focus automatically, so the placeholder hint stays visible.
- If the input box starts non-empty, it still auto-focuses so you can continue editing existing content.
- The input box is now a real multiline input box. Text fills top-to-bottom and can scroll inside the box when it becomes long.
- Input-box height is now linked to the button row and status-hint layout; future height changes should move the lower layout automatically.
- The placeholder hint is now short-form to avoid excessive visual blocking.
- Hover the input box to see the full rule and examples.
- The max input length is controlled by client config `client.pairingInputMaxLength` (default `1024`).
- When the box is focused, normal Enter inserts a new line, and `Ctrl+Enter` submits.

### Pairing UI and Tooltip Display Rules
- Applies to: current links shown in the pairing GUI, pairing-GUI hover tooltips, and hover tooltips on pairable items.
- Display format: always a structured expression using `N` and `A:B`, separated by `/`.
- Example: `1:100/901:1903/2500`.
- Long text: when the content exceeds available space, append `(+n)`, where `n` is the number of omitted serials.
- Note: this is a structured view. It does not guarantee that the user's original segmented input formatting is preserved.

### Batch Serial Input Format for Commands / GUI
- Applies to: `/redstonelink link set ... <targets>`, `/redstonelink node activate triggerSource <source_serials> [toggle|pulse]`, and the pairing GUI input box.
- Unified separator: `/`.
- Unified grammar: supports single values `N` and ranges `A:B`, and they can be mixed.
- Example: `1:100/1/1000/901:1903/` (the trailing `/` is optional).
- GUI multiline compatibility: you may split the expression across lines; the client folds line breaks into `/` before sending, and server parse semantics stay unchanged.
- Activation command mode: in `activate triggerSource`, `toggle|pulse` is parsed as a separate keyword after the batch serials, for example `1:100/901:1903 pulse`.
- Whitelist batch-overwrite order: only the standard order `<serials> resident confirm` is supported now; `confirm resident` is no longer accepted as a swapped form.
- Duplicate entries: duplicate input is allowed, but the system warns and deduplicates before writing.
- Invalid entries: the whole input is rejected and returns the invalid fragments; if the quantity exceeds the cap, the server returns the matching limit message from config.

### Semantic Alignment: `role` (`source/target`) and `type` (`triggerSource/core`)
- `role` only expresses semantic direction: `source` / `target`. Command input is case-insensitive.
- `type` only expresses node type: `triggerSource` (maps to `LinkNodeType.TRIGGER_SOURCE`) / `core` (maps to `LinkNodeType.CORE`). Command input is case-insensitive.
- Recommended standard wording in docs and examples: use `source/target` for `role`, and `triggerSource/core` for `type`.
- Config entries (`crosschunk.whitelist.sourceTypes/targetTypes` and `crosschunk.preset.<name>.sources/targets`) also accept only `triggerSource/core`; old aliases such as `button` and `trigger_source` are no longer accepted.

### Optimistic Concurrency Protection in the Pairing UI
- The pairing GUI now carries revision baselines:
1. When you open the pairing UI, the server-side current-link snapshot also carries `graphRevision/sourceRevision`.
2. `triggerSource`-side submit validates that source's own `sourceRevision`.
3. `core`-side submit validates the whole-graph `graphRevision`, preventing silent overwrite when several sources edit the same `core`.
- Conflict feedback rules:
1. If the `triggerSource` itself was changed by another operation, the UI returns a `source revision conflict` message with expected and current revisions.
2. If the whole link graph changed after a `core`-side screen was opened, the UI returns a `graph revision conflict` message with expected and current revisions.
3. The current conflict strategy is "reject this submit and require reopening the screen". The server does not silently replay stale input in the background.
- Snapshot baseline note:
1. `NodeSnapshotQueryService` now always attaches revision baselines to current-link snapshots.
2. The actual active revision conflict gate is pairing-GUI submit for now, but the same baseline is also the foundation for quick-link, state panel, and later editors.

### Linked Redstone Dust Core Redstone Behavior
- Linked Redstone Dust Cores, including transparent variants, only activate the block they are attached to. They do not spread into redstone networks in other directions.
- Linked Redstone Dust Cores do not participate in vanilla redstone-dust connectivity or calculations.

### Player Interaction Override for Receiver Blocks
- `Linked Redstone Core`, `Linked Redstone Core (Transparent)`, `Linked Redstone Dust Core`, and `Linked Redstone Dust Core (Transparent)` no longer support direct player right-click activation. Normal right-click now only opens the pairing GUI when the access gate allows it.
- If you want to observe receiver-block state, use actual link-driven behavior. Receiver blocks no longer provide a "manually click once to activate" shortcut.

### Linker Trigger Path Alignment (2026-03-12)
- Both linkers (`redstonelink_toggle_linker` / `redstonelink_pulse_linker`) now reuse the same dispatch path used by `triggerSource` nodes.
- Linker triggers now behave like buttons / levers: if the target is not loaded, they also enter the cross-chunk persistent queue / force-load scheduling logic.
- Interaction updates:
1. Buttons, emitters, and levers still use standing right-click with the main hand to trigger; linkers still use sneak-right-click to open pairing.
2. `Linked Redstone Core`, `Linked Redstone Core (Transparent)`, `Linked Redstone Dust Core`, and `Linked Redstone Dust Core (Transparent)` no longer respond to direct player right-click activation. Normal right-click opens pairing directly when access rules allow it.
3. Opening pairing on placed blocks now requires sneaking by default. If you want to remove that gate, set `interaction.requireSneakToOpenPairing=false`.

### Signal Contention Model
- The mod uses a "same-tick merge + time-order arbitration" model: events for the same target within the same tick are merged by rule, and class priority is fixed as `sync > pulse > toggle`.
- Within the same-tick class, `sync` aggregates by strength (`max`); `pulse` uses the delay window; `toggle` merges by parity.
- The current state is determined by the latest effective event. It does not require a source to keep sending continuously.
- Compared with many wireless redstone mods, RedstoneLink is still driven primarily by event time ordering, and only introduces strength aggregation inside the `sync` class.
- Because of that, concurrent race scenarios can end with different final states and reproducibility boundaries from other models.

## II. Client Display and Observation

- Best for: players who want to read serial overlays, external text, and client-side rendering effects clearly.
- Recommended reading order: `Serial overlay for Linked Redstone Dust Core and Linked Redstone Core (client)` first, then the other two sections as needed.

### Serial Overlay for Linked Redstone Dust Core and Linked Redstone Core (Client)
- `Linked Redstone Dust Core` and `Linked Redstone Dust Core (Transparent)` render decimal serial text outside the attached face, using thousands separators.
- `Linked Redstone Core` and `triggerSource` emitters render the same serial overlay system slightly above the top edge of the block.
- When the crosshair hits a node at close range, the center of the screen can show three lines of information on a dark background; this only works within 8 blocks:
1. `[Item Name] Serial`
2. Activation status (`ON` / `OFF`)
3. Current links (structured expression using `N` / `A:B`, separator `/`, with `(+n)` for overflow)
- The far overlay format is `serial only`, for example `12,345`.
- Different node types use fixed colors: `core` is cyan, `triggerSource` is orange; Linked Redstone Dust Cores currently use the `core` color.
- Client hotkey: default `K`; each press cycles one mode: `far -> near -> far+near -> off`.
- Client config file: `config/redstonelink-client.properties`
1. `client.serialOverlayMode`: default overlay mode (`far/near/both/off`, default `far`)
2. `client.serialOverlayMaxDistance`: far-overlay distance in blocks (`4~256`, default `24`)
3. `client.serialOverlayFontScale`: overlay font scale (`0.50~3.00`, default `1.00`)
4. `client.serialOverlayToggleKey`: default toggle key (recommended `key.keyboard.k`, but plain letters such as `K` are also accepted)

### Client Far-Overlay Rendering Commands (Standalone Root)
- Command root: `/rlclient` (client-local command root; it does not enter the server-side `/redstonelink` tree).
- Far-overlay mode switch:
1. `/rlclient display far_overlay occluded`: far-overlay text is blocked by blocks and does not render through walls.
2. `/rlclient display far_overlay see_through`: far-overlay text renders through blocks.
- Config persistence: the command writes `client.serialOverlayFarSeeThrough` into `config/redstonelink-client.properties`.
- Default: `client.serialOverlayFarSeeThrough=false` (no see-through by default).

### Translucent Rendering for Emitters and Linked Redstone Core Blocks (Client)
- Placed emitters (`toggle/pulse/sync`) and `Linked Redstone Core` blocks use a translucent render layer.
- This is only a client-side visual adjustment. It does not change redstone behavior or server-side logic.

## III. Commands and Server Administration

- Best for: admins, server owners, or players who need batch maintenance of links and nodes.
- Recommended reading order: `New Command Entrypoints` -> `Batch Command Confirm Rules (overwrite-type)` -> `Other Admin Commands`.

### New Command Entrypoints
- Batch activation: `/redstonelink node activate triggerSource <source_serials> [toggle|pulse]`
- Batch whitelist overwrite: `/redstonelink crosschunk whitelist set <role> <type> <serials> confirm`
- Batch retire: `/redstonelink node retire batch <type> <serials> confirm`

### Simplified Link Command Surface
- Removed commands: `/redstonelink pair ...`, `/redstonelink pair_node ...`
- Remaining link-write entrypoints: `/redstonelink link add/remove/set`

### Batch Command Confirm Rules (Overwrite-Type)
- All overwrite-style batch commands require a second `confirm` before they actually take effect.
- `link set`: append `confirm` when the parsed `targets` count is greater than `1`.
- `crosschunk whitelist set`: always requires `confirm`.
- `retire batch`: always requires `confirm`.
- `place fill`: anything not run with `force` is blocked, and the original command must end with `confirm`, for example `/redstonelink place fill <from> <to> <block> confirm`.
- `confirm`, `resident`, `toggle`, and `pulse` are all parsed as independent command nodes and should use standard lowercase spelling.

### Other Admin Commands
1. `node get/list`: query node information and state.
   - For online `core` nodes, `node get` also prints one runtime-snapshot line: `configuredMode/effectiveMode/active/resolvedStrength/output/maxSources`.
   - `maxSources` is the current tied max-strength source set, sorted ascending, useful for auditing equal-strength ties.
   - `core` state persists structural truth values: `syncSourceStrengths`, `pulseEpoch+pulseUntilTick`, `toggleState`; `active/output` are derived and rebuilt after restart.
2. `link get`: query the current target list linked to a source node.
3. `audit summary text/csv`: output audit summary data.
4. `place setblock dry_run/force`: preview or force single-block placement; `place fill` now only keeps `force/confirm`.

### Default Link-Set Target Limit
- Config key: `server.maxTargetsPerSetLinks`
- Default: `1024`
- Range: `1~4096` (values outside the range are clamped automatically)
- Applies to: `/redstonelink link set ... <targets>`
- Rule: the client no longer rejects by a local count cap first; the final quantity decision is now always made by the server.

### Permission Level for the `/redstonelink` Root Command
- Config file: `config/redstonelink-server.properties`
- New config key: `server.command.permissionLevel`
- Default: `0`
- Range: `0~4` (values outside the range are clamped automatically)
- Scope: the whole `/redstonelink` command tree, including all subcommands
- The matching automated-test cases need a Fabric Loader environment (server or client startup).

## IV. Permissions and Access Control

- Best for: server owners who need to decide who can see, who can modify, and who wants copy-ready recommended configs.
- Recommended reading order: `Permission Overview and Recommended Matrix`, then look back at privacy and write-control details as needed.

### Current-Link Privacy Commands and Read Rules
- Server config (`config/redstonelink-server.properties`):
1. `server.currentLinksPrivacy.mode`: `hidden|masked|plain`
2. `server.currentLinksPrivacy.overlayResponsePermissionLevel`: minimum permission level (`0~4`) required for the server to send any near-overlay packet containing current links / final IO; default `0`
3. `server.currentLinksPrivacy.viewPermissionLevel`: permission level required to view controlled links in `masked` mode (`0~4`)
4. `server.currentLinksPrivacy.managePermissionLevel`: permission level required to manage `link privacy current_links mask` commands (`0~4`)
- Privacy-list commands (`/redstonelink link privacy current_links mask`):
1. `add <type> <serial>`: add a masked entry
2. `remove <type> <serial>`: remove a masked entry
3. `list <type>`: list masked entries for that type
4. `set <type> <serials> [confirm]`: batch overwrite using `N`, `A:B`, and `/`; `confirm` is an independent trailing keyword and is required for overwrite
- `type` only supports `triggerSource|core`.
- Read behavior:
1. `hidden`: current links are hidden completely (shown as empty / `-`)
2. `plain`: no privacy filtering is applied to current links
3. `masked`: masked source nodes are permission-gated; masked target nodes are filtered item-by-item in the pairing GUI and near overlay, while item snapshot data is not filtered by this rule
4. Command reads (`/redstonelink node get`, `/redstonelink link get`), pairing GUI, and near overlay all share the same privacy read rule and no longer expose raw links that bypass privacy control
5. Adding new State Panel subscriptions also reuses the same source-node read control; nodes you may not read cannot be newly subscribed. If an old subscription already exists but the current player no longer has read permission, that row stays in the list but its status shows the hidden state.

### Link Write-Control Modes and Protected Lists
- Server config (`config/redstonelink-server.properties`):
1. `server.linkWriteControl.mode`: `full|limited|readonly`
2. `server.linkWriteControl.limited.permissionLevel`: permission level (`0~4`) required to bypass the max-set-size limit in `limited` mode
3. `server.linkWriteControl.limited.maxSetSize`: max allowed target set size after the write in `limited` mode
4. `server.linkWriteControl.protected.permissionLevel`: permission level (`0~4`) required when the write touches protected entries
5. `server.linkWriteControl.protected.managePermissionLevel`: permission level (`0~4`) required to manage protected-list commands
- Protected-list commands (`/redstonelink link write_control protected`):
1. `add <type> <serial>`: add an entry to the protected list (only allocated and non-retired serials are accepted)
2. `remove <type> <serial>`: remove an entry from the protected list
3. `list <type>`: list protected entries for that type
4. `set <type> <serials> [confirm]`: batch overwrite using `N`, `A:B`, `/`; overwrite requires trailing `confirm`
- Write interception rules:
1. `readonly`: rejects all link writes
2. `limited`: enforced by the final target-set size after `set`, not by the number of incremental additions/removals
3. When a protected list is hit, both the source node and affected target nodes must pass `protected.permissionLevel`
4. In `limited`, low-permission players are not blocked globally. They are only rejected when the protected list is hit (either the source or the affected targets); otherwise they may still write, subject to the size cap.
5. `full`: the protected list is ignored in the current implementation and writes pass directly

### Permission Overview and Recommended Matrix
- This section gathers the major permission keys for read / write / commands and gives recommended values for common server scenarios.
- Note: pairing-GUI submit currently goes through `redstonelink link set ...`, so it is controlled by both the `/redstonelink` root permission and the write-control strategy.

### Permission Key Quick Reference
| Config Key | Default | Scope |
| --- | --- | --- |
| `server.command.permissionLevel` | `0` | Permission gate for the `/redstonelink` root command (includes `link set`, so it also affects GUI submit) |
| `server.command.otherPermissionLevel` | `2` | Permission gate for the other command group: `node activate`, `node retire`, `node get/list`, `link get`, `place`, `audit` |
| `server.currentLinksPrivacy.mode` | `masked` | Current-link read mode: `hidden/masked/plain` |
| `server.currentLinksPrivacy.overlayResponsePermissionLevel` | `0` | Minimum permission needed for the server to send near-overlay packets |
| `server.currentLinksPrivacy.viewPermissionLevel` | `2` | Permission needed to view masked current links |
| `server.currentLinksPrivacy.managePermissionLevel` | `2` | Permission for `link privacy current_links mask` management commands |
| `server.linkWriteControl.mode` | `limited` | Write-control mode: `full/limited/readonly` |
| `server.linkWriteControl.limited.permissionLevel` | `2` | Permission needed to bypass the limited-mode max-set-size cap |
| `server.linkWriteControl.limited.maxSetSize` | `64` | Maximum allowed target set size after a limited-mode write |
| `server.linkWriteControl.protected.permissionLevel` | `2` | Permission needed when the protected list is hit |
| `server.linkWriteControl.protected.managePermissionLevel` | `2` | Permission for `write_control protected` management commands |
| `server.command.linkSet.maxInputLength` | `1024` | Max raw input length in characters for `link set` targets |
| `server.command.activate.batchMaxSerials` | `1024` | Batch source cap for `node activate` |
| `server.command.retire.batchMaxSerials` | `1024` | Batch count cap for `node retire batch` |
| `server.command.privacy.currentLinksMask.maxSetSerials` | `1024` | Batch cap for `link privacy ... mask set` |
| `server.command.writeControl.protected.maxSetSerials` | `1024` | Batch cap for `link write_control protected set` |
| `server.command.crosschunk.whitelist.maxSetSerials` | `1024` | Batch cap for `crosschunk whitelist set` |
| `crosschunk.command.enabled` | `true` | Whether the `crosschunk` command tree is enabled |
| `crosschunk.command.permissionLevel` | `2` | Permission level for the `crosschunk` command tree |
| `server.command.rateLimit.actorGroup.other.baseCapacity` | `6` | Base capacity for the `other` command group per actor |
| `server.command.rateLimit.actorGroup.other.stepPerLevel` | `4` | Per-permission-level capacity increment for the `other` command group |

### Command Rate Limiting (Phase 2)
- Goal: stop high-frequency spam / write storms while avoiding a shared bucket that lets one player monopolize the whole allowance for the same permission tier.
- Protection layers (all of them must pass):
1. Global window capacity (`Global`)
2. Permission-tier window capacity (`Tier`)
3. Per-actor window capacity (`Actor`)
4. Per-actor + per-command-group capacity (`ActorGroup`)
- Phase-2 coverage:
1. `link` write group: `link add/remove/set`, `link write_control protected add/remove/list/set`
2. `crosschunk` command group, including `whitelist` and `preset`
3. `other` group: `node activate`, `node retire` (including `batch`), `place`, `audit`, `node get/list`, `link get`
- Key config entries (`config/redstonelink-server.properties`):
1. `server.command.rateLimit.enabled`: master switch
2. `server.command.rateLimit.windowTicks`: window length in ticks, range `1~2000`
3. `server.command.rateLimit.global.capacity`: global capacity, range `1~200000`
4. `server.command.rateLimit.tier.baseCapacity` / `stepPerLevel`: capacity formula for the permission tier
5. `server.command.rateLimit.actor.baseCapacity` / `stepPerLevel`: capacity formula for one actor
6. `server.command.rateLimit.actorGroup.linkRw.baseCapacity` / `stepPerLevel`: per-actor capacity formula for the `link` group
7. `server.command.rateLimit.actorGroup.crosschunk.baseCapacity` / `stepPerLevel`: per-actor capacity formula for the `crosschunk` group
8. `server.command.rateLimit.actorGroup.other.baseCapacity` / `stepPerLevel`: per-actor capacity formula for the `other` group
- Capacity formula: `capacity = base + permissionLevel * step`, where permission level is `0~4`.
- Current defaults are estimated from roughly 50 players of the same permission tier concurrently spamming `link add`: `global=3072`, `tier.base=600`, `tier.step=400`.
- Over-limit feedback is unified as `Too many requests. Please try again later.` No threshold details are leaked.

### Recommended Config Matrix by Scenario
| Scenario | Recommended Config Highlights | Cross-Chunk Guidance |
| --- | --- | --- |
| Single-player development / owner self-test with everything open | `server.command.permissionLevel=2`; `server.currentLinksPrivacy.mode=plain`; `server.linkWriteControl.mode=full`; `crosschunk.command.permissionLevel=2` | Prefer debugging: `crosschunk.queue.enabled=true`, `crosschunk.forceLoad.enabled=true`, `crosschunk.forceLoad.mode=all` for fast validation of cross-chunk links |
| Small co-op server where normal players may edit links via GUI/commands, but at limited scale | `server.command.permissionLevel=0`; `server.currentLinksPrivacy.mode=masked`; `server.currentLinksPrivacy.viewPermissionLevel=2`; `server.linkWriteControl.mode=limited`; `server.linkWriteControl.limited.maxSetSize=8~32`; `server.linkWriteControl.limited.permissionLevel=2`; `server.linkWriteControl.protected.permissionLevel=2`; `server.linkWriteControl.protected.managePermissionLevel=3`; `crosschunk.command.permissionLevel=2` | Prefer production behavior: `crosschunk.queue.enabled=true`, `crosschunk.forceLoad.enabled=true`, `crosschunk.forceLoad.mode=whitelist`; only keep key infrastructure in `crosschunk whitelist`, and only give `resident` to always-on facilities |
| Public server where normal players may do basic interaction but may not change links by command | `server.command.permissionLevel=2`; `server.currentLinksPrivacy.mode=masked`; `server.linkWriteControl.mode=readonly`; `server.linkWriteControl.protected.managePermissionLevel=3~4`; `crosschunk.command.permissionLevel=3~4` | Reduce the cross-chunk surface: keep `queue`, set `forceLoad.mode=whitelist`; if player-side operations are unnecessary, set `crosschunk.command.enabled=false` or keep it admin-only |
| High-secrecy server where link information is hidden by default and only management may inspect or modify | `server.command.permissionLevel=2` or higher; `server.currentLinksPrivacy.mode=hidden` or `masked` + fully controlled lists; `server.currentLinksPrivacy.viewPermissionLevel=3~4`; `server.linkWriteControl.mode=limited/readonly`; `server.linkWriteControl.protected.permissionLevel=3~4`; `server.linkWriteControl.protected.managePermissionLevel=4`; `crosschunk.command.permissionLevel=4` | Minimize exposure: use `crosschunk.forceLoad.mode=whitelist` and narrow `crosschunk.whitelist.sourceTypes/targetTypes`; only the management team should maintain whitelist and `resident` entries |

### Practical Scenario Recommendations
- If you want normal players to submit links from the pairing GUI, `server.command.permissionLevel` cannot be higher than their permission level, usually `0`.
- If you want normal players to do small rewires but forbid large overwrites, prefer `server.linkWriteControl.mode=limited` together with `server.linkWriteControl.limited.maxSetSize`.
- If you want key nodes to stay protected from ordinary players, add those serials into `write_control protected` and raise `protected.permissionLevel`.

## V. Cross-Chunk and Persistence

- Best for: server owners or maintainers who need to manage cross-chunk links, resident whitelists, and replay strategy.
- Recommended reading order: `Cross-Chunk Strategy Matrix (2026-03-12)` first, then look up whitelist, resident, replay, and retry-backoff details as needed.

### Forced Sync for Retire and Whitelist Cleanup
- All retire paths now go through one unified retire entrypoint, whether they come from commands, batches, events, or block flow.
- When a node is retired, the matching `type+serial` is force-synchronized out of both `source/target` whitelists immediately, and resident marks are cleaned up at the same time.
- This change only improves consistency. It does not change retire-command semantics or cross-chunk scheduling boundaries.

### Offline `resident` Setup and Delayed Activation (Overrides Older Rules)
- `crosschunk whitelist add ... resident` and `crosschunk whitelist set ... resident confirm` now support offline serial setup, useful for carried items or pre-configuration flows.
- While offline, the node does not renew resident tickets and does not trigger force-load; resident behavior becomes active automatically again after the node comes back online.
- A `linker` is only a serial carrier, not an online node entry. Having only a linker serial does not create resident force-load behavior.
- The normal whitelist entrypoints without `resident` keep the existing `allocated and non-retired` validation semantics.

### Cross-Chunk Whitelist Resident Labels
- A linker cannot be used as a resident `triggerSource`.
- Once resident is enabled, the source or target chunk will be force-loaded on the next server tick, without needing a trigger event.
- `whitelist add <role> <type> <serial> resident`: add a whitelist entry and set `resident=on`.
- `whitelist add <role> <type> <serial>`: write by strict command semantics and set `resident=off` explicitly.
- `whitelist set <role> <type> <serials> confirm`: batch overwrite and set `resident=off`.
- `whitelist set <role> <type> <serials> resident confirm`: batch overwrite and set `resident=on`.
- `resident` depends strictly on whitelist membership and cannot exist independently. `whitelist list` prints the resident list as extra output.
- Resident chunk loading only uses this mod's own ticket type and stays isolated from force-load systems used by other mods.

### Cross-Chunk Takeover Notifications (2026-03-13)
- The source side is notified only when force-load takeover actually takes effect. Persistent-queue forwarding does not emit a notification.
- Config keys (`config/redstonelink-server.properties`):
1. `crosschunk.notify.enabled`: master switch for cross-chunk notifications, default `true`
2. `crosschunk.notify.mode`: notification mode, `simple` / `detailed`, default `simple`
- Display rules:
1. Output is unified as `type + serial`.
2. Only force-loaded targets are shown, as per-line `type + serial` lists.
3. `simple` shows at most 3 entries per type, `detailed` shows at most 50 entries per type, and overflow is shown as `(+n)`.

### Cross-Chunk Commands
- Command root: `/redstonelink crosschunk`
1. `whitelist add <role> <type> <serial>`: add a runtime whitelist entry
2. `whitelist remove <role> <type> <serial>`: remove a runtime whitelist entry
3. `whitelist list <role> <type>`: show the whitelist for one role+type pair
   - Output structure: one separator line `------------------------------`, then one header line for `role/type/count`
   - Detail rows: one serial per line, with fields `serial/online/resident/dimension/chunk` (`online` appears before `resident`)
   - `chunk` is displayed as `chunkX,chunkZ`; if the node is offline or has no online instance, `dimension` and `chunk` are shown as `-`
4. `whitelist clear <role> <type>`: clear the whitelist for one role+type pair
5. `preset list`: list readonly preset names
6. `preset show <name>`: show the `sources/targets` details of one preset

### Cross-Chunk Strategy Matrix (2026-03-12)
- Semantic constraints:
1. `triggerSource` can only act as a source and maps to `LinkNodeType.TRIGGER_SOURCE`
2. `core` can only act as a target and maps to `LinkNodeType.CORE`
- Core config keys (`config/redstonelink-server.properties`):
1. `crosschunk.queue.enabled`: master switch for the persistent cross-chunk dispatch queue
2. `crosschunk.queue.defaultTtlTicks`: common TTL for queued dispatch entries
3. `crosschunk.queue.maxPendingEntries`: hard cap for total pending queue entries (default `100000`, range `1~2000000`); when the cap is reached, only new keys are rejected, while existing keys can still be updated
4. `crosschunk.dispatch.maxPerTick`: max number of queue entries handled per tick (default `500`, range `1~20000`)
5. `crosschunk.syncSignalPersistent`: whether `sync` gets unlimited persistent fallback delivery (default `false`)
6. `crosschunk.syncSignalTtlTicks`: TTL for `SYNC` events when `crosschunk.syncSignalPersistent=false`
7. `crosschunk.syncTargetChunkLoadReplay.enabled`: whether to replay `sync` when the target chunk loads (default `true`)
8. `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst`: whether to try replay immediately in the same `CHUNK_LOAD` tick first, default `true`
9. `crosschunk.syncSourceAttachReplay.enabled`: whether to enable `sync-only replay` when a `triggerSource` reattaches, default `false`
10. `crosschunk.directBatching`: batch mode for loaded-direct dispatch. `off` = all loaded-direct `sync/toggle/pulse` are immediate; `queued_only` = only async loaded `sync` enters batching; `all_direct` = loaded-direct `sync/toggle/pulse` plus async loaded `sync` all enter target-level batching together. Default `all_direct`.
11. `crosschunk.dispatch.batchWindowTicks`: fixed target-level batch delay in ticks (`0~2`); `0` = current-tick alignment plus same-tick late flush after `END_SERVER_TICK`, `1` = fixed `1 tick` delay, `2` = fixed `2 tick` delay. Default `0`.
12. `crosschunk.activation.pulse.relay.enabled`: whether normal TTL relay is enabled for `pulse`, default `false`
13. `crosschunk.activation.pulse.ttlTicks`: TTL for normal `pulse` relay
14. `crosschunk.activation.pulse.persistentExperimental`: whether experimental unlimited `pulse` delivery is enabled, default `false`
15. `crosschunk.activation.toggle.relay.enabled`: whether normal TTL relay is enabled for `toggle`, default `false`
16. `crosschunk.activation.toggle.ttlTicks`: TTL for normal `toggle` relay
17. `crosschunk.activation.toggle.persistentExperimental`: whether experimental unlimited `toggle` delivery is enabled, default `false`
18. `crosschunk.triggerSourceContextDetachInvalidation.enabled`: whether to enable `triggerSource` `soft/context-detach invalidation`, which removes only `sync` contribution on targets; default `false`
19. `triggerSource` hard invalidation is always on and no longer has its own config key; source offline / unlink / retire / delete and other non-context-detach invalidations still remove `toggle/pulse/sync` contributions from targets
20. `crosschunk.forceLoad.enabled`: master switch for force-load
21. `crosschunk.forceLoad.mode`: `all` / `whitelist`
22. `crosschunk.forceLoad.ticketTicks`: lifetime of force-load tickets in ticks
23. `crosschunk.forceLoad.maxPerTick`: max force-load operations per tick
24. `crosschunk.forceLoad.maxPerSourcePerTick`: max force-load operations per source per tick
25. `crosschunk.whitelist.sourceTypes` / `crosschunk.whitelist.targetTypes`: types allowed to participate in the whitelist
26. `crosschunk.preset.<name>.sources` / `crosschunk.preset.<name>.targets`: readonly presets in `type:serial` form
- Matrix when the target is not loaded:
1. `queue=true` + `forceLoad=false`: persistent queue only
2. `queue=true` + `forceLoad=true` + `mode=whitelist`: persistent queue + whitelist-only force-load
3. `queue=true` + `forceLoad=true` + `mode=all`: persistent queue + force-load for everything
4. `queue=false` + `forceLoad=false`: skip directly
5. `queue=false` + `forceLoad=true` + `mode=whitelist`: whitelist-only force-load; non-whitelisted targets are skipped
6. `queue=false` + `forceLoad=true` + `mode=all`: force-load everything without needing the whitelist
7. Stale-entry guard: same-key entries reject older versions monotonically; expired entries are dropped directly by TTL
8. `sync` does not use unlimited persistent fallback by default (`crosschunk.syncSignalPersistent=false`); normal recovery for unloaded targets is target-chunk-load replay (`crosschunk.syncTargetChunkLoadReplay.enabled=true`)
9. If `crosschunk.syncSignalPersistent=true` is enabled, `sync` waits indefinitely as a latest-state pending entry and redelivers after the target recovers; this is better treated as a fallback, not the default main recovery path
10. With `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst=true`, replay is attempted in the same `CHUNK_LOAD` tick first; only if the target is still not truly ready does it fall back to the next-tick local retry queue
11. If `crosschunk.syncTargetChunkLoadReplay.enabled=false`, the `CHUNK_LOAD` replay path is fully skipped; if you still want recovery after target restoration, then consider enabling `crosschunk.syncSignalPersistent=true`
12. With `crosschunk.syncSourceAttachReplay.enabled=true`, a reattached `triggerSource` resends one `sync-only replay` to all linked `core` nodes based on the current source state; default is off to avoid duplicating real post-placement input dispatch
13. With `crosschunk.directBatching=queued_only`, only loaded `sync` hit by async / queue paths enters batching; loaded-direct `sync/toggle/pulse` still apply immediately
14. With `crosschunk.directBatching=all_direct`, loaded-direct `sync/toggle/pulse` and async loaded `sync` all enter unified target-level batching; this is the current default
15. `crosschunk.directBatching=all_direct` + `crosschunk.dispatch.batchWindowTicks=0` is the closest fixed-delay setup to "no extra tick delay": everything still goes through unified batching, but it stays aligned to the current tick and allows same-tick late flush after `END_SERVER_TICK`
16. `crosschunk.directBatching=off` + `crosschunk.dispatch.batchWindowTicks=0` is the closest setup to immediate original behavior: loaded-direct `sync/toggle/pulse` do not enter direct batching and apply as soon as they hit the target; `batchWindowTicks=0` is still useful for async invalidation and similar batchable items
17. `pulse` does not relay cross-chunk by default; only when `crosschunk.activation.pulse.relay.enabled=true` is enabled does it buffer by TTL; with `persistentExperimental=true`, it may wait indefinitely and replay one pulse after target load
18. `toggle` does not relay cross-chunk by default; only when `crosschunk.activation.toggle.relay.enabled=true` is enabled does it buffer by TTL; with `persistentExperimental=true`, it may wait indefinitely and replay by net parity after target load
19. `pulse/toggle` ready-drain and force-load hits now also reuse `crosschunk.dispatch.batchWindowTicks` and enter target-level batching; lifecycle replay remains `sync-only` and does not replay historical `pulse/toggle` events
20. `triggerSource` soft/context-detach invalidation only affects `sync` and is off by default; with `crosschunk.triggerSourceContextDetachInvalidation.enabled=true`, a source that only detaches from context still removes its `sync` contribution from targets and triggers recomputation
21. `triggerSource` hard invalidation is always on; offline / unlink / retire / delete and other non-context-detach invalidations continue to remove that source's `toggle/pulse/sync` contributions from targets

### `sync` Target-Chunk-Load Replay Switch
- Goal: decide whether target-chunk `CHUNK_LOAD` automatically replays the source side's latest real sync state.
- Config (`config/redstonelink-server.properties`):
1. `crosschunk.syncTargetChunkLoadReplay.enabled`: whether target-load sync replay is enabled, default `true`
2. `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst`: whether one replay attempt is made immediately in the `CHUNK_LOAD` tick first, default `true`; only if the target is still not truly ready does it fall back to the next-tick local retry queue
- Behavior:
1. When enabled, if the source side has a replay snapshot after the target chunk loads, the server replays `sync` using the source side's original `tick/slot/seq`, instead of faking the target-load moment as the latest event
2. With `immediateAttemptFirst=true`, recovery is attempted in the same `CHUNK_LOAD` tick first; only if the target is still not actually ready is it deferred to the next tick
3. With `immediateAttemptFirst=false`, conservative mode is used: always delay one tick first and then retry locally
4. When disabled, `CHUNK_LOAD` no longer automatically replays `sync`; new-link flows such as attach / replace remain unaffected

### Cross-Chunk Persistent Retry Backoff
- Goal: control when persistent pending dispatch begins to slow down retries and what interval each slowdown stage uses.
- Config (`config/redstonelink-server.properties`):
1. `crosschunk.retry.stage1.maxAttempts` / `crosschunk.retry.stage1.intervalTicks`: max failed attempts and retry interval for stage 1
2. `crosschunk.retry.stage2.maxAttempts` / `crosschunk.retry.stage2.intervalTicks`: max failed attempts and retry interval for stage 2
3. `crosschunk.retry.stage3.maxAttempts` / `crosschunk.retry.stage3.intervalTicks`: max failed attempts and retry interval for stage 3
4. `crosschunk.retry.stage4.intervalTicks`: retry interval for stage 4, used after stage-3 limits are exceeded
5. `crosschunk.retry.dropThreshold`: only affects the drop cap for non-persistent events and does not participate in staged retry for persistent pending entries
- Behavior:
1. Persistent `SYNC_SIGNAL` entries are unlimited pending entries and keep retrying for the long term.
2. The default staged table is `1~99 -> 1 tick`, `100~499 -> 5 tick`, `500~999 -> 20 tick`, `>=1000 -> 100 tick`.
3. After each failure, persistent pending entries recompute `nextEligibleTick` from the current failure-count stage instead of relying on the old two-stage backoff threshold.
4. Once the target chunk fires `CHUNK_LOAD`, waiting persistent pending entries for that chunk immediately leave the wait window and can retry in the next scheduling round.
5. Unlimited pending entries no longer emit generic `warn/error` threshold logs by default; only the first transition into stage 2 or higher emits one notice.

## VI. Diagnostics and Runtime Tools

- Best for: maintainers who need to diagnose issues, reproduce problems, observe runtime state, or profile hotspots.
- Recommended reading order: `Node State Trace and Sampling Commands` -> `Minimal Runtime Loop for Old-World TP Stutter Diagnosis` -> `Capture Hotspots with JFR (recommended)`.

### Node State Trace and Sampling Commands
- Goals:
1. Provide one unified instant-state read path and historical sampling entrypoint for `core` and `sync triggerSource`.
2. Let bench, live diagnostics, and future State Panel work reuse the same runtime-state dataset.
- Preconditions:
1. Keep `server.command.nodeTrace.enabled=true` in `config/redstonelink-server.properties`; it is enabled by default.
2. If post-load self-heal for `core` or `triggerSource` is enabled, it is recommended to keep node trace enabled as well so you can observe states before and after self-heal.
- Command root:
1. `/redstonelink node trace mount <type> <serials> [every] [capacity]`
2. `/redstonelink node trace latest <type> <serials>`
3. `/redstonelink node trace read <type> <serial> [limit]`
4. `/redstonelink node trace unmount <type> <serials>`
5. `/redstonelink node trace list`
- Parameter notes:
1. `type` always uses `triggerSource|core`.
2. `serials` supports the batch serial format `N` and `A:B`, separated by `/`; `read` still takes only one `serial`.
3. `every` is the sampling period in ticks, default `1`, max `1200`.
4. `capacity` is ring-buffer size, default `128`, max `4096`.
5. `limit` is the number of most recent samples to read, default `10`, max `256`.
- Current support:
1. `core`: online and offline snapshots are supported.
2. `triggerSource`: supports recognized online `pulse/toggle/sync` emitters; after mount, even if the node goes offline temporarily, the server can still output an offline snapshot based on the known `traceKind`.
- Behavior:
1. `mount/latest/unmount` now support batch serials; single-serial behavior stays the same as before.
2. `mount` writes one current snapshot immediately, so you do not need to wait one more tick for the first sample.
3. Historical samples are read in latest-first order.
4. The phase-2 per-tick assertions of `RunFunctionalCase` first convert `read` results into forward time order, then crop them by `mountRef/anchorRef` baseline and align them within a finite window.
5. `read` is intentionally not batched this round to avoid flooding chat with many nodes' histories at once.
6. Initial sampling time granularity is server `tick`, with `slot=0`.
7. `list` only shows samplers mounted inside the current server process; after restart they must be mounted again.
- Examples:
```powershell
/redstonelink node trace mount core 101 1 256
/redstonelink node trace latest core 101
/redstonelink node trace read core 101 20
/redstonelink node trace mount triggerSource 202/205/208 4 128
/redstonelink node trace latest triggerSource 202:220
/redstonelink node trace unmount triggerSource 202/205/208
/redstonelink node trace list
```

### Input Playback Commands
- Goals:
1. Inject reproducible test input into groups of `triggerSource/core` nodes by command, for bench, stress tests, and runtime diagnosis.
2. The first version uses in-memory jobs only. It does not write persistence and does not replace the phase-2 `pulse/toggle` simulation abstraction.
- Preconditions:
1. Keep `server.command.input.enabled=true` in `config/redstonelink-server.properties`; it is enabled by default.
2. If post-load self-heal for `triggerSource` is enabled, keeping input playback enabled is recommended for reproducing and diagnosing leftover-input issues.
- Current support:
1. `triggerSource`: supports emitter input simulation; runtime state uses the greater one of real input and simulated input.
2. `core`: only supports temporary `sync` injection through runtime buckets and does not write into real source buckets.
3. Waveforms: only `square` and `custom`.
- Core boundaries:
1. All input jobs live only inside the current server process; they are lost after server restart.
2. `core pulse/toggle` contributor-key abstraction is not implemented this round, so the command side does not support it.
3. Target nodes must be allocated, non-retired, and currently online or inside loaded chunks.
4. Runtime simulated state does not persist across restart; temporary `core` sync input, runtime replay for sync emitters, and emitter `POWERED` state are rebuilt from real input after later loads.
- Command root:
1. `/redstonelink input start triggerSource square <serials> <period_ticks> [high_ticks] [high_power] [low_power] [phase_ticks] [total_ticks]`
2. `/redstonelink input start triggerSource custom <serials> <sequence> [phase_ticks] [total_ticks]`
3. `/redstonelink input start core sync square <serials> <period_ticks> [high_ticks] [high_power] [low_power] [phase_ticks] [total_ticks]`
4. `/redstonelink input start core sync custom <serials> <sequence> [phase_ticks] [total_ticks]`
5. `/redstonelink input stop <job_id>`
6. `/redstonelink input list`
7. `/redstonelink input clear`
- Parameter notes:
1. `serials` uses the same batch serial format as existing commands: `N`, `A:B`, separated by `/`.
2. `serials` and `sequence` are both parsed as one token ending at a space, so later integer arguments continue through the normal command tree correctly.
3. In `square`, `period_ticks` is the full period and `high_ticks` is the number of high-level ticks.
4. `custom` `sequence` supports both per-tick bit/hex strings such as `0101` / `f0f0`, and explicit power lists such as `15/0/7/0`.
5. `phase_ticks` applies a whole-wave phase shift.
6. `total_ticks=0` means infinite duration until manual `stop` or `clear`.
- Common examples:
```powershell
/redstonelink input start triggerSource square 101:132 4 2 15 0 0 200
/redstonelink input start triggerSource custom 201/202 0101 0 80
/redstonelink input start core sync square 901:964 4 1 15 0 0 0
/redstonelink input start core sync custom 1201/1202 15/0/15/0 2 160
/redstonelink input list
/redstonelink input stop 1
/redstonelink input clear
```
- Behavior:
1. If there are offline targets, unloaded-chunk targets, or unsupported nodes at job start, the whole start is rejected and returns the relevant serials.
2. `list` shows current jobs, endpoint type, waveform summary, target count, elapsed ticks, and target serials.
3. `clear` stops all input jobs on the current server and clears related runtime input state.

### Command Switches and Post-Load Self-Heal Switches
- Bench mode:
1. With `server.command.benchmarkMode.enabled=true`, bench-related commands work more easily with console or RCON.
2. This switch no longer controls `input` and `node trace`.
- Independent command switches:
1. `server.command.input.enabled=true`: enables input-playback commands and runtime services; default `true`
2. `server.command.nodeTrace.enabled=true`: enables node-trace commands and sampling services; default `true`
- Post-load self-heal:
1. `server.runtime.loadResync.core.enabled=true`: enables asynchronous display self-heal after `core` loads from disk; recommended to keep enabled
2. `server.runtime.loadResync.triggerSource.enabled=true`: enables asynchronous input self-heal after `triggerSource` emitters load from disk; recommended to keep enabled
3. `server.runtime.loadResync.maxRetry=40`: max extra retries when the chunk is not ready; `0` means try only once and never requeue
4. Both self-heal paths consume chunks via non-blocking `getChunkNow(...)`; if the retry budget is still exceeded, one warning log is written so abandoned tasks can be investigated
5. Recommended combinations:
   Keep `server.command.input.enabled=true` when `triggerSource` self-heal is enabled.
   Keep `server.command.nodeTrace.enabled=true` when `core` or `triggerSource` self-heal is enabled.

### Layout Advice for High-Frequency `sync triggerSource` Driving a Linked Redstone Core Array
- Applies to: high-frequency `sync` triggerSources driving dense `core` arrays, especially `Linked Redstone Core` arrays.
- Conclusion:
1. Even when the total number of chunks covered by the `core` array is unchanged, the `triggerSource`'s position relative to chunk borders can significantly affect performance.
2. In one `2x2 chunks` Linked Redstone Core array, an observed rough order is: `center chunk boundary` is heaviest, `outer edge chunk boundary` is second, `inner edge still inside the same chunk as the main affected array part` is lightest.
3. The main cost increase is not in cross-chunk dispatch or block-entity sync, but in the vanilla `setBlock -> neighbor update -> chunk/tracker/light` chain.
- Layout advice:
1. Place high-frequency `sync triggerSource` inside a non-border region of one chunk within the array whenever possible.
2. Avoid placing the `triggerSource` on the center split line of a `2x2` array.
3. If you must stay near a boundary, prefer an inner-edge position that is still inside the same chunk as the main affected array part, not the outer edge or center boundary.
4. For Linked Redstone Core arrays, boundary sensitivity of `triggerSource` placement is usually more important than small differences such as whether one extra block-entity client sync is performed.

### Notes for Large High-Concurrency Linked Redstone Dust Core Networks
- It is not recommended to activate large Linked Redstone Dust Core networks with high concurrency at the same time, because short bursts of high latency may occur.
- The current version has removed the old `top-face ACTIVE client sync throttle` config entry; it is no longer a supported tuning knob.

### `sync` Fanout Counter Log Switch
- Goal: control whether `sync_fanout_slow` also prints fanout counters (`delta/total`).
- Config (`config/redstonelink-server.properties`):
1. `crosschunk.diag.runtime.enabled`: master switch for slow-path runtime logs
2. `crosschunk.diag.runtime.warnThresholdMs`: slow-path threshold in milliseconds
3. `crosschunk.diag.runtime.fanoutCounters.enabled`: whether fanout counters are printed, default `false`
- Behavior:
1. When `crosschunk.diag.runtime.fanoutCounters.enabled=false`, `sync_fanout_slow` only prints the base slow-log fields.
2. When the switch is `true`, the log also prints fanout counters (`fanoutRequest/centerNotify/neighborNotify/crossChunkSkip/fanoutDedupHit` as `Delta/Total`).

### Lithium Diagnostics and Strict-Mode Cleanup
- Removed command: `/redstonelink diag lithium`
- Removed config key: `compat.lithiumStrictMode` (`config/redstonelink-server.properties` no longer supports it)

### Minimal Runtime Loop for Old-World TP Stutter Diagnosis
- Goal: determine whether the following three slow paths are being hit:
1. `CHUNK_LOAD/CHUNK_UNLOAD` lifecycle projection
2. `ENTITY_UNLOAD` retire path and pending-retire tick handling
3. `sync fanout` (`triggerSource -> core` fanout dispatch)
- Config (`config/redstonelink-server.properties`):
1. `crosschunk.diag.runtime.enabled=true`
2. `crosschunk.diag.runtime.warnThresholdMs=25`
- Log keywords (`latest.log` / `debug.log`):
1. `[DiagRuntime] chunk_lifecycle_slow`
2. `[DiagRuntime] retire_entity_unload_slow`
3. `[DiagRuntime] pending_retire_tick_slow`
4. `[DiagRuntime] sync_fanout_slow`
5. `[DiagRuntime] link_saveddata_type_mismatch` (old-world type-compat scan that counts type strings dropped by strict parsing)

### Capture Hotspots with JFR (Recommended)
- Best for: reproducing "old-world TP causes ticks to freeze or jitter heavily".
1. Find the process: `jcmd -l` (on Windows, if JAVA is not in PATH, use `%JAVA_HOME%\\bin\\jcmd.exe -l`)
2. Start a 120-second capture: `jcmd <PID> JFR.start name=rl_diag settings=profile filename=run/logs/rl_diag.jfr duration=120s`
3. Enter the game and reproduce the TP stutter.
4. The recording is written automatically at the end; to stop early, run `jcmd <PID> JFR.stop name=rl_diag`
5. Open `run/logs/rl_diag.jfr` in JMC and inspect Hot Methods / Call Tree for the top hotspot.

### Capture Hotspots with Spark (Optional)
- Best for: a quick flame graph when spark is installed on the server.
1. Run on the server: `/spark profiler --timeout 120`
2. Reproduce the TP stutter.
3. Inspect the top hotspot and main-thread stack from the returned spark link.

### Direct Spark Report Analysis
- Best for:
1. bench results that only contain a spark link, where you want to read metrics directly without clicking through the viewer UI
2. scripting spark results into later analysis, issue review, or performance-threshold governance
- Stable entrypoints:
1. Lightweight JSON summary: `https://spark.lucko.me/<code>?raw=1`
2. Full JSON: `https://spark.lucko.me/<code>?raw=1&full=1`
3. Raw binary: `https://spark-usercontent.lucko.me/<code>`
- Notes:
1. `?raw=1` uses spark's JSON service and is suitable for direct reads of summary data such as `metadata`, `TPS`, `MSPT`, memory, and CPU.
2. `?raw=1&full=1` returns the full profile/heap JSON; for profiler results, this is more suitable for scripted analysis than the viewer page.
3. `spark-usercontent` returns the raw spark binary actually used by the viewer; profiler `Content-Type` is usually `application/x-spark-sampler`.
4. `path=` queries are more reliable for objects / arrays, for example `?raw=1&path=$.metadata.platformStatistics.mspt.last1m` or `?raw=1&full=1&path=$.threads[0]`.
5. Direct `path=` reads of a pure scalar are currently unstable on the server side and may return an empty body or server error. The safer approach is to query the parent object and extract locally.
- PowerShell examples:
```powershell
$code = 'C9ErRWnBhQ'

# 1. Read the lightweight summary
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1" |
  Select-Object -ExpandProperty Content

# 2. Read only the MSPT summary object instead of a raw scalar
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1&path=$.metadata.platformStatistics.mspt.last1m" |
  Select-Object -ExpandProperty Content

# 3. Download the full profiler JSON
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1&full=1" `
  -OutFile ".\\run\\profiles\\spark-$code-full.json"

# 4. Download the raw spark profiler binary directly
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark-usercontent.lucko.me/$code" `
  -Headers @{ Accept = 'application/x-spark-sampler,application/x-spark-heap,application/x-spark-health' } `
  -OutFile ".\\run\\profiles\\spark-$code.sparkprofile"
```
- `spark2json` fallback:
1. Official repository: `https://github.com/lucko/spark2json`
2. Shortest Docker usage: `docker run -it --rm ghcr.io/lucko/spark2json node cli.js <code>`
3. If `?raw=1&full=1` changes behavior, or if you need to parse `.sparkprofile` offline, switch back to `spark2json` first.
- Current bench guidance:
1. For `TPS/MSPT/memory/CPU`, prefer `?raw=1`
2. For hotspot trees, thread trees, or local secondary aggregation, prefer `?raw=1&full=1`
3. For long-term archival, also save the `.sparkprofile` downloaded from `spark-usercontent`

## VII. Automated Tests and Bench

- Best for: development, regression testing, functional verification, and performance baseline collection.
- Recommended reading order: `Automated Test Commands` first, then move into the three Bench sections as needed.

### Automated Test Commands
1. `./gradlew test`: stable-core suite (default gate)
2. `./gradlew testIntegration`: tests tagged `integration`
3. `./gradlew testClient`: tests tagged `client`
4. `./gradlew testSlow`: tests tagged `slow`
5. `./gradlew testExtended`: stable core + extended test set
6. `./gradlew testCoverageReport`: generate XML/HTML coverage reports
7. `./gradlew testCoverage`: run extended tests and validate the coverage threshold
8. `testApiLegacy` has been retired and is no longer kept as an executable test task or CI entry

### Bench Scenario Automation and Spark Collection
- Directories:
1. Lite performance matrix: `tools/bench/matrix.json`
2. Formal baseline matrix: `tools/bench/matrix-baseline-256.json`
3. Heavy performance matrix: `tools/bench/matrix-performance-heavy.json`
4. Cross-chunk / loading stress matrix: `tools/bench/matrix-crosschunk-stress.json`
5. Physical-world end-to-end matrix: `tools/bench/matrix-physical-world.json`
6. Runner script: `tools/bench/run-bench.ps1`
7. Suite runner: `tools/bench/run-bench-suite.ps1`
8. Bench datapack: `tools/bench/datapack/rl_bench`
9. Dedicated-server RCON example: `tools/bench/bench.server.properties.example`
- Preconditions:
1. Use a dedicated server scene with RCON support; follow `tools/bench/bench.server.properties.example` and enable `enable-rcon=true`.
2. Install spark in the bench world and make sure `/spark ...` commands can run.
3. Install the datapack into the target save first:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action InstallDatapack -SavePath run/saves/rl-bench
```
4. The examples in this document target Windows PowerShell 5.x by default. If you use PowerShell 7, you can switch the invocation to `pwsh` yourself.
- Common commands:
1. List scenes:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action List
```
2. Print one scene layout summary:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -CaseId sync_1_to_64_core_dense
```
3. Run a lite pure-performance scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -CaseId sync_1_to_64_core_dense -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
4. Print a formal 256 baseline scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16
```
5. Run a formal 256 baseline scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId mixed_256_to_256_core_banded16 -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
6. Run a heavy scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-performance-heavy.json -CaseId sync_1_to_2048_core_dense -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
7. Run a cross-chunk / loading stress scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-crosschunk-stress.json -CaseId sync_queue_release_1024_single_shot -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
8. Run a physical-world end-to-end scene:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-physical-world.json -CaseId mixed_256_to_256_core_banded16 -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
9. Run a single case with automatic player waiting, setup, and teleport to the observation point:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16 -SavePath 'D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template - 256' -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer '@a[tag=bench_runner,limit=1]' -PlayerReadyTimeoutMs 180000 -PlayerSetupCommands 'gamemode spectator @s' -AutoTeleportPlayerToObservationPoint
```
10. Run a single case with an external client that auto-starts, auto-joins, and auto-teleports after placement:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16 -SavePath 'D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template - 256' -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AutoStartBenchClient -BenchClientPlayerName op -BenchClientInstanceRoot 'D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client' -BenchClientStartCommand '.\start-client.bat' -BenchClientGameHost 127.0.0.1 -BenchClientGamePort 25565 -BenchClientFocusWindow -BenchClientOpenTickCharts -SyncLatestClientModJar -BuildBeforeSyncLatestModJar -PlayerSetupCommands 'gamemode spectator @s' -AutoTeleportPlayerToObservationPoint
```
11. If `BenchClientStartCommand` contains nested quotes, prefer the "session variable + `& .\tools\bench\run-bench.ps1 ...`" pattern in Windows PowerShell 5.x:
```powershell
$benchClientStartCommand='"D:\Prism Launcher\prismlauncher.exe" --dir "C:\Users\15166\AppData\Roaming\PrismLauncher" --launch "1.21.1" --profile "op" --server "127.0.0.1:25565"'
& '.\tools\bench\run-bench.ps1' -Action RunCase ... -BenchClientStartCommand $benchClientStartCommand
```
- Built-in sample cases:
1. Lite performance:
   `sync_1_to_64_core_dense`
   `toggle_64_to_1_core_dense`
   `mixed_16_to_16_core_dense`
   `sync_1_to_256_core_dense`
   `mixed_64_to_64_core_dense`
2. Formal 256 baseline:
   `sync_256_to_256_core_banded16`
   `mixed_256_to_256_core_banded16`
3. Heavy performance:
   `sync_1_to_2048_core_dense`
   `toggle_1_to_2048_core_dense`
   `pulse_1_to_2048_core_dense`
   `sync_1024_to_1_core_dense`
   `toggle_1024_to_1_core_dense`
   `pulse_1024_to_1_core_dense`
   `sync_1024_to_1024_core_banded16`
   `mixed_1024_to_1024_core_banded16`
   `mixed_1024_to_1024_topology_mutation_batches`
4. Cross-chunk / loading stress:
   `sync_minimal_loop_single_pair`
   `sync_queue_release_1024_single_shot`
   `sync_queue_release_1024_blank_control`
   `sync_resident_256_distinct_chunks`
   `sync_force_load_128_bursty_distinct_chunks`
- Behavior notes:
1. The script places nodes via `/redstonelink place ...`, then reads back serials through `/data get block <pos> Serial`. During link building, it first tries the structured batch command `/redstonelink bench link apply ...` for `broadcast_all`, `fan_in_first`, and `banded`; if the server does not support it or returns a command-level failure, it falls back automatically to the old per-entry `link set`.
2. `tools/bench/matrix.json` and `tools/bench/matrix-baseline-256.json` are both pure-mod performance layers now. They drive `triggerSource/core` uniformly through the input system instead of using redstone-block control belts or `node activate` as the main performance driver.
3. `tools/bench/matrix-performance-heavy.json` now covers `1 -> 2048` broadcast, `1024 -> 1` fan-in, and `1024 -> 1024` sync/mixed heavy many-to-many scenes.
4. `tools/bench/matrix-crosschunk-stress.json` covers minimal loop oscillation, `1024 pending` reload release, `256 resident` distinct chunks, and `128 force-load` bursty rounds. The matrix supports mixed `wait_ticks/command_assert` timelines in `drive.steps`, and lets each case explicitly disable default case-chunk preload.
5. Pure-performance input templates are standardized as `0/15`, `2 ticks`, `10Hz`, which means `periodTicks=2/highTicks=1`.
6. `tools/bench/matrix-physical-world.json` intentionally keeps redstone-block and command-driven flows for real-world end-to-end observation and F3 comparisons.
7. Pure-performance matrices use a `1600 tick = 80 second` drive window and only count the last `60 seconds` of spark data with `warmupTicks=400 / measureTicks=1200`.
8. Result JSON paths are split by matrix:
   - lite: `run/profiles/bench-results/`
   - baseline-256: `run/profiles/bench-results-baseline-256/`
   - performance-heavy: `run/profiles/bench-results-performance-heavy/`
   - crosschunk-stress: `run/profiles/bench-results-crosschunk-stress/`
   - physical-world: `run/profiles/bench-results-physical-world/`
9. Pure-performance matrices automatically call `redstonelink input clear` at the end of each case so that input jobs do not leak into the next case.
10. Pure-performance matrices depend on `server.command.input.enabled=true`, which is enabled by default.
11. Dedicated-server `RunCase` still supports `-AsPlayer` by default. That player must be online, have command permission, and be in the target dimension.
12. If the server already has `server.command.benchmarkMode.enabled=true`, `RunCase` can be called without `-AsPlayer`; bench will use console / RCON command sources directly for `place/link set/input`, which is better for unattended pressure tests.
13. When `-AsPlayer` is provided, bench automatically polls for player readiness before running the case.
14. `-PlayerReadyTimeoutMs` and `-PlayerReadyPollIntervalMs` control timeout and polling interval; if the player still is not online or the selector does not match in time, bench fails and reports the last probe response.

### Bench Functional Verification Scripts
- Directories:
1. Functional matrix: `tools/bench/functional-matrix.json`
2. Runner script: `tools/bench/run-bench.ps1`
- Goals:
1. Reuse the current bench skeleton to validate integration between `input start`, `node activate`, and `node trace`.
2. Cover typical `sync/pulse/toggle` behavior and explicitly verify `range/slash/mixed` serial-expression formats.
- Built-in sample cases:
1. Runtime command phase 1:
   `place_runtime_basic`, `node_query_retire_audit_basic`, `input_admin_basic`, `policy_runtime_basic`, `crosschunk_command_basic`
2. Functional behavior regression:
   `link_lifecycle_add_remove_set_clear`, `sync_core_range_square_basic`, `sync_triggerSource_slash_square_basic`, `sync_triggerSource_custom_mixed_basic`, `pulse_activate_sparse_zip`, `toggle_activate_fanin_even_odd`, `input_activate_trace_command_alignment`
3. Cross-chunk and restart:
   `crosschunk_sync_triggerSource_zip_square`, `restart_persist_toggle_prepare`, `restart_persist_toggle_verify`
4. Phase-2 edges / cross-dimension / restart expansion:
   `command_boundary_matrix`
   `crosschunk_resident_offline_restore`
   `crossdim_sync_overworld_to_nether_zip_square`
   `crossdim_sync_nether_to_end_zip_square`
   `crossdim_sync_end_to_overworld_zip_square`
   `restart_input_job_prepare`
   `restart_input_job_verify`
   `crosschunk_sync_reload_replay_enabled`
   `crosschunk_sync_reload_replay_disabled`
   `crosschunk_force_load_mode_all`
   `crosschunk_force_load_mode_whitelist_unmatched`
   `reload_template_world_boot_basic`
   `crosschunk_target_attach_filters_multi_source_physical_remove`
   `direct_sync_batching_all_sync_multi_source_square`
   `crosschunk_pending_queue_batching_multi_source_manual_reload`
   `crosschunk_force_load_preload_batching_multi_source`
- Common commands:
1. List functional cases:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action List -MatrixPath .\tools\bench\functional-matrix.json
```
2. Print one functional case:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -MatrixPath .\tools\bench\functional-matrix.json -CaseId sync_core_range_square_basic
```
3. Run one functional case:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunFunctionalCase -MatrixPath .\tools\bench\functional-matrix.json -CaseId sync_triggerSource_custom_mixed_basic -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot
```
- Behavior notes:
1. `RunFunctionalCase` still reuses `/redstonelink place ...`, `link set`, and serial readback. It does not maintain a separate placement implementation.
2. Functional phases wait by real server `gametime`, not only local `Start-Sleep`.
3. Phase-2 main assertions now use per-tick comparison on `node trace read`; `trace latest` stays only for a few steady-state scenes or supporting evidence.
4. `trace mount` writes one baseline snapshot immediately; `trace_read_tick_assert` automatically crops that baseline by `mountRef` and aligns real driving from `mountTick + 1`.
5. Strict per-tick assertion requires `every=1` in the matching mount phase and uses `anchorRef` to limit comparison to a finite window near the driving command.
6. When `trace_read_tick_assert` uses an `input start` phase as `anchorRef` and the target-side change is flushed inside `END_SERVER_TICK` by `CoreDispatchBatchScheduler`, the target trace may first show the new value at `jobStartTick + 1`; that does not mean `crosschunk.dispatch.batchWindowTicks=0` failed.
7. The current order is: `NodeStateTraceService` samples at `END_SERVER_TICK` first, `InputPlaybackService` processes input after that, and `CoreDispatchBatchScheduler` flushes last.

### Bench Suite: Fresh World Per Case
- Best for:
1. cases that must run in a brand-new world every time so that old links, chunk state, and spark history cannot leak into the result
2. external dedicated servers that need to switch `level-name`, start/stop, and gather results per case in sequence
- Core boundaries:
1. `tools/bench/run-bench.ps1` still handles only `single world + single case`
2. `tools/bench/run-bench-suite.ps1` only handles outer lifecycle orchestration: copy template world, switch `level-name`, start/stop the dedicated server, call single-case bench, and write the suite summary
3. `-BenchAction` chooses the inner action:
   - `RunCase`: performance / sampling case
   - `RunFunctionalCase`: functional verification case
- Recommended preparation:
1. Configure bench local paths in `tools/bench/bench.path-config.json`; the current keys are `serverRoot`, `templateWorld`, `reloadTemplateWorld`, `prismLauncher`, and `prismRootDir`.
2. `serverRoot` is the dedicated-server root; `templateWorld` and `reloadTemplateWorld` may be absolute paths or names relative to `serverRoot`.
3. Suite entries such as `reload_template_world_boot` resolve the reload template through `{{param:reloadTemplateWorld}}` instead of a hard-coded absolute path.
4. Suite runs create case worlds under `<serverRoot>\rl-cases\`.
5. Keep RCON enabled in `server.properties`; for full unattended runs, also enable `server.command.benchmarkMode.enabled=true`.
6. In Windows PowerShell 5.x, do not use outer `powershell -File` for multi-case commands using `-CaseIds @(...)`; call `& .\tools\bench\run-bench-suite.ps1 ...` inside the current session instead.
7. If local script execution is blocked in the current session, run `Set-ExecutionPolicy -Scope Process Bypass -Force` first.
- Common commands:
1. Run a fresh-world single case:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -CaseIds sync_1_to_64_core_dense `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
2. Shortest command for one functional case on the current workstation:
These commands now read `serverRoot` and `templateWorld` from `tools/bench/bench.path-config.json`; if your workstation is already configured, you may omit `-ServerRoot` / `-TemplateWorldPath`.
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunFunctionalCase `
  -MatrixPath .\tools\bench\functional-matrix.json `
  -CaseIds sync_core_range_square_basic `
  -RconPassword redstonelink-bench
```
3. Run a whole functional subset at once:
```powershell
& .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunFunctionalCase `
  -MatrixPath .\tools\bench\functional-matrix.json `
  -CaseIds @(
    'sync_core_range_square_basic'
    'sync_triggerSource_slash_square_basic'
    'sync_triggerSource_custom_mixed_basic'
    'pulse_activate_sparse_zip'
    'toggle_activate_fanin_even_odd'
  ) `
  -RconPassword redstonelink-bench `
  -ContinueOnFailure
```
4. If benchmark mode is off, add player context:
```powershell
-AsPlayer BenchBot
```
5. Run several performance cases in sequence:
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -CaseIds @(
    'sync_1_to_64_core_dense'
    'toggle_64_to_1_core_dense'
    'mixed_16_to_16_core_dense'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -ContinueOnFailure
```
6. Run a fresh-world suite with automatic player waiting and setup commands:
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -MatrixPath '.\tools\bench\matrix-baseline-256.json' `
  -CaseIds @(
    'sync_256_to_256_core_banded16'
    'mixed_256_to_256_core_banded16'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AsPlayer '@a[tag=bench_runner,limit=1]' `
  -PlayerReadyTimeoutMs 180000 `
  -AutoTeleportPlayerToObservationPoint `
  -PlayerSetupCommands @(
    'gamemode spectator @s'
  )
```
7. Run a fresh-world suite with an external auto-start bench client and sync the same locally built jar to both server and client:
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -MatrixPath '.\tools\bench\matrix-baseline-256.json' `
  -CaseIds @(
    'sync_256_to_256_core_banded16'
    'mixed_256_to_256_core_banded16'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AutoStartBenchClient `
  -BenchClientPlayerName op `
  -BenchClientInstanceRoot 'D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client' `
  -BenchClientStartCommand '.\start-client.bat' `
  -BenchClientGameHost 127.0.0.1 `
  -BenchClientGamePort 25565 `
  -BenchClientFocusWindow `
  -BenchClientOpenTickCharts `
  -SyncLatestModJar `
  -SyncLatestClientModJar `
  -BuildBeforeSyncLatestModJar `
  -AutoTeleportPlayerToObservationPoint `
  -PlayerSetupCommands @(
    'gamemode spectator @s'
  )
```
8. Run the "first mandatory" wrapper directly:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-first-mandatory-suite.ps1 `
  -RconPassword redstonelink-bench
```
9. Run the "first mandatory" build-sync wrapper:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-first-mandatory-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
10. Or keep using `run-bench-suite.ps1` directly and explicitly build before syncing the latest jar:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\first-mandatory.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -BuildBeforeSyncLatestModJar `
  -SyncLatestModJar
```
- Suite external-client notes:
1. After `-AutoStartBenchClient` is enabled, the suite starts the external client only after the first dedicated server passes `Wait-RconReady`, so a launcher command containing `--server` does not connect too early.
2. At the end of the suite, the script tries to close the external client process and clean the auto-written `config/redstonelink-bench-client.properties`.
3. If `BenchClientStartCommand` is a launcher command, `launcherProcessId/gameProcessId/trackedProcessKind` in the suite summary show whether bench ended up managing the launcher or the real Minecraft process.
4. `-SyncLatestClientModJar` shares the same locally built jar with `-SyncLatestModJar`, guaranteeing the same runtime version on server and client.
5. It is recommended to keep the external bench client in a dedicated directory such as `D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client`.
6. With `-BenchClientFocusWindow`, the suite tries to bring the real Minecraft window to the foreground after launch.
7. With `-BenchClientOpenTickCharts`, the client reopens the `F3+2` tick chart idempotently after each reconnect.
8. The suite summary also records `benchClientRequested/benchClientStartAttempted/benchClientStartSucceeded/benchClientStartError` at the top level.
9. If `-AutoStartBenchClient` was requested but the suite still cannot get a bench-client session after the dedicated server is ready, the suite fails immediately instead of waiting through a long player-readiness timeout.
- Suite layering:
1. `smoke.json`: minimum smoke validation for datapack, world setup, and one minimal functional case
2. `command-basic.json`: command and basic-function regression entry
3. `functional-phase2.json`: phase-2 functional coverage for command edges, offline resident recovery, input-job restart semantics, cross-dimension sync, `syncTargetChunkLoadReplay`, `forceLoad.mode`, pending queue batching, and force-load preload batching with `directBatching=all_direct`
4. `first-mandatory.json`: current mandatory-first set
5. `functional-regression.json`: functional regression, covering F02/F03/F04
6. `restart-crosschunk.json`: cross-chunk and restart focus, covering X01/X04/R02
7. `performance-lite.json`: lite pure-performance scenes for script connectivity, spark path, and quick performance regression
8. `performance-baseline.json`: formal pure-performance baseline from regularized `256x256` many-to-many scenes
9. `performance-physical-world.json`: physical-world end-to-end scenes that keep redstone-block / command drives to observe real input and neighbor-update cost
10. `crosschunk-stress-sync.json`: first crosschunk stress set, only `sync_queue_release_1024_single_shot` and `sync_queue_release_1024_blank_control`
11. `crosschunk-stress-full.json`: extended stress set with `sync_minimal_loop_single_pair`, `sync_resident_256_distinct_chunks`, and `sync_force_load_128_bursty_distinct_chunks`
12. `nightly-full.json`: night full run covering current functional, cross-chunk/restart, and pure-performance baseline scenes; physical-world runs are still separate
- Recommended wrappers:
1. It is recommended to collapse functional / lifecycle regression into four groups: `smoke`, `regression-core`, `regression-lifecycle`, `regression-full`
2. Matching suite files:
   `tools/bench/suites/smoke.json`
   `tools/bench/suites/regression-core.json`
   `tools/bench/suites/regression-lifecycle.json`
   `tools/bench/suites/regression-full.json`
3. Matching wrapper scripts:
   `tools/bench/run-smoke-suite.ps1`
   `tools/bench/run-smoke-suite-build-sync.ps1`
   `tools/bench/run-regression-core-suite.ps1`
   `tools/bench/run-regression-core-suite-build-sync.ps1`
   `tools/bench/run-regression-lifecycle-suite.ps1`
   `tools/bench/run-regression-lifecycle-suite-build-sync.ps1`
   `tools/bench/run-regression-full-suite.ps1`
   `tools/bench/run-regression-full-suite-build-sync.ps1`
4. These regression wrappers do not auto-start clients or auto-teleport to observation points by default; especially `regression-lifecycle` and `regression-full` deliberately avoid auto-TP so that crosschunk / loading scenes are not disturbed by player movement.
5. Even if `server.command.benchmarkMode.enabled=true` is off, you may still pass `-AsPlayer <player>` explicitly.
- Recommended regression wrapper commands:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-smoke-suite.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-smoke-suite-build-sync.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-core-suite.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-core-suite-build-sync.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-lifecycle-suite.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-lifecycle-suite-build-sync.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-full-suite.ps1 -RconPassword redstonelink-bench
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-full-suite-build-sync.ps1 -RconPassword redstonelink-bench
```
- Other useful suite commands:
1. Run any other suite directly through `-SuitePath`.
2. The dedicated-server basic-permission suite is `tools/bench/suites/server-basic-permission.json`.
3. The 10Hz heavy-performance suite is `tools/bench/suites/performance-heavy.json`.
4. The first crosschunk stress suite is `tools/bench/suites/crosschunk-stress-sync.json`.
5. The extended crosschunk stress suite is `tools/bench/suites/crosschunk-stress-full.json`.
- Performance wrapper scripts:
1. `tools/bench/run-performance-lite-suite.ps1`
2. `tools/bench/run-performance-lite-suite-build-sync.ps1`
3. `tools/bench/run-performance-heavy-suite.ps1`
4. `tools/bench/run-performance-heavy-suite-build-sync.ps1`
- Defaults built into those four wrappers:
1. external client auto-join
2. auto-teleport to the current case observation point
3. client-window focus
4. automatic `F3+2` tick chart opening
5. dedicated server hidden by default (`ServerWindowMode=Hidden`)
6. dedicated server requested at high priority by default (`ServerPriorityClass=High`)
7. default player / instance: `BenchClientPlayerName=op`, Prism instance `1.21.1`
- Typical usage:
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-lite-suite.ps1
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-heavy-suite.ps1
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-lite-suite-build-sync.ps1
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-heavy-suite-build-sync.ps1
```
- Performance-scene categories:
1. `performance-lite.json`: quick regression for scripts, client observation, and spark path
2. Lite broadcast: `sync_1_to_64_core_dense`, `sync_1_to_256_core_dense`
3. Lite fan-in: `toggle_64_to_1_core_dense`
4. Lite mixed many-to-many: `mixed_16_to_16_core_dense`, `mixed_64_to_64_core_dense`
5. Lite loop: `sync_minimal_loop_single_pair`
6. `performance-heavy.json`: pure-mod heavy input set at 10Hz
7. Heavy single-source broadcast: `sync_1_to_2048_core_dense`, `toggle_1_to_2048_core_dense`, `pulse_1_to_2048_core_dense`
8. Heavy many-source fan-in: `sync_1024_to_1_core_dense`, `toggle_1024_to_1_core_dense`, `pulse_1024_to_1_core_dense`
9. Heavy regularized many-to-many: `sync_1024_to_1024_core_banded16`, `mixed_1024_to_1024_core_banded16`, `mixed_1024_to_1024_topology_mutation_batches`
- Recommended usage:
1. Run `smoke.json` first for a quick local sanity check.
2. For command / feature regression, prefer `command-basic.json`, `first-mandatory.json`, or `functional-regression.json`.
3. If you touched cross-chunk or persistence logic, add `restart-crosschunk.json`.
4. For a fast check of scripts, client automation, and spark path, prefer `run-performance-lite-suite.ps1`.
5. For high-pressure pure-mod sampling or regression, prefer `run-performance-heavy-suite.ps1`.
6. If the round also requires syncing the latest server/client mod jar, switch to the matching `*-build-sync.ps1` wrapper.
7. Use `performance-baseline.json` for formal pure-mod baselines.
8. Add `performance-physical-world.json` when you want real-world input / F3 behavior evidence.
9. Use `nightly-full.json` for larger version closeout or night runs.
- Output notes:
1. Single-case bench results are written into `run/profiles/bench-results/`.
2. Suite summaries are written into `run/profiles/bench-suite-results/<timestamp>/summary.json`.
3. By default, case worlds are kept under `mcserver\rl-cases\<worldName>` for later review; if only result files matter, add `-DeleteCaseWorldOnSuccess`.
4. During suite execution, `server.properties` temporarily rewrites `level-name` to `rl-cases/<worldName>`.
5. When `-BenchAction RunFunctionalCase` is used, the suite summary also records `passed/checksCount/failedChecksCount`.
6. When `-SyncLatestModJar` or `-SyncLatestClientModJar` is enabled, the suite summary also records the local jar used this round, the copied server/client jars, and the replaced old `redstonelink*.jar`.
7. If a suite entry overrides `templateWorldPath`, the suite summary records the actual template path used by that result entry.
- Notes:
1. Before a suite starts, make sure the dedicated server is not already running, or `level-name` switching will not be trustworthy.
2. `-ServerStartCommand` must be a foreground blocking server-start command.
3. At the end of the suite, `server.properties` is restored to its original text.
4. `-BuildBeforeSyncLatestModJar` must be paired with `-SyncLatestModJar` or `-SyncLatestClientModJar`.
5. If you need to pin the artifact manually, use `-ModJarPath <path>`; otherwise the script auto-selects the latest runtime jar from `build/libs` and excludes `*-sources.jar`.
