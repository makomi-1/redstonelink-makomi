# target chunk load replay 立即尝试优先开关

生成时间：2026-03-28 14:34:30
文件名：2026-03-28_143430_target_chunk_load_replay_立即尝试优先开关.md

## 任务背景

当前 `target chunk load replay` 在 `CHUNK_LOAD` 命中后，会统一先进入生命周期服务内部的本地延迟队列，再从下一 tick 开始消费。这样虽然能规避“区块刚加载但目标方块实体尚未完全就绪”的时序问题，但也会让已经就绪的目标无条件多出 1 tick 恢复延迟。

本次目标是在不改变 crosschunk queue 主职责的前提下，把该行为收敛为“可选策略开关”，并将默认策略改为“立即尝试，失败再延后”。

## 方案详情

### 现状分析

- `crosschunk.syncTargetChunkLoadReplay.enabled` 当前是唯一总开关，只决定是否启用 target chunk load replay。
- `LinkNodeLifecycleDispatchEvents` 在 `CHUNK_LOAD` 路径里对目标节点统一执行 `enqueueTargetChunkLoadReplay(...)`，不会先判断当前 tick 是否已经可以立即恢复。
- 本地 deferred replay 队列本身具备重试能力，适合兜底“当前 tick 尚未完全就绪”的场景，但不适合成为默认唯一路径。

### 技术方案

新增一个跨区块配置项：

- `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst`
- 默认值：`true`

行为调整为：

1. 若 `crosschunk.syncTargetChunkLoadReplay.enabled=false`，保持不做该类 replay。
2. 若总开关开启且 `immediateAttemptFirst=true`：
   - `CHUNK_LOAD` 时先立即尝试消费一次 target chunk load replay。
   - 若立即尝试返回 `DEFERRED`，再落入现有本地延迟队列，沿用最多 40 次重试。
3. 若总开关开启且 `immediateAttemptFirst=false`：
   - 保持当前保守行为，统一延后一 tick 再消费。

### 影响范围

- 配置快照结构：`src/main/java/com/makomi/config/RedstoneLinkCrossChunkConfig.java`
- 配置解析：`src/main/java/com/makomi/config/RedstoneLinkCrossChunkConfigParser.java`
- 默认配置模板：`src/main/java/com/makomi/config/RedstoneLinkConfigTemplate.java`
- 生命周期 replay 路径：`src/main/java/com/makomi/data/LinkNodeLifecycleDispatchEvents.java`
- 配置解析测试：`src/test/java/com/makomi/config/RedstoneLinkConfigInteractionAndCrossChunkParseTest.java`
- 使用说明：`docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：补配置结构与解析
- **操作对象**：`RedstoneLinkCrossChunkConfig.java`、`RedstoneLinkCrossChunkConfigParser.java`
- **具体动作**：新增 `syncTargetChunkLoadReplayImmediateAttemptFirst` 布尔配置，并在 parser 中解析，默认 `true`
- **预期结果**：运行时可读取到新的 replay 策略开关
- **关键里程碑**：是

### 步骤 2：补默认模板文案
- **操作对象**：`RedstoneLinkConfigTemplate.java`
- **具体动作**：新增配置模板项与双语说明，明确“默认立即尝试，失败再延后”
- **预期结果**：默认配置文件与说明文案一致
- **关键里程碑**：否

### 步骤 3：调整 lifecycle replay 策略
- **操作对象**：`LinkNodeLifecycleDispatchEvents.java`
- **具体动作**：在 `CHUNK_LOAD` 路径增加 fast-path；默认先立即尝试消费，只有未就绪时再入本地重试队列
- **预期结果**：已就绪目标不再被固定额外延后 1 tick，未就绪目标仍保留重试兜底
- **关键里程碑**：是

### 步骤 4：补回归测试
- **操作对象**：`RedstoneLinkConfigInteractionAndCrossChunkParseTest.java`
- **具体动作**：新增默认值、显式关闭、非法值回退的解析断言
- **预期结果**：配置兼容性受自动化保护
- **关键里程碑**：否

### 步骤 5：补使用说明
- **操作对象**：`docs_dev/使用说明.md`
- **具体动作**：补充该配置项的使用方式与默认行为说明
- **预期结果**：使用者可以根据需要切回保守模式
- **关键里程碑**：否

## 预期结果

- 默认配置下，`target chunk load replay` 会在 `CHUNK_LOAD` 当 tick 优先立即尝试恢复。
- 仅在目标仍未真正就绪时，才回退到生命周期服务内部的下一 tick 本地重试机制。
- 保守行为仍可通过配置显式恢复，不破坏当前 crosschunk queue 与 replay 语义边界。
