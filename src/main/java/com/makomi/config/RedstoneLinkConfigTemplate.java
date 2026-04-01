package com.makomi.config;

/**
 * 默认配置模板提供器。
 */
final class RedstoneLinkConfigTemplate {
	private RedstoneLinkConfigTemplate() {
	}

	/**
	 * 生成默认配置文件内容。
	 */
	static String defaultConfigContent() {
		return """
			# RedstoneLink server config / RedstoneLink 服务器配置
			#
			# --- [基础与核心 / Core Runtime] ---------------------------------------
			# server.pulseDurationTicks
			# zh: 核心脉冲持续时长（tick），PULSE 模式触发后保持激活的时间。
			# en: Pulse duration (ticks) for core activation in PULSE mode.
			server.pulseDurationTicks=4
			
			# server.emitterEdgeMode
			# zh: 发射器红石边沿触发模式（仅 toggle/pulse 生效），可选：rising / falling / both。
			# en: Emitter edge trigger mode for toggle/pulse emitters: rising / falling / both.
			server.emitterEdgeMode=rising
			
			# server.coreOutputPower
			# zh: 核心输出红石强度（0~15）。
			# en: Core redstone output power (0~15).
			server.coreOutputPower=15
			
			# server.maxTargetsPerSetLinks
			# zh: 单次 set_links 允许设置的最大目标数。
			# en: Maximum target count allowed in one set_links operation.
			server.maxTargetsPerSetLinks=1024
			
			# server.allowOfflineTargetBinding
			# zh: 是否允许绑定离线目标（未加载区块/不在线端点）。
			# en: Whether offline targets can be bound.
			server.allowOfflineTargetBinding=true

			# server.statePanel.refreshHz
			# zh: 状态面板整体刷新动作节流频率（Hz），用于限制一次 refresh 的最小间隔。
			# en: Throttle frequency (Hz) for state-panel full refresh actions.
			server.statePanel.refreshHz=5

			# server.statePanel.maxSubscriptions
			# zh: 状态面板订阅上限（总条目数，含 core 与 triggerSource）。
			# en: Maximum subscription entries in state panel (core + triggerSource combined).
			server.statePanel.maxSubscriptions=50

			# --- [命令权限与限流 / Command Permission & Rate Limit] -----------------
			# server.command.permissionLevel
			# zh: /redstonelink 整个命令树所需权限等级（0~4）。
			# en: Permission level required for the whole /redstonelink command tree (0~4).
			server.command.permissionLevel=0

			# server.command.otherPermissionLevel
			# zh: “其他权限”命令组所需权限等级（0~4），默认 2（普通玩家不开放）。
			# zh: 管控范围：node activate、place 批量放置、node retire（含 batch）、audit 审阅、node get/list、link get。
			# en: Permission level for the "other-permissions" command group (0~4), default 2 (not open to regular players).
			# en: Scope: node activate, place, node retire (including batch), audit, node get/list, and link get.
			server.command.otherPermissionLevel=2

			# server.command.benchmarkMode.enabled
			# zh: 是否启用 bench 命令测试模式。启用后，bench 相关命令允许更方便地配合控制台或 RCON 执行。
			# en: Whether to enable benchmark command mode for bench workflows. When enabled, bench-related commands can be used more conveniently with console or RCON.
			server.command.benchmarkMode.enabled=false

			# server.command.input.enabled
			# zh: 是否启用输入播放命令与运行时服务。默认开启；若 triggerSource 加载后自愈已开启，推荐保持开启以便复现与诊断输入残留问题。
			# en: Whether to enable input playback commands and runtime service. Enabled by default; recommended to stay on when triggerSource post-load self-heal is enabled.
			server.command.input.enabled=true

			# server.command.nodeTrace.enabled
			# zh: 是否启用节点状态追踪命令与采样服务。默认开启；若 core 或 triggerSource 加载后自愈已开启，推荐保持开启以便观察自愈前后的节点状态。
			# en: Whether to enable node trace commands and sampling service. Enabled by default; recommended to stay on when core or triggerSource post-load self-heal is enabled.
			server.command.nodeTrace.enabled=true

			# server.runtime.loadResync.core.enabled
			# zh: 是否启用 core 读档后的异步外显自愈。建议保持开启，用于校正异常停服后的可见态残留。
			# en: Whether to enable async post-load self-heal for core visible blockstates.
			server.runtime.loadResync.core.enabled=true

			# server.runtime.loadResync.triggerSource.enabled
			# zh: 是否启用 triggerSource 读档后的异步输入自愈。建议保持开启，用于校正 emitter 的残留 POWERED/观测缓存。
			# en: Whether to enable async post-load input self-heal for triggerSource emitters.
			server.runtime.loadResync.triggerSource.enabled=true

			# server.runtime.loadResync.maxRetry
			# zh: 加载后自愈任务在 chunk 未就绪时允许的最大额外重试次数；0 表示只尝试当前这一轮，不再回队。
			# en: Maximum extra retries for post-load self-heal tasks when the chunk is not ready yet; 0 means try once without requeue.
			server.runtime.loadResync.maxRetry=40

			# server.command.rateLimit.enabled
			# zh: 是否启用命令频率防护（分层限流）。
			# en: Whether to enable layered command rate limiting.
			server.command.rateLimit.enabled=true

			# server.command.rateLimit.windowTicks
			# zh: 限流窗口长度（tick），范围 1~2000，建议保持 20（约 1 秒）。
			# en: Rate-limit window length in ticks, range 1~2000; 20 ticks is about 1 second.
			server.command.rateLimit.windowTicks=20

			# server.command.rateLimit.global.capacity
			# zh: 全局窗口容量（所有来源共享），范围 1~200000。
			# en: Global window capacity shared by all actors, range 1~200000.
			server.command.rateLimit.global.capacity=3072

			# server.command.rateLimit.tier.baseCapacity
			# server.command.rateLimit.tier.stepPerLevel
			# zh: 权限层级窗口容量 = base + permissionLevel * step，base 范围 1~200000，step 范围 0~200000。
			# en: Tier capacity formula: base + permissionLevel * step; base range 1~200000, step range 0~200000.
			server.command.rateLimit.tier.baseCapacity=600
			server.command.rateLimit.tier.stepPerLevel=400

			# server.command.rateLimit.actor.baseCapacity
			# server.command.rateLimit.actor.stepPerLevel
			# zh: 来源个体窗口容量 = base + permissionLevel * step，base 范围 1~200000，step 范围 0~200000。
			# en: Actor capacity formula: base + permissionLevel * step; base range 1~200000, step range 0~200000.
			server.command.rateLimit.actor.baseCapacity=24
			server.command.rateLimit.actor.stepPerLevel=16

			# server.command.rateLimit.actorGroup.linkRw.baseCapacity
			# server.command.rateLimit.actorGroup.linkRw.stepPerLevel
			# zh: link 读写组个体容量公式（一期覆盖 link add/remove/set 与 write_control protected），base 范围 1~200000，step 范围 0~200000。
			# en: Actor-group formula for link write/read group; base range 1~200000, step range 0~200000.
			server.command.rateLimit.actorGroup.linkRw.baseCapacity=12
			server.command.rateLimit.actorGroup.linkRw.stepPerLevel=8

			# server.command.rateLimit.actorGroup.crosschunk.baseCapacity
			# server.command.rateLimit.actorGroup.crosschunk.stepPerLevel
			# zh: crosschunk 组个体容量公式，base 范围 1~200000，step 范围 0~200000。
			# en: Actor-group formula for crosschunk group; base range 1~200000, step range 0~200000.
			server.command.rateLimit.actorGroup.crosschunk.baseCapacity=4
			server.command.rateLimit.actorGroup.crosschunk.stepPerLevel=3

			# server.command.rateLimit.actorGroup.other.baseCapacity
			# server.command.rateLimit.actorGroup.other.stepPerLevel
			# zh: 其他权限组个体容量公式（activate/retire/place/audit/node/link 查询），base 范围 1~200000，step 范围 0~200000。
			# en: Actor-group formula for other-permissions group; base range 1~200000, step range 0~200000.
			server.command.rateLimit.actorGroup.other.baseCapacity=6
			server.command.rateLimit.actorGroup.other.stepPerLevel=4

			# --- [当前连接隐私 / Current Links Privacy] -----------------------------
			# server.currentLinksPrivacy.mode
			# zh: 近外显“当前连接”保密模式：hidden=全部保密，masked=仅名单保密，plain=全部解密。
			# zh: 注意：plain 模式下历史物品 NBT 快照不会因后续切回 masked/hidden 自动重写，建议默认使用 masked。
			# en: Privacy mode for near-overlay current links: hidden/masked/plain.
			# en: Note: historical item-NBT snapshots written in plain mode are not automatically rewritten after switching back to masked/hidden.
			server.currentLinksPrivacy.mode=masked

			# server.currentLinksPrivacy.overlayResponsePermissionLevel
			# zh: 服务端是否回任何近外显包所需权限等级（0~4）；不满足时直接拒绝回“当前连接/最终 IO”外显包。
			# en: Permission level required for the server to send any near-overlay packets (0~4); requests below this level receive no current-links/runtime-HUD response.
			server.currentLinksPrivacy.overlayResponsePermissionLevel=0

			# server.currentLinksPrivacy.viewPermissionLevel
			# zh: 查看被加密“当前连接”所需权限等级（0~4）。
			# en: Permission level required to view masked current links (0~4).
			server.currentLinksPrivacy.viewPermissionLevel=2

			# server.currentLinksPrivacy.managePermissionLevel
			# zh: 管理“当前连接加密名单”命令所需权限等级（0~4）。
			# en: Permission level required for current-links mask management commands (0~4).
			server.currentLinksPrivacy.managePermissionLevel=2

			# --- [连接写控与输入上限 / Link Write Control & Input Limits] ----------
			# server.linkWriteControl.mode
			# zh: 链接写入控制模式：full=全量写入，limited=限量写入，readonly=只读。
			# en: Link write-control mode: full / limited / readonly.
			server.linkWriteControl.mode=limited

			# server.linkWriteControl.limited.permissionLevel
			# zh: limited 模式下越过“最大设置量”限制所需权限等级（0~4）。
			# en: Permission level to bypass limited-mode max set-size restriction (0~4).
			server.linkWriteControl.limited.permissionLevel=2

			# server.linkWriteControl.limited.maxSetSize
			# zh: limited 模式下单次 set 允许的最大目标设置量。
			# en: Maximum target set-size allowed per single set operation in limited mode.
			server.linkWriteControl.limited.maxSetSize=64

			# server.linkWriteControl.protected.permissionLevel
			# zh: 命中受控名单时允许写入所需权限等级（0~4）。
			# en: Permission level required to write when touching protected serials (0~4).
			server.linkWriteControl.protected.permissionLevel=2

			# server.linkWriteControl.protected.managePermissionLevel
			# zh: 管理受控名单命令所需权限等级（0~4）。
			# en: Permission level required for protected-list management commands (0~4).
			server.linkWriteControl.protected.managePermissionLevel=2

			# server.command.linkSet.maxInputLength
			# zh: `link set` 的 targets 原始输入最大长度（字符），超出直接拒绝。
			# en: Maximum raw targets input length (characters) for `link set`; over limit is rejected.
			server.command.linkSet.maxInputLength=1024

			# server.command.activate.batchMaxSerials
			# zh: `node activate` 批量来源序号上限。
			# en: Maximum source serial count for `node activate` batch input.
			server.command.activate.batchMaxSerials=1024

			# server.command.retire.batchMaxSerials
			# zh: `node retire batch` 批量序号上限。
			# en: Maximum serial count for `node retire batch`.
			server.command.retire.batchMaxSerials=1024

			# server.command.privacy.currentLinksMask.maxSetSerials
			# zh: `link privacy current_links mask set` 批量序号上限。
			# en: Maximum serial count for `link privacy current_links mask set`.
			server.command.privacy.currentLinksMask.maxSetSerials=1024

			# server.command.writeControl.protected.maxSetSerials
			# zh: `link write_control protected set` 批量序号上限。
			# en: Maximum serial count for `link write_control protected set`.
			server.command.writeControl.protected.maxSetSerials=1024

			# server.command.crosschunk.whitelist.maxSetSerials
			# zh: `crosschunk whitelist set` 批量序号上限。
			# en: Maximum serial count for `crosschunk whitelist set`.
			server.command.crosschunk.whitelist.maxSetSerials=1024
			
			# --- [交互行为 / Interaction] -------------------------------------------
			# interaction.requireSneakToOpenPairing
			# zh: 打开配对 UI 是否必须潜行。默认 true，需潜行右键打开。
			# en: Require sneaking to open pairing UI. Default true means sneaking + right-click opens the UI.
			interaction.requireSneakToOpenPairing=true

			# interaction.requireSneakToOpenLinkerPairing
			# zh: 遥控器打开配对 UI 是否必须潜行。默认 true，避免与站立右键触发冲突。
			# en: Require sneaking when opening pairing UI via linker.
			interaction.requireSneakToOpenLinkerPairing=true
			
			# interaction.requireEmptyOffhandToOpenPairing
			# zh: 打开配对 UI 是否必须副手为空。
			# en: Require empty offhand to open pairing UI.
			interaction.requireEmptyOffhandToOpenPairing=true

			# --- [跨区块 / Cross-Chunk] ---------------------------------------------
			# ----- [白名单与预设 / Whitelist & Preset] -------------------------------
			# crosschunk.whitelist.sourceTypes
			# zh: 允许作为来源的类型列表（逗号/空格分隔）。
			# en: Allowed source types for cross-chunk whitelist.
			crosschunk.whitelist.sourceTypes=triggerSource

			# crosschunk.whitelist.targetTypes
			# zh: 允许作为目标的类型列表（逗号/空格分隔）。
			# en: Allowed target types for cross-chunk whitelist.
			crosschunk.whitelist.targetTypes=core

			# crosschunk.preset.<name>.sources / crosschunk.preset.<name>.targets
			# zh: 只读 preset，格式为 type:serial（逗号/空格分隔）。
			# en: Read-only preset entries using type:serial format.
			# crosschunk.preset.keypath.sources=triggerSource:1001
			# crosschunk.preset.keypath.targets=core:2001

			# ----- [信号与激活 / Signal & Activation] ------------------------------
			# crosschunk.syncSignalTtlTicks
			# zh: 跨区块 SYNC 信号 TTL（tick），仅在 syncSignalPersistent=false 时生效。
			# en: TTL for queued cross-chunk SYNC events; only used when syncSignalPersistent=false.
			crosschunk.syncSignalTtlTicks=40

			# crosschunk.syncSignalPersistent
			# zh: 是否启用 SYNC 信号不限时持久化兜底。true=不受 TTL 过期影响，按“最新状态”等待目标恢复后补投递；默认关闭，常规恢复优先依赖 target attach replay。
			# en: Whether SYNC uses unlimited persistence as a fallback. true keeps the latest-state pending without TTL expiry until the target recovers; disabled by default because normal recovery primarily relies on target attach replay.
			crosschunk.syncSignalPersistent=false

			# crosschunk.syncTargetChunkLoadReplay.enabled
			# zh: 是否启用目标区块 `CHUNK_LOAD` 时的 sync 补发。true=按来源端最近一次真实 sync 时间键恢复。
			# en: Whether sync should replay when the target chunk loads. true means restore using the source's latest real sync event time.
			crosschunk.syncTargetChunkLoadReplay.enabled=true

			# crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst
			# zh: 目标区块 `CHUNK_LOAD` 时是否先立即尝试一次 sync 补发。true=当前 tick 先试，只有目标尚未真正就绪时才延后到下一 tick 重试；false=始终先延后一 tick。
			# en: Whether target-chunk-load sync replay should try immediately first. true tries in the current tick and only defers when the target is not ready yet; false always defers by one tick first.
			crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst=true

			# crosschunk.syncSourceAttachReplay.enabled
			# zh: 是否启用来源节点重新 attach 时的 sync 恢复。true=来源重新上线后，按当前来源状态向其已链接的 `core` 重新发布一次 sync；默认关闭，避免与放置后的真实输入派发重复。
			# en: Whether to replay sync when a triggerSource re-attaches. true republishes one sync recovery to linked cores using the source's current state; disabled by default to avoid duplicating real placement/input dispatches.
			crosschunk.syncSourceAttachReplay.enabled=false

			# crosschunk.directSyncBatching
			# zh: loaded `SYNC` 的批提交模式：off=loaded sync 一律立即生效，queued_only=仅异步/队列链路进入批提交，all_sync=loaded direct/async sync 全部进入目标级批提交；默认 queued_only。
			# en: Batching mode for loaded `SYNC`: off=all loaded sync stays immediate, queued_only=only async/queued loaded sync uses batching, all_sync=all loaded direct/async sync goes through target-level batching.
			crosschunk.directSyncBatching=queued_only

			# crosschunk.dispatch.batchWindowTicks
			# zh: `core` 目标级批提交的 flush 窗口（tick）。0=保持当前 tick 末提交；1=延后一 tick 提交；2+ 仅建议用于高稳定性实验，不建议默认开启。
			# en: Flush window in ticks for target-level `core` batching. 0 keeps end-of-tick flush, 1 delays by one tick, and 2+ is intended only for high-stability experiments.
			crosschunk.dispatch.batchWindowTicks=0

			# crosschunk.activation.pulse.relay.enabled
			# zh: 是否启用 PULSE 事件的普通 TTL relay。false=目标未加载时直接跳过。
			# en: Whether PULSE events use normal TTL relay. false means skip when target is unloaded.
			crosschunk.activation.pulse.relay.enabled=false

			# crosschunk.activation.pulse.ttlTicks
			# zh: PULSE 事件进入普通 relay 后的 TTL（tick）。
			# en: TTL in ticks for PULSE events queued through normal relay.
			crosschunk.activation.pulse.ttlTicks=200

			# crosschunk.activation.pulse.persistentExperimental
			# zh: 是否启用 PULSE 事件实验性不限时投递。true=可无限期等待目标加载后补发一次脉冲。
			# en: Enable experimental unlimited delivery for PULSE events. true means wait indefinitely and replay one pulse after target loads.
			crosschunk.activation.pulse.persistentExperimental=false

			# crosschunk.activation.toggle.relay.enabled
			# zh: 是否启用 TOGGLE 事件的普通 TTL relay。false=目标未加载时直接跳过。
			# en: Whether TOGGLE events use normal TTL relay. false means skip when target is unloaded.
			crosschunk.activation.toggle.relay.enabled=false

			# crosschunk.activation.toggle.ttlTicks
			# zh: TOGGLE 事件进入普通 relay 后的 TTL（tick）。
			# en: TTL in ticks for TOGGLE events queued through normal relay.
			crosschunk.activation.toggle.ttlTicks=200

			# crosschunk.activation.toggle.persistentExperimental
			# zh: 是否启用 TOGGLE 事件实验性不限时投递。true=可无限期等待目标加载后按净奇偶补发。
			# en: Enable experimental unlimited delivery for TOGGLE events. true means wait indefinitely and replay by net parity after target loads.
			crosschunk.activation.toggle.persistentExperimental=false

			# ----- [失效处理 / Invalidation] ---------------------------------------
			# crosschunk.triggerSourceContextDetachInvalidation.enabled
			# zh: 是否启用 triggerSource 的 soft/context-detach 失效。true=来源仅因上下文脱附（如区块活动）时，也会剔除目标上的 sync 贡献并重算；默认关闭，推荐稳定玩法保持 false。
			# en: Enable soft/context-detach invalidation for triggerSource. true removes sync contribution when the source only detaches from world context (such as chunk activity); disabled by default for the stable gameplay mode.
			crosschunk.triggerSourceContextDetachInvalidation.enabled=false
			# zh: triggerSource 的 hard invalidation 固定开启，不再提供独立配置项。
			# en: Hard invalidation for triggerSource is always enabled and is no longer configurable.

			# ----- [队列与重试 / Queue & Retry] ------------------------------------
			# crosschunk.queue.enabled
			# zh: 是否启用跨区块持久派发队列总开关。
			# en: Whether the persisted cross-chunk dispatch queue is enabled.
			crosschunk.queue.enabled=true

			# crosschunk.queue.defaultTtlTicks
			# zh: 跨区块持久派发队列通用 TTL（tick），主要用于未使用专属 TTL 的队列路径。
			# en: Default TTL in ticks for the persisted cross-chunk dispatch queue.
			crosschunk.queue.defaultTtlTicks=200

			# crosschunk.dispatch.maxPerTick
			# zh: 每 tick 从跨区块持久队列最多处理的条目数，范围 1~20000。
			# en: Maximum persisted cross-chunk queue entries processed per tick, range 1~20000.
			crosschunk.dispatch.maxPerTick=500

			# crosschunk.retry.warnThreshold
			# zh: 派发失败重试告警阈值（次数），0=关闭。
			# en: Warning threshold for dispatch retry failures (attempts), 0=disabled.
			crosschunk.retry.warnThreshold=200

			# crosschunk.retry.errorThreshold
			# zh: 派发失败重试错误阈值（次数），0=关闭。
			# en: Error threshold for dispatch retry failures (attempts), 0=disabled.
			crosschunk.retry.errorThreshold=1000

			# crosschunk.retry.dropThreshold
			# zh: 非持久事件派发失败重试丢弃阈值（次数），0=关闭。
			# en: Drop threshold for non-persistent retry failures, 0=disabled.
			crosschunk.retry.dropThreshold=2000

			# crosschunk.retry.stage1.maxAttempts
			# zh: 第 1 段最大失败次数（含）。默认 1~99 次失败使用第 1 段间隔。
			# en: Inclusive max attempts for retry stage 1. By default failures 1~99 use stage 1 interval.
			crosschunk.retry.stage1.maxAttempts=99

			# crosschunk.retry.stage1.intervalTicks
			# zh: 第 1 段重试间隔（tick）。
			# en: Retry interval in ticks for stage 1.
			crosschunk.retry.stage1.intervalTicks=1

			# crosschunk.retry.stage2.maxAttempts
			# zh: 第 2 段最大失败次数（含）。默认 100~499 次失败使用第 2 段间隔。
			# en: Inclusive max attempts for retry stage 2. By default failures 100~499 use stage 2 interval.
			crosschunk.retry.stage2.maxAttempts=499

			# crosschunk.retry.stage2.intervalTicks
			# zh: 第 2 段重试间隔（tick）。
			# en: Retry interval in ticks for stage 2.
			crosschunk.retry.stage2.intervalTicks=5

			# crosschunk.retry.stage3.maxAttempts
			# zh: 第 3 段最大失败次数（含）。默认 500~999 次失败使用第 3 段间隔。
			# en: Inclusive max attempts for retry stage 3. By default failures 500~999 use stage 3 interval.
			crosschunk.retry.stage3.maxAttempts=999

			# crosschunk.retry.stage3.intervalTicks
			# zh: 第 3 段重试间隔（tick）。
			# en: Retry interval in ticks for stage 3.
			crosschunk.retry.stage3.intervalTicks=20

			# crosschunk.retry.stage4.intervalTicks
			# zh: 第 4 段重试间隔（tick）。默认 >=1000 次失败使用该间隔。
			# en: Retry interval in ticks for stage 4. By default failures >=1000 use this interval.
			crosschunk.retry.stage4.intervalTicks=100

			# ----- [强制加载 / Force Load] -----------------------------------------
			# crosschunk.forceLoad.enabled
			# zh: 是否允许命中白名单时触发强制加载。
			# en: Whether force-load is allowed when whitelist matches.
			crosschunk.forceLoad.enabled=true

			# crosschunk.forceLoad.mode
			# zh: 强制加载模式，all=不依赖白名单，whitelist=仅白名单/preset 生效。
			# en: Force-load mode, all or whitelist.
			crosschunk.forceLoad.mode=whitelist

			# crosschunk.forceLoad.ticketTicks
			# zh: 强制加载票据保活时长（tick）。
			# en: Lifetime of force-load ticket in ticks.
			crosschunk.forceLoad.ticketTicks=80

			# crosschunk.forceLoad.maxPerTick
			# zh: 每 tick 最多执行的强制加载请求数量。
			# en: Maximum force-load requests per tick.
			crosschunk.forceLoad.maxPerTick=256

			# crosschunk.forceLoad.maxPerSourcePerTick
			# zh: 每个来源每 tick 最多执行的强制加载请求数量。
			# en: Maximum force-load requests per source per tick.
			crosschunk.forceLoad.maxPerSourcePerTick=256

			# ----- [命令与提示 / Command & Notify] ---------------------------------
			# crosschunk.command.enabled
			# zh: 是否启用 /redstonelink crosschunk 命令树。
			# en: Whether /redstonelink crosschunk command tree is enabled.
			crosschunk.command.enabled=true

			# crosschunk.command.permissionLevel
			# zh: /redstonelink crosschunk 命令所需权限等级（0~4）。
			# en: Permission level required for /redstonelink crosschunk commands.
			crosschunk.command.permissionLevel=2

			# crosschunk.notify.enabled
			# zh: 是否启用跨区块接管生效提示。
			# en: Whether to notify when cross-chunk takeover is accepted.
			crosschunk.notify.enabled=true

			# crosschunk.notify.mode
			# zh: 跨区块提示模式：simple/detailed。
			# en: Cross-chunk notify mode: simple/detailed.
			crosschunk.notify.mode=simple

			# ----- [诊断 / Diagnostics] --------------------------------------------
			# crosschunk.diag.runtime.enabled
			# zh: 是否启用运行时慢路径诊断日志（区块生命周期、退役处理、sync fanout）。
			# en: Enable runtime slow-path diagnostics for chunk lifecycle, retire flow, and sync fanout.
			crosschunk.diag.runtime.enabled=false

			# crosschunk.diag.runtime.warnThresholdMs
			# zh: 运行时慢路径诊断阈值（毫秒），达到阈值才输出日志。
			# en: Runtime slow-path logging threshold in milliseconds.
			crosschunk.diag.runtime.warnThresholdMs=25

			# crosschunk.diag.runtime.fanoutCounters.enabled
			# zh: 是否在 sync_fanout_slow 日志中输出 fanout 计数（delta/total）。
			# en: Whether to include fanout counter fields (delta/total) in sync_fanout_slow logs.
			crosschunk.diag.runtime.fanoutCounters.enabled=false

			""";
	}
}
