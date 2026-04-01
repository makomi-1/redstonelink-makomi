# crosschunk新增P25目标replay压测

生成时间：2026-04-01 13:06:33
文件名：2026-04-01_130633_crosschunk新增P25目标replay压测.md

## 任务背景

现有 `crosschunk-stress` 已有：

- `P20`：`1 -> 1024` 的 sync queue release 压测。
- `P21`：复用同时间线的 blank control。
- `X07/X08`：单点验证 `syncTargetChunkLoadReplay` 开启/关闭时的 reload replay 语义。

当前缺口是：还没有一条专门隔离“`1 -> 1024` 目标 reload replay”成本的 performance case。用户希望补一条参考 `P20` 拓扑的专项，用来对比“不靠 queue/pending，而是主要靠 target replay 恢复”的内存、GC 与热点特征。

## 方案详情

### 现状分析

- `P20` 通过 `crosschunk.queue.enabled=true`、`crosschunk.syncSignalPersistent=true` 且关闭 replay，测的是 pending queue release。
- `X07` 已证明在 queue 关闭时，目标 unload 期间发生的 sync 最新态可以在 reload 后由 `syncTargetChunkLoadReplay` 恢复。
- 现有 `matrix-crosschunk-stress.json` 已具备 spark 自动采集链路；只要把新 case 接入 matrix，就能直接复用现有运行脚本。
- 当前还不需要把新例并入 `crosschunk-stress-full.json`；先单独验证其隔离价值更符合最小改动原则。

### 技术方案

1. 新增 `P25` performance case，拓扑复用 `P20` 的 `1 -> 1024` broadcast_all。
2. 时间线复用 `P20` 的 unload -> 离线切高 -> reload -> 观察窗口骨架，但语义改为“由 target replay 恢复高态”。
3. 运行时配置通过 matrix entry 显式固定为：
   - `crosschunk.queue.enabled=false`
   - `crosschunk.syncSignalPersistent=false`
   - `crosschunk.syncTargetChunkLoadReplay.enabled=true`
   - `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst=true`
   - `crosschunk.forceLoad.enabled=false`
4. 实施完成后先跑一次新例并读取 spark 结果，再跑与 replay 语义直接相关的定向功能回归。

### 影响范围

- `tools/bench/cases/performance/p25/sync_target_reload_replay_1024_single_shot.json`
- `tools/bench/matrix-crosschunk-stress.json`

### 语义对齐说明

- 命名仅新增 bench 场景文案，不涉及业务术语输出；内部仍保持 `triggerSource -> core` 方向基线。
- 本轮不改任何运行时代码或 `LinkNodeType.TRIGGER_SOURCE/CORE` 映射，仅补一条 bench case 与 matrix 入口。
- 新 case 的目标是隔离 reload replay 链路，不改变 queue、force-load、direct sync 的既有对外语义。
- 残余风险：单次性能结果仍可能受游戏内噪声影响；若后续需要严格扣除 reload 本底，再补 replay blank control。

## 原子步骤清单

### 步骤 1：新增 P25 case 文件
- **操作对象**：`tools/bench/cases/performance/p25/sync_target_reload_replay_1024_single_shot.json`
- **具体动作**：参考 `P20` 复制 `1 -> 1024` 拓扑和 unload/reload 时间线，改为 replay 导向的描述、坐标和 case id。
- **预期结果**：得到一条专门压 `syncTargetChunkLoadReplay` 的 1024 目标 reload replay 压测。
- **关键里程碑**：是

### 步骤 2：接入 stress matrix
- **操作对象**：`tools/bench/matrix-crosschunk-stress.json`
- **具体动作**：把 `P25` case path 加入 matrix，便于直接通过现有 `run-bench.ps1`/suite 链路执行。
- **预期结果**：新例可被 bench 工具识别并运行。
- **关键里程碑**：是

### 步骤 3：执行一次新例性能分析
- **操作对象**：bench 运行结果与 spark 报告
- **具体动作**：跑一次 `P25`，提取 profiler/health 链接和关键内存、GC 指标，必要时与既有 `P20/P21` 结果对照。
- **预期结果**：确认 replay 导向压测的热点与内存特征。
- **关键里程碑**：是

### 步骤 4：执行定向功能回归
- **操作对象**：与 replay 相关的功能专项
- **具体动作**：运行 `X07/X08` 等定向 case，确认 replay 开关语义仍正确。
- **预期结果**：新增压测配置不会引入功能层面的回归。
- **关键里程碑**：是

## 预期结果

- 补齐一条与 `P20` 同拓扑但不同恢复链路的 replay performance case。
- 能直接用现有 spark 采集链路观察 reload replay 的堆占用、GC 和热点分布。
- 保持 `triggerSource -> core` 语义与既有运行时代码不变。
