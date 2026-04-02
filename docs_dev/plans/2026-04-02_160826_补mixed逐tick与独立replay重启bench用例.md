# 补 mixed 逐tick与独立 replay 重启 bench 用例

生成时间：2026-04-02 16:08:26
文件名：2026-04-02_160826_补mixed逐tick与独立replay重启bench用例.md

## 任务背景

现有 functional mixed 用例仅覆盖 `sync` 自定义序列的弱特征场景，尚未验证运行态 `sync/pulse/toggle` 同场混合仲裁。此前曾考虑把 replay 绑定在 `triggerSource` 输入器末态延续上，但该路径会受到输入 job 进程生命周期影响，不适合作为 mixed replay 的稳定验证基础。

本次目标是把 mixed 验证拆成两条独立链路：

1. `all_direct + batchWindowTicks 可变` 的 mixed direct 逐 tick 验证。
2. 用红石块构造 mixed 并发态、跨重启后验证 replay/最终仲裁结果的 restart 场景。

## 方案详情

### 现状分析

- 现有 functional mixed 用例 `sync_triggerSource_custom_mixed_basic` 不是 runtime mixed。
- bench `trace_read_tick_assert` 已支持直接手写 `expectTicks`，足够表达 mixed 仲裁逐 tick 期望。
- bench case/suite 已支持参数化，可直接复用 `dispatchBatchWindowTicks=0/1/2`。
- `command_assert` 可直接发送 `setblock`，现有 crosschunk/replay 用例已证明“红石块驱动 + 重启复验”路径可行。
- `pulse` 默认持续时间仅 4 tick，不适合直接作为 restart 后稳定断言核心，需要在 replay 专项 suite 中局部放宽 `server.pulseDurationTicks`。

### 技术方案

#### 1. mixed direct 逐 tick 用例

- 新增 1 个 functional case，拓扑采用 `3 个 triggerSource -> 1 个 core`。
- 三个来源分别为 `sync_emitter`、`pulse_emitter`、`toggle_emitter`。
- 输入方式继续使用 `input_start_custom_triggerSource`，保持强特征：
  - `sync` 高电平固定 `15`
  - `pulse` 高电平固定 `14`
  - `toggle` 高电平固定 `13`
  - 低电平统一 `0`
- `triggerSource` 侧分别逐 tick 断言输入特征，`core` 侧手写 mixed `expectTicks`，校验 `effectiveMode/resolvedStrength/active`。
- case 自带 `dispatchBatchWindowTicks` 参数，占位用于对齐 `0/1/2` 三种窗口。

#### 2. mixed replay / restart 独立用例

- 新增 prepare/verify 成对 functional case。
- prepare 阶段不依赖输入 job，直接用红石块驱动各类 emitter，构造同一 `core` 上的 `sync/pulse/toggle` 并发贡献。
- verify 阶段复用同一世界，在重启后验证：
  - 当前可见最终态；
  - 去掉更高优先级贡献后是否退化到下一层仲裁结果；
  - `pulse` 到期后是否最终回到 `toggle` 稳定态。
- replay 专项 suite 局部覆盖更长 `server.pulseDurationTicks`，确保重启后 `pulse` 仍有足够观测窗口。

#### 3. suite 与矩阵接入

- 在 functional matrix 中注册新增 case。
- 新增 mixed direct debug suite：
  - `crosschunk.directBatching=all_direct`
  - `crosschunk.dispatch.batchWindowTicks={{param:dispatchBatchWindowTicks}}`
  - 依次执行窗口 `0/1/2`
- 新增 mixed replay restart suite：
  - 跑 prepare + verify
  - 复用 prepare 产生的世界
  - 覆盖较长 `server.pulseDurationTicks`

### 影响范围

- `tools/bench/cases/functional/**`
- `tools/bench/suites/**`
- `tools/bench/functional-matrix.json`
- `docs_dev/reviews/**`

## 原子步骤清单

### 步骤 1：新增 mixed direct 用例
- **操作对象**：`tools/bench/cases/functional/`
- **具体动作**：新增 mixed direct 逐 tick case，定义三类来源序列、trace 挂载与 `core` mixed 期望序列
- **预期结果**：可在 `all_direct + batchWindowTicks=0/1/2` 下复用同一 case 做逐 tick 验证
- **关键里程碑**：是

### 步骤 2：新增 mixed replay/restart 用例
- **操作对象**：`tools/bench/cases/functional/`
- **具体动作**：新增 prepare/verify 用例，使用红石块构造 mixed 并发态并在重启后验证
- **预期结果**：可稳定验证 mixed replay 的跨重启仲裁结果
- **关键里程碑**：是

### 步骤 3：注册矩阵并新增 suite
- **操作对象**：`tools/bench/functional-matrix.json`、`tools/bench/suites/`
- **具体动作**：注册新增 case，补 direct 与 replay 两个专项 suite
- **预期结果**：bench 可独立执行 direct 与 replay 两类 mixed 验证
- **关键里程碑**：是

### 步骤 4：执行验证并校正
- **操作对象**：新增 case/suite 与 bench 结果
- **具体动作**：运行专项 suite 与必要回归，若期望序列和真实仲裁不一致则最小修正测试数据
- **预期结果**：新增 mixed 测试稳定通过
- **关键里程碑**：是

### 步骤 5：编写审查文档
- **操作对象**：`docs_dev/reviews/`
- **具体动作**：按四段式要求记录实现细节、调用链、性能与风险
- **预期结果**：满足项目文档化要求
- **关键里程碑**：否

## 预期结果

- functional bench 新增 mixed direct 逐 tick 用例。
- functional bench 新增 mixed replay/restart 独立用例。
- 可分别按 `batchWindowTicks=0/1/2` 与跨重启路径执行 mixed 语义验证。
- 全过程继续统一使用 `triggerSource/core` 术语，不引入 API 依赖与运行时实现改动。
