# 调整 restart mixed 回放断言并补充跨 tick replay 时间键专项

生成时间：2026-04-14 19:18:52
文件名：2026-04-14_191852_调整restart_mixed回放断言并补充跨tick_replay时间键专项.md

## 任务背景

当前 dedicated 游戏内回归显示：
- `x22/x23` mixed direct 同窗口仲裁已经符合“同 tick 优先级仲裁”的现语义。
- `r05` 仍沿用旧断言，要求“移除 sync 后回落到 toggle”，与最新简化模型“sync 移除不回落事件信号”不一致。
- 现有 restart mixed 专项没有直接覆盖“sync replay 必须沿用旧时间键，否则会误伤更晚事件信号”的跨 tick 语义。

## 方案详情

### 现状分析

- `tools/bench/cases/functional/r05/restart_mixed_replay_redstone_verify.json` 的断言口径仍基于旧模型。
- `target attach replay` 读取的是来源侧 replay 快照时间键；需要 dedicated 专项把“旧 sync replay 不得覆盖更晚 toggle”固化下来。
- 现有 functional matrix 已包含 `x25/r05` mixed restart 专项，但没有对应的跨 tick replay 时间键专项。

### 技术方案

1. 直接更新 `r05` 的 verify 断言，使其符合最新模型：
   - 重启后 `sync` 仍先恢复；
   - 移除 `sync` 后直接变为 `none/0`；
   - 不再期待回落到 `toggle`。
2. 新增一组 dedicated functional prepare/verify case：
   - prepare 先制造“较早 sync，再制造较晚 toggle”的跨 tick mixed 状态；
   - verify 复用世界重启后，断言目标仍保持较晚 toggle，而不是被旧 sync replay 覆盖。
3. 将新 case 注册到 functional matrix，并补一个对应 suite，便于后续定向 dedicated 回归。

### 影响范围

- `tools/bench/cases/functional/r05/**`
- `tools/bench/cases/functional/x37/**`（新增）
- `tools/bench/cases/functional/r06/**`（新增）
- `tools/bench/functional-matrix.json`
- `tools/bench/suites/**`（新增专项 suite）

## 原子步骤清单

### 步骤 1：更新旧断言
- **操作对象**：`tools/bench/cases/functional/r05/restart_mixed_replay_redstone_verify.json`
- **具体动作**：把“移除 sync 后回落到 toggle”的断言改成“移除 sync 后直接 none/0”
- **预期结果**：旧 mixed restart 专项与最新信号模型一致
- **关键里程碑**：是

### 步骤 2：补跨 tick replay 时间键专项
- **操作对象**：`tools/bench/cases/functional/x37/**`、`tools/bench/cases/functional/r06/**`
- **具体动作**：新增 prepare/verify case，构造“早 sync、晚 toggle、重启后仍应保留 toggle”的 dedicated 场景
- **预期结果**：把 replay 时间键语义固化为可回归的游戏内断言
- **关键里程碑**：是

### 步骤 3：注册 matrix 与 suite
- **操作对象**：`tools/bench/functional-matrix.json`、`tools/bench/suites/*.json`
- **具体动作**：把新 case 加入 functional matrix，并新增可直接运行的 restart replay 时间键专项 suite
- **预期结果**：后续可以直接 bench 定向复跑
- **关键里程碑**：否

### 步骤 4：执行验证
- **操作对象**：Gradle 自动化测试与 dedicated bench suite
- **具体动作**：跑 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`，再跑 `restart-mixed-replay` 与新增 suite
- **预期结果**：确认旧断言已对齐、新专项通过
- **关键里程碑**：是

## 预期结果

- `restart-mixed-replay` 不再因旧断言误报失败。
- 新增 dedicated 用例可以稳定验证“跨 tick replay 必须沿用旧时间键”。
- 语义对齐说明：
  - 命名继续使用 `triggerSource/core`
  - 方向仍为 `triggerSource -> core`
  - 文案与结构无旧术语新增泄漏
  - `LinkNodeType.TRIGGER_SOURCE/CORE` 映射不变
  - 兼容层保持“读旧写新”
  - 残余风险：若 bench 模板对红石块边沿时序的观测仍有偏差，可能需要后续再微调等待 tick 数；当前先按实现设计落断言
