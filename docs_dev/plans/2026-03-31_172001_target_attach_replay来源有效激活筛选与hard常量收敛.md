# target attach replay来源有效激活筛选与hard常量收敛

生成时间：2026-03-31 17:20:01
文件名：2026-03-31_172001_target_attach_replay来源有效激活筛选与hard常量收敛.md

## 任务背景

当前 `target attach replay` 已具备“仅对当前目标自己连接的来源补 `sync`”的基础能力，但来源筛选仍依赖现有生命周期副作用，没有一套显式的“来源有效激活”判定。  
同时，`crosschunk.triggerSourceHardInvalidation.enabled` 当前仍作为可配置项存在；本轮要求将其收敛为内部常量，固定为真，并放在“来源有效激活判断”文件中，而不是继续留在配置类中。

## 方案详情

### 现状分析

- `target attach replay` 当前入口位于 `LinkNodeLifecycleDispatchEvents`，只对当前 attach 的目标命中一次 replay。
- replay 内容当前仅恢复 `sync`，来源快照由 `SyncReplaySourceBlockEntity` 与 `LinkSavedData.triggerSourceReplaySyncSnapshots` 双通道维护。
- 软下线（区块卸载）与硬下线（方块放置态失效、破坏、挖掘）生命周期响应已存在，但 replay 资格尚未显式建模。
- `triggerSourceHardInvalidation` 运行时直接读取配置的调用点很少，适合本轮一并去配置化。

### 技术方案

- 新增一个专门的“来源有效激活判定”收口文件：
  - 定义内部 hard 常量，固定为真；
  - 定义 replay 资格口径；
  - 暴露统一的 `sync` 来源有效激活判定方法。
- `target attach replay` 由“直接对已连来源补发”改为：
  - 先按“来源有效激活”统一筛选；
  - 再仅对命中的 `sync` 来源解析一次最新快照并补发。
- 快照解析补充软下线 fallback：
  - 来源在线时优先读 live/block-entity snapshot；
  - 来源软下线但仍允许 replay 时，回退到 `LinkSavedData` 中的持久快照。
- 删掉 `crosschunk.triggerSourceHardInvalidation.enabled` 的配置字段、解析与模板项。
- `hard invalidation` 发布路径改为读取新判定文件中的内部常量。

### 影响范围

- 生命周期 replay：`LinkNodeLifecycleDispatchEvents`
- replay 构造与快照解析：`InternalDispatchDeltaRuleSupport`
- 失效发布：`InternalDispatchDeltaRuleSupport`
- 跨区块配置结构与解析：`RedstoneLinkCrossChunkConfig`、`RedstoneLinkCrossChunkConfigParser`、`RedstoneLinkConfigTemplate`
- 说明与测试：相关测试类及使用说明文档

## 原子步骤清单

### 步骤 1：新增来源有效激活判定收口
- **操作对象**：新建判定 helper 文件
- **具体动作**：定义 hard 常量、有效激活口径与统一判定方法
- **预期结果**：`sync` 来源 replay 资格有统一入口
- **关键里程碑**：是

### 步骤 2：改造target attach replay来源筛选
- **操作对象**：`LinkNodeLifecycleDispatchEvents`
- **具体动作**：在 replay 前逐源应用有效激活判定，再按命中的来源补发一次最新 `sync`
- **预期结果**：只对当前目标连接且满足口径的 `sync` 来源 replay
- **关键里程碑**：是

### 步骤 3：补齐软下线快照fallback
- **操作对象**：`InternalDispatchDeltaRuleSupport`
- **具体动作**：调整 replay snapshot 解析逻辑，允许软下线来源回退到 `LinkSavedData` 快照
- **预期结果**：区块卸载来源在允许口径下仍可恢复最新 `sync`
- **关键里程碑**：否

### 步骤 4：移除hard invalidation配置项
- **操作对象**：`RedstoneLinkCrossChunkConfig`、`RedstoneLinkCrossChunkConfigParser`、`RedstoneLinkConfigTemplate`
- **具体动作**：删除字段、解析与模板项，并将 hard invalidation 改为内部常量控制
- **预期结果**：hard invalidation 不再对外可配置
- **关键里程碑**：是

### 步骤 5：补测试并执行回归
- **操作对象**：相关 replay / lifecycle / invalidation 测试
- **具体动作**：覆盖非下线、非硬下线、软下线快照回放、硬下线过滤等场景，并执行全量自动化测试门禁
- **预期结果**：新增语义受测试保护
- **关键里程碑**：是

## 预期结果

- `target attach replay` 只对当前目标连接的、满足“来源有效激活”定义的 `sync` 来源补发一次最新信号。
- 软下线来源可在允许口径下继续利用已保留的最新 `sync` 快照参与 replay。
- `hard invalidation` 固定为内部常量真值，不再暴露为配置项。
