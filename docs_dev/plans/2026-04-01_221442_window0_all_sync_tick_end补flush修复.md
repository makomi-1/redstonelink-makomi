# window0_all_sync_tick_end补flush修复

生成时间：2026-04-01 22:14:42
文件名：2026-04-01_221442_window0_all_sync_tick_end补flush修复.md

## 任务背景

`x21` 同区块延迟专项在 `crosschunk.directSyncBatching=all_sync` 且 `crosschunk.dispatch.batchWindowTicks=0` 时，当前结果表现为 `triggerSource` 延迟仍稳定为 `1 tick`，但 `core` 完全 `no_match`。结合历史成功结果与用户确认，本轮目标语义已经明确：

- `batchWindowTicks=0` 时，`core` 相对 `triggerSource` 的额外延迟必须固定为 `0 tick`
- `window=1/2` 的现有参数化专项结论必须保持不变

现状分析表明，问题不在 bench 参数化或 summary 命名，而在运行时 `all_sync + window=0` 的时序：当某次 direct `SYNC` 入队发生在当前 tick 的 `END_SERVER_TICK` 之后，它虽然仍处于同一个 `gameTime`，但只能等到下一次 tick 末才 flush，导致 `x21` 的 burst 首拍整体晚一拍。

## 方案详情

### 现状分析

- `CoreDispatchBatchScheduler` 当前只在 `END_SERVER_TICK` 统一 flush pending batch，没有记录“本 tick 的 END 是否已经执行过”。
- `InputPlaybackService.start/stop/clear` 会在正常 tick 钩子之外调用 `process(..., force=true)`，这类入口可能在当前 tick 的 END 之后继续产生新的 direct `SYNC` 入队。
- `window=0` 的设计目标是“保持当前 tick 末 flush，行为等价现状”；但对于 END 之后才入队的事件，现实现无法满足这一设计目的。
- 历史通过的 `x21_samechunk_latency_burst_debug` 并未启用 `crosschunk.directSyncBatching=all_sync`，因此不能证明现有 `all_sync + window=0` 语义正确。

### 技术方案

本轮采用“保留统一 scheduler，同时补 `window=0` 的 END 后补 flush”方案：

1. 在 `CoreDispatchBatchScheduler` 的服务端状态里记录最近一次已完成的 `END_SERVER_TICK` 的 tick 值。
2. 新增仅服务 `window=0` 语义的补 flush helper：
   - 仅当当前配置窗口为 `0`
   - 且当前 `gameTime` 的 `END_SERVER_TICK` 已经执行过
   - 才对当前服务端 pending batch 立即执行一次 flush
3. 该补 flush 不绕过 scheduler，仍让事件先进入 accumulator，再复用既有的 merge / 覆盖 / 排序 / batch apply 逻辑，避免把 `all_sync` 重新拆回 immediate 直投路径。
4. 在 `InputPlaybackService` 的 `start/stop/clear` 这些“正常 END 钩子之外强制推进输入”的入口，在调用 `process(..., force=true)` 后执行补 flush。
5. 补自动化测试，覆盖：
   - scheduler 能识别“本 tick END 已过”
   - `window=0` 时补 flush 生效
   - `window=1/2` 不受影响

### 影响范围

- `src/main/java/com/makomi/data/CoreDispatchBatchScheduler.java`
- `src/main/java/com/makomi/data/input/InputPlaybackService.java`
- `src/test/java/com/makomi/data/CoreDispatchBatchSchedulerTest.java`

### 技术取舍

- 不修改 bench 断言口径，而是修运行时时序。这样可以保住用户确认的语义目标，也避免为 `window=0` 增加专项特判。
- 不将 `window=0` 的 late signal 全部改成 direct immediate apply，而是保留 scheduler 入口与统一 batch 规约，降低语义分叉风险。
- 本轮只优先覆盖已暴露问题的“强制输入入口”，保持最小粒度实现；若后续发现其它 END 后入队路径，再按同一 helper 扩展。

## 原子步骤清单

### 步骤 1：补 scheduler 的 END tick 状态与补 flush helper
- **操作对象**：`CoreDispatchBatchScheduler`
- **具体动作**：在服务端状态中记录最近一次 END tick；新增仅对 `window=0` 生效的补 flush 方法，并复用现有 flush 主路径
- **预期结果**：scheduler 能识别“本 tick 正常 flush 机会已过”并做对齐补偿
- **关键里程碑**：是

### 步骤 2：在强制输入入口接入补 flush
- **操作对象**：`InputPlaybackService`
- **具体动作**：在 `start/stop/clear` 中的强制 `process` 后调用 scheduler 补 flush
- **预期结果**：`x21` burst 首拍不会再因为 END 后入队而整体晚一 tick
- **关键里程碑**：是

### 步骤 3：补自动化测试
- **操作对象**：`CoreDispatchBatchSchedulerTest`
- **具体动作**：新增 END 后入队 + `window=0` 的回归测试，并确认非零窗口不被误 flush
- **预期结果**：`window=0` 设计目的有单测保护
- **关键里程碑**：是

### 步骤 4：执行回归验证
- **操作对象**：Gradle 全标签测试与定向 bench
- **具体动作**：先执行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`，再定向复跑 `functional-samechunk-latency-debug`
- **预期结果**：`window=0` 恢复为 `core` 相对 `triggerSource` 固定 `0 tick`，`window=1/2` 保持原结论
- **关键里程碑**：是

## 预期结果

- `all_sync + batchWindowTicks=0` 下，`core` 相对 `triggerSource` 的额外延迟重新稳定为 `0 tick`
- `window=1/2` 的参数化专项结果不回退
- 不改变 `triggerSource -> core` 方向约束，不改变 `LinkNodeType.TRIGGER_SOURCE/CORE` 映射

## 语义对齐说明

- 命名校验：方案与新增实现统一使用 `triggerSource/core`
- 输入输出结构校验：仍只处理 `triggerSource -> core`
- 文案与注释校验：不引入旧术语
- 映射校验：不改 `LinkNodeType.TRIGGER_SOURCE/CORE` 映射
- 兼容层校验：只修运行时时序，不改 bench 输入/输出结构
- 残余风险：若仓库里存在其他“当前 tick END 之后才产生 loaded direct sync 入队”的入口，本轮不会自动全部覆盖；当前已知 `InputPlaybackService` 路径会被修复
