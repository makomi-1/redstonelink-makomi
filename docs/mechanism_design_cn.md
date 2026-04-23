# RedstoneLink 统一机制设计说明

## 1. 文档目的

这份文档不按“命令、方块、配置”分散介绍，而是按同一条内部主线解释 RedstoneLink 的核心机制：

`拓扑写入 -> 事件派发 -> 在线直达 / 跨区块接管 -> 目标批调度 -> core 仲裁 -> 状态观测 / 回放恢复`

这样可以把仲裁模型、跨区块机制、批打包机制、回放机制、读写控制机制放在同一张设计图里理解，而不是把它们当成互相独立的零件。

## 2. 统一语义基线

### 2.1 节点语义

- `triggerSource`：来源节点，只负责产生触发或同步信号。
- `core`：目标节点，只负责接收并决议最终状态。
- 内部真实写入方向固定为 `triggerSource -> core`。
- `core` 视角的编辑、GUI、quick-link 批量改链，本质上都只是“以 core 为观察中心”构造若干条正向写入计划，最终仍会回落到 `triggerSource -> core` 真值。

### 2.2 真值层分工

- `LinkSavedData`：连接拓扑、序号分配、运行态版本号、sync replay 快照的世界级真值。
- `CrossChunkDispatchQueueSavedData`：跨区块 pending 派发的持久真值。
- `ActivatableTargetBlockEntity`：单个 `core` 目标端的运行态真值，负责真正的仲裁和输出状态。
- `NodeSnapshotQueryService`：对外读模型，不直接制造真值，只负责把身份、连接、运行态、revision、跨区块身份组装成可读快照。

换句话说，拓扑真值、跨区块真值、目标运行态真值、外部读模型是四层，不混写。

## 3. 总体架构图

```text
编辑入口 / 输入入口
  -> 写控与 OCC 校验
  -> LinkSavedData 拓扑变更
  -> InternalDispatchDeltaEvents 增量事件
  -> LinkedTargetDispatchService
     -> 目标已加载：直接命中目标 / 进入目标批调度
     -> 目标未加载：CrossChunkDispatchService 接管
        -> pending 队列 / force-load / resident
  -> CoreDispatchBatchScheduler
  -> ActivatableTargetBlockEntity
     -> sync 来源桶 + pulse/toggle 事件快照
     -> 时间优先 + 同 tick 固定优先级仲裁
  -> 方块状态、红石输出、观测快照

生命周期附着 / 区块加载
  -> LinkNodeLifecycleDispatchEvents
  -> target attach replay / source attach replay / 加载后自愈
```

## 4. 仲裁模型

### 4.1 核心思想

仲裁发生在单个 `core` 目标端，而不是全局总线上。  
目标端不再把三类信号都视为同一种“来源长期贡献”，而是拆成两层真值：

- `sync`：状态信号，维护来源级聚合桶。
- `pulse/toggle`：事件信号，只保留目标本地事件结果快照。

每条事件都带 `EventMeta`，其中核心时间键是 `tick + slot`，并辅以 `seq` 处理同时间粒度下的稳定顺序。

### 4.2 决议规则

- 第一层：先比较时间，新时间覆盖旧时间。
- 第二层：同一时间粒度内按固定优先级决议，规则是 `SYNC > PULSE > TOGGLE`。
- 第三层：同优先级再按 `seq` 取较新者，确保结果可重复。

### 4.3 三类语义如何合并

- `sync`：状态信号，不是“最后写入覆盖”，而是按来源强度聚合，取 `max` 作为最终同步强度，同时保留并列最大来源列表供观测；它享有来源级失效、补发与 replay 服务，也是默认与推荐的红石机器主链路。
- `pulse`：事件信号，维护目标本地的有效脉冲窗口；它会覆盖更早的事件结果，但同 tick 或更晚的 `sync` 会清掉其事件持久化。
- `toggle`：事件信号，语义是“对当前目标解析状态取反”；它只保留最新事件结果，不再作为来源级 fallback 长期存活。
- `pulse/toggle` 共用一个事件域：同 tick 只保留一个事件结果，优先级固定为 `PULSE > TOGGLE`；更晚事件会覆盖更早事件。
- `sync` 失效时只回退到仍存在的 `sync`，不会回露已被清掉的旧 `pulse/toggle` 事件。

### 4.4 失效语义

- 默认配置即推荐模型：`triggerSource` 的 `hard invalidation` 固定开启；来源离线、解绑、退役、删除等真实下线会撤掉该来源在目标上的 `sync` 贡献。
- 区块活动本身不等于来源逻辑失效：区块暂时未加载、未活跃或仅发生 context detach，不会自动把来源判成无效；默认恢复主链是 `sync` 的 `target chunk load replay`。
- `pulse/toggle` 不享有来源级 relay/replay；它们的 relay 默认关闭，仅保留兼容或实验入口。

这保证了“同步状态”和“事件语义”不会被同一种失效逻辑粗暴混为一类。

## 5. 跨区块机制

### 5.1 接管原则

`LinkedTargetDispatchService` 负责把“来源序号 -> 目标集合”转换为具体派发：

- 目标区块已加载：直接命中目标实体。
- 目标区块未加载：交给 `CrossChunkDispatchService` 决定是否接管。

### 5.2 接管形态

跨区块接管并不是单一机制，而是三层组合：

- `pending queue`：把待派发事件写入持久队列，等待目标恢复。
- `force-load`：为目标区块临时挂票据，尽快把目标拉起后投递。
- `resident`：长期持有白名单目标，适合常驻跨区块链路。

票据粒度按“目标中心区块”计算，但这不等于物理上只加载一个区块。当前 `force-load` 与 `resident` 底层都对目标中心区块使用 `addRegionTicket(..., radius=2, ...)`，目标是让中心区块达到 `ENTITY_TICKING` 级别；原版区块系统会为该 region ticket 拉起周边支撑区块。因此负载估算应按“中心区块票据 + 周边支撑加载”理解，而不是按严格单区块理解。即使把半径降为 `radius=1`，也只是把中心区块需求降到更低 tick 级别，并不保证物理只加载一个区块。

边界方块的邻居更新也不会绕过这条边界：跨区块邻居扇出在相邻区块未加载时会直接跳过，不会仅因方块处在区块边界就主动强拉相邻区块。若相邻区块被实际更新，说明它已经被玩家、票据或其它加载来源拉起。

### 5.2.1 区块激活器并入有效白名单

`PlacedChunkActivatorSavedData` 独立持久化每个区块激活器的 `triggerSource/core` 两套节点集、当前作用类型、模式、别名与激活态：

- 激活态下，当前作用类型对应的节点集会并入跨区块有效白名单。
- `force-load` 模式贡献的是强加载资格；`resident` 模式会额外并入 resident 常驻集合。
- 这层设计只改变“后续跨区块接管是否允许、resident 是否持续保活”，不会单独制造一条新的 `sync` 事件。
- 普通区块卸载不会删除这份真值；只有物理破坏时才移除条目。

### 5.3 队列真值

`CrossChunkDispatchQueueSavedData` 以 `sourceType/sourceSerial/targetType/targetSerial/dispatchKind` 为 key 保存 pending 条目：

- 同 key upsert，只保留最新意图，不无限堆叠重复事件。
- 为每个 key 分配单调 version，用 accepted/issued version 护栏过滤旧包和过期重试。
- 过期清理由最小堆驱动，不做每 tick 全量扫描。

### 5.4 生命周期协同

当前版本不依赖全局 `CHUNK_LOAD/CHUNK_UNLOAD` 扫描，而是由节点实例主动上报 attach/detach，再由 `LinkNodeLifecycleDispatchEvents` 在服务端 tick 内按预算消费：

- 节点 attach 后补在线登记。
- target attach 时尝试 replay 与 queue release。
- source attach 时按配置决定是否做一次 sync 恢复。
- detach 时按配置发布来源失效事件。

这种设计的核心目标，是把高成本的块加载判定和恢复逻辑从热路径中搬出去。

## 6. 批打包机制

### 6.1 为什么需要批打包

直接逐条把事件打到目标实体，会带来两个问题：

- 同一目标在同一 tick 内被多次重复重算。
- loaded direct、crosschunk ready release、lifecycle replay 三条链路的同 tick 行为难以统一。

`CoreDispatchBatchScheduler` 的作用，就是把这些来源统一收束到“目标级批提交”。

### 6.2 调度规则

- `window=0`：仍保持当前 tick 对齐，但允许 `END_SERVER_TICK` 之后的 late-arrival 补一次 flush。
- `window>=1`：按目标级 `dueTick` 建桶，形成固定延迟批次。
- 同一 `dueTick` 内：
  - 同源同 kind 只保留最新条目；
  - `ACTIVATION` 还会按 `activationMode` 进一步分桶；
  - `TRIGGER_SOURCE_INVALIDATION` 可以覆盖同桶内更早的普通事件。

### 6.3 批次落点

批次真正落地时，统一调用 `core.applyDispatchBatch(...)`：

- 先按 `timeKey -> seq -> deltaPriority` 排序；
- 再批末一次性重算 sync/pulse/toggle 真值；
- 最后只写回一次派生态和观测态。

所以“批打包”不是单纯为了减少调用次数，而是为了把同一目标的裁决语义固定下来。

## 7. 回放机制

### 7.1 回放不等于输入播放

项目里至少有三种容易混淆的“回放”：

- `target chunk load replay`：目标区块加载后，把来源最近一次真实 `sync` 状态补发给目标。
- `source attach replay`：来源重新附着后，按配置补发一次当前 `sync` 状态。
- `InputPlaybackService`：运行时/bench 用的波形输入播放服务，用来驱动模拟输入 job。

前两者属于生命周期恢复，后一者属于测试/运行态输入注入。默认配置下，真正承担跨区块恢复主链的是 `sync` replay；`pulse/toggle` 不参与这条主链。

### 7.2 sync replay 快照

`SyncReplaySourceBlockEntity` 会记录最近一次真实 `sync` 派发快照：

- 强度 `signalStrength`
- 原始时间键 `tick/slot/seq`

这份快照既保存在 block entity，也同步落到 `LinkSavedData`，用于“来源区块不在线，但目标区块刚加载”的恢复场景。

### 7.3 target chunk load replay

目标 attach 时，`LinkNodeLifecycleDispatchEvents` 会：

1. 读取当前目标关联的来源集合。
2. 用 `TriggerSourceEffectiveActivationPolicy` 过滤出具备 replay 资格的 `sync` 来源。
3. 按来源最近一次真实快照补发，而不是把“目标加载时刻”伪装成事件发生时刻。

这保证 replay 恢复的是“原始来源状态”，不是“加载瞬间的新事件”。  
对默认配置来说，这也是推荐的 `sync` relay/recovery 方式，不需要把 `pulse` 纳入 relay 才能实现稳定跨区块状态链。

### 7.4 sync 自动补发的真实触发条件

对 `sync` 来说，自动补发的触发条件不是“有 resident/临时强加载”本身，而是“确实生成了一次离线 `sync` 传播事件”：

- `force-load` / `resident` 票据只负责把目标区块变成可处理；真正会被 release / replay 的，是那条已经入队或已经生成的离线 `sync` 事件。
- 新连接建立且目标离线时，会尝试 attach replay；若目标仍离线，则转入 pending，后续自动补发。
- 来源信号在目标离线期间发生 `0 -> 15`、`15 -> 0` 或强度变化时，会生成新的离线 `sync` 传播事件；后续目标被拉起或自然上线后自动落地。


### 7.5 非阻塞原则

回放链路明确避免在启动关键路径做阻塞式取块：

- 热路径读取优先使用非阻塞探测。
- chunk 未真正就绪时选择 defer/retry，而不是同步等待。
- 启动期不允许因为 replay 把服务器卡死在 prepare/load 流程里。

这是当前跨区块恢复设计的一条硬边界。

### 7.6 过滤器运行时重算

发送/接收过滤器不是另一套拓扑真值，而是附着在派发链路上的筛选层：

- `send` 过滤器只服务 `triggerSource`。
- `receive` 过滤器只服务 `core`。
- 过滤器本身不改变真实写入方向，仍然只是在 `triggerSource -> core` 路径上决定“这次派发是否继续向前”。

已放置过滤器的持续真值由 `PlacedLinkFilterSavedData` 维护，它不是“只有区块在线时才存在”的临时状态：

- 过滤器只要仍处于放置态，就会以持久化条目形式参与求值。
- 作用范围不是球体，而是以过滤器方块为中心、`X/Y/Z` 各方向半径 `8` 格的正方体。
- 运行期配置变化和邻居输入变化都会走重采样，再比较 before/after 结果。

同时，过滤器还维护一条独立于运行时真值的“配置快照”链路：

- 手持过滤器编辑时，读写的是物品 NBT 中的过滤器配置快照。
- 放置时会把这份快照恢复到方块实体与 `PlacedLinkFilterSavedData`；挖起掉落时，再把当前生效配置写回掉落物。
- 物品 tooltip 展示的也是这份配置快照，包括节点集；它只是配置载体，不是另一套连接图真值。

对于运行态补偿，当前版本只对 `sync` 做失效/补发协调：

- 原来放行、现在拦截：发一次 `sync` 失效，把旧贡献撤掉。
- 原来拦截、现在放行：按当前 replay/snapshot 补发一次 `sync`。
- `pulse/toggle` 不补历史事件，只影响后续新派发。

## 8. 读写控制机制

### 8.1 读控制

读侧由 `CurrentLinksPrivacyService` 统一处理，核心模式是：

- `plain`：明文可见。
- `masked`：按受控名单局部隐藏目标。
- `hidden`：整体隐藏。

`NodeSnapshotQueryService` 只负责把隐私裁剪后的当前连接、节点身份、运行态快照、revision 与跨区块身份组装出来。  
物品 tooltip/NBT 快照则走单独的 item snapshot 口径，不与玩家实时读权限完全等价。

### 8.2 写控制

写侧由 `LinkWriteControlService` 统一判定：

- `full`：完全放行。
- `limited`：限制单次设置量，超限需要更高权限。
- `readonly`：拒绝真实写入。
- `protected` 名单：命中受控序号时需要额外权限。

### 8.3 统一写入闭环

真正的拓扑覆盖写入统一收口到 `LinkSetExecutionService`：

- 校验来源与目标合法性；
- 做写控判定；
- 更新 `LinkSavedData`；
- 发布 attach/detach delta；
- 同步节点快照与物品快照；
- 返回结构化反馈。

quick-link、pairing、bench 提交并不是各写一套底层逻辑，而是尽量复用这条共享写入闭环。

### 8.4 过滤器配置写入与 quick-link

过滤器编辑需要单独和“改链”区分：

- 过滤器 GUI 保存、手持过滤器编辑保存与 quick-link 命中过滤器，本质上都是“改过滤器配置”，不是“改连接图”。
- 对手持过滤器，写入目标是物品侧配置快照；对已放置过滤器，写入目标是方块实体配置并同步到持久化过滤器真值。
- 这里写入的是过滤器自己的 `serialExpression/nodeSetMode/signalThresholdSource/fixedSignalThreshold/signalMode` 快照，而不是 `triggerSource -> core` 拓扑。
- quick-link 命中过滤器时，只覆盖 `serialExpression`，其余过滤器配置保持原样。
- 也正因为写的是配置快照，过滤器可以形成“手持编辑 -> 放置生效 -> 掉落保留 -> 重放置恢复”的闭环，而不触碰连接图 revision。

因此它和普通 quick-link 改链共用前端交互形态，但不共用同一套权限/OCC 语义：

- 命中节点：继续走 `LinkSetExecutionService + LinkWriteControlService + LinkOccSupport`。
- 命中过滤器：改为按 `server.command.permissionLevel` 校验权限，不走 link write control，也不参与 link OCC。
- 这正是因为过滤器配置写入不会改动连接图 revision，只会改变筛选层真值。

### 8.5 OCC 冲突控制

为避免“先读后写”期间的静默覆盖，项目额外引入 revision 基线：

- `graphRevision`：全图级版本。
- `sourceRevision`：单个 `triggerSource` 的版本。
- `coreRevision`：单个 `core` 成员集合版本。

`LinkOccSupport` 统一提供冲突判定：

- `triggerSource` 视角提交比较 `sourceRevision`。
- `core` 视角提交比较 `coreRevision`。
- 冲突时直接拒绝本次提交，要求重新读取，而不是后台悄悄重放旧输入。

所以写控解决“你能不能写”，OCC 解决“你基于的旧快照是否还可信”，两者职责不同。

## 9. 统一工作流示例

### 9.1 改链

`pairing / quick-link / command`
-> 写控校验
-> OCC 校验
-> `LinkSetExecutionService`
-> `LinkSavedData`
-> attach/detach delta
-> 后续派发与快照同步

### 9.2 在线触发

`triggerSource` 产生事件
-> `LinkedTargetDispatchService`
-> 已加载目标直接命中
-> `CoreDispatchBatchScheduler`
-> `ActivatableTargetBlockEntity`
-> 输出最终红石状态

### 9.3 离线恢复

目标未加载
-> `CrossChunkDispatchService` 接管
-> pending queue / force-load / resident
-> 目标 attach
-> queue release 或 target chunk load replay
-> 重新进入批调度与仲裁

## 10. 设计取舍总结

这个机制体系的核心不是“功能堆叠”，而是把不同问题分层处理：

- `LinkSavedData` 解决拓扑真值。
- `CrossChunkDispatchQueueSavedData` 解决离线目标恢复。
- `CoreDispatchBatchScheduler` 解决同目标同窗口批归并。
- `ActivatableTargetBlockEntity` 解决最终仲裁。
- `CurrentLinksPrivacyService + LinkWriteControlService + LinkOccSupport` 解决读控制、写控制与并发冲突。

统一之后，可以把系统概括成一句话：

RedstoneLink 以 `triggerSource -> core` 为唯一真实方向，以“目标端仲裁”为最终收敛点，以“跨区块接管 + 生命周期回放”为恢复手段，再用“读控 / 写控 / OCC”把外部访问约束在同一套可验证边界内。
