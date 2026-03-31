# crosschunk ready drain与缓存接入3_4级

生成时间：2026-03-31 22:20:11
文件名：2026-03-31_222011_crosschunk_ready_drain与缓存接入3_4级.md

## 任务背景

当前 `triggerSource -> core` 的 1/2 级改动已经完成：

1. `core` 端具备批提交能力；
2. 生命周期 replay 与 crosschunk ready release 已能进入异步目标级 batch scheduler。

但 `crosschunk runtime` 仍保留“逐条 pending -> 逐条定位目标 chunk/block entity -> 逐条提交”的执行方式。这意味着：

1. 同一 tick 内多个 ready pending 命中同一 chunk / 同一 `core` 时，仍会重复做目标定位；
2. ready 后的 drain 仍是逐条投递，尚未把强制加载命中的这一波 pending 做成组处理；
3. scheduler 前没有短生命周期缓存，无法进一步压缩 runtime 层的重复开销。

本轮按已确认范围实现第 3/4 级：

1. 完整实现“ready 后按 chunk 收集、按 `core` 分组、一次性 drain”；
2. 实现两项短缓存：
   - `TargetLocatorCache`
   - `ChunkReadyDrainCache`
3. 本轮不新增独立 `BatchNoopGuard`。

## 方案详情

### 现状分析

1. `CrossChunkDispatchRuntimeSupport.processPendingDispatches(...)` 当前仍在主循环内逐条调用 `tryDispatch(...)`。
2. `tryDispatch(...)` 每条 pending 都会重新执行 `server.getLevel -> isLoaded -> getChunkNow -> getBlockEntity`。
3. `CoreDispatchBatchScheduler` 虽已能把同一 `core` 的多条异步 SYNC / invalidation 合成一次批提交，但其上游仍是逐条 enqueue。
4. `dispatch.maxPerTick` 当前按访问/处理的 pending 条数生效，已有 bench 与运行时语义依赖这一口径。

### 技术方案

1. 在 `CrossChunkDispatchRuntimeSupport` 内补一组 tick 级短缓存对象：
   - `TargetLocatorCache`：缓存同一 tick 内对目标 `core` 的定位结果；
   - `ChunkReadyDrainCache`：缓存同一 tick 内 ready pending 的 chunk/core 分组结果。
2. 将 `tryDispatch(...)` 拆成“两段式”：
   - 先做目标解析，产出 `READY_TARGET / RETRYABLE_MISS / INVALID_TARGET` 三态；
   - ready 时不立即投递，而是先放入 drain cache。
3. 在主循环末统一 drain：
   - 对支持 batching 的 kind，按 `core` 批量写入 `CoreDispatchBatchScheduler`；
   - 对不支持 batching 的 `ACTIVATION/toggle/pulse`，保持 direct apply。
4. 在 `CoreDispatchBatchScheduler` 中新增批量 enqueue 入口，避免 ready drain 后仍逐条重复走外层 key 构造与查桶流程。
5. 保持 `dispatch.maxPerTick` 语义不变：
   - 预算仍按 pending 条数递减；
   - 本轮只压缩 ready 后的定位与提交成本，不改变调度公平性口径。

### 影响范围

1. `src/main/java/com/makomi/data/CrossChunkDispatchRuntimeSupport.java`
2. `src/main/java/com/makomi/data/CoreDispatchBatchScheduler.java`
3. `src/test/java/com/makomi/data/CrossChunkDispatchServiceTest.java`
4. 视测试需要，可能补充一个新的 scheduler 侧测试文件

## 原子步骤清单

### 步骤 1：抽出目标定位结果模型与定位缓存
- **操作对象**：`CrossChunkDispatchRuntimeSupport`
- **具体动作**：新增 target 定位结果模型、ready/miss/invalid 三态和 `TargetLocatorCache`
- **预期结果**：同 tick 内同目标不再重复解析 `chunk/block entity`
- **关键里程碑**：是

### 步骤 2：把 ready pending 改为“收集 -> 分组 -> drain”
- **操作对象**：`CrossChunkDispatchRuntimeSupport`
- **具体动作**：新增 `ChunkReadyDrainCache`，将 ready pending 从主循环中的逐条 dispatch 改为循环末统一 drain
- **预期结果**：ready 后的 pending 能按 chunk/core 成组处理
- **关键里程碑**：是

### 步骤 3：扩展 scheduler 的批量 enqueue
- **操作对象**：`CoreDispatchBatchScheduler`
- **具体动作**：新增同一 `core` 的批量 enqueue 入口，复用既有 merge/flush 规则
- **预期结果**：runtime drain 不再逐条重复进入 scheduler
- **关键里程碑**：是

### 步骤 4：补齐测试与回归
- **操作对象**：`CrossChunkDispatchServiceTest` 及相关测试文件
- **具体动作**：覆盖 ready drain、target locator cache、scheduler 批量 enqueue 的回归场景
- **预期结果**：3/4 级行为可自动化验证
- **关键里程碑**：是

## 预期结果

1. 强制加载命中后，目标 chunk ready 的同一 tick 内，runtime 不再逐条重复定位目标。
2. 同 chunk 多 pending 会先聚合，再按 `core` 一次性写入批调度器或 direct apply。
3. `core` 级 batch 的收益继续保留，同时减少 scheduler 上游的重复 map merge。
4. `dispatch.maxPerTick`、`triggerSource -> core` 方向语义和现有 API 边界保持不变。

## 语义对齐说明

1. 命名继续统一使用 `triggerSource/core`，不引入旧术语回流。
2. 方向保持 `triggerSource -> core` 单向，不新增反向写入路径。
3. 不改变 `LinkNodeType.TRIGGER_SOURCE/CORE` 的内部映射关系。
4. 本轮仅改内部调度与缓存，不引入 `com.makomi.api.v1.*` 依赖。
5. 残余风险：
   - `dispatch.maxPerTick` 仍按 pending 数而不是 grouped `core` 数计量；
   - `ACTIVATION/toggle/pulse` 仍保持事件语义 direct 路径，本轮不参与状态批合并。
