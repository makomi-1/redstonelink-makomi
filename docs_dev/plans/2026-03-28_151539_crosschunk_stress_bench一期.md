# crosschunk stress bench 一期

生成时间：2026-03-28 15:15:39
文件名：2026-03-28_151539_crosschunk_stress_bench一期.md

## 任务背景

当前 `bench` 已有大规模性能矩阵，但主要覆盖近原点、持续输入的 dense case。用户本次希望补一组更贴近 crosschunk 压力语义的专项压测，重点关注：

- 最小回环与 crosschunk 场景区分
- 持久化 `sync` 队列在目标重新加载时的集中释放
- 后续扩展到 resident / force-load 的 distinct chunk 压测
- 全部压测应避开出生点附近，减少原版常驻加载干扰

结合现状分析，一期最有价值的落点是先补 bench runner 的最小能力缺口，然后补两条 `sync` 持久队列释放基准：

- `single-shot`：离线期间制造一次真实状态切换，再 reload 目标
- `blank control`：复用同一套 unload/reload 编排，但不制造 pending release

## 方案详情

### 现状分析

- 现有性能矩阵 case 坐标仍靠近原点，不适合直接用于规避 spawn chunk 干扰。
- `RunCase` 会在放置前自动对 case 覆盖范围执行 `forceload add`，这会污染 crosschunk unload/reload 压测语义。
- 性能 `drive.steps` 当前只支持 `input_square`、`input_custom`、`activate_batch` 和旧的 `sync_square_wave`，不能承载 `wait + command` 组成的时间线。
- `sync` 持久队列是 latest-state 语义，不会无限累积整段历史波形；因此“1024 一次性释放”应设计成“1024 个 pending key 在目标 reload 后集中刷出”，而不是长时间离线高频灌输入。

### 技术方案

一期只补最小能力集，不先扩 resident / force-load 的稀疏布局能力：

1. 为 `RunCase` 增加可选开关 `autoLoadCaseChunks`，默认仍为 `true`
   - 仅在 crosschunk stress case 上显式设为 `false`
   - 保持现有性能 case 行为不变

2. 扩展性能 `drive.steps`
   - 新增 `wait_ticks`
   - 新增 `command_assert`
   - 复用现有 bench 命令断言与维度包装能力，让性能 case 也能描述 `卸载 -> 状态切换 -> reload -> 观测` 的时间线

3. 新增专用矩阵与 suite
   - 单独建立 `crosschunk-stress` 矩阵
   - 不复用 `performance-heavy` 的固定性能窗口
   - 继续保留 spark 采集，但测量区间由时间线 steps 自己定义

4. 新增两条一期 `sync` 用例
   - `sync_queue_release_1024_single_shot`
   - `sync_queue_release_1024_blank_control`
   - 统一远坐标，避开出生点附近
   - 只测 `sync`

### 影响范围

- bench world/chunk 预加载：`tools/bench/lib/Bench.World.ps1`
- bench 单 case 运行入口：`tools/bench/lib/Bench.EntryPoint.ps1`
- bench 性能时间线：`tools/bench/lib/Bench.PlaceAndLink.ps1`
- crosschunk stress 矩阵与 suite：`tools/bench/*.json`
- crosschunk stress case：`tools/bench/cases/performance/*`
- 使用说明：`docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：补 case chunk 预加载开关
- **操作对象**：`Bench.World.ps1`
- **具体动作**：为 `Ensure-CaseChunksLoaded` 增加 `autoLoadCaseChunks` 判定，默认保持现状，仅在专项 case 关闭
- **预期结果**：crosschunk stress case 不再被 bench 自身的 `forceload add` 污染
- **关键里程碑**：是

### 步骤 2：扩展性能时间线能力
- **操作对象**：`Bench.PlaceAndLink.ps1`
- **具体动作**：为 `drive.steps` 增加 `wait_ticks`、`command_assert` 支持，并记录执行结果
- **预期结果**：性能 case 可以直接表达 unload/reload/状态切换时间线
- **关键里程碑**：是

### 步骤 3：接入新时间线结果到 RunCase
- **操作对象**：`Bench.EntryPoint.ps1`
- **具体动作**：保持现有 spark 与结果汇总路径，兼容混合 drive steps 的执行摘要
- **预期结果**：新 case 能沿用现有 `RunCase` 输出结构
- **关键里程碑**：否

### 步骤 4：新增 crosschunk stress 矩阵、suite 与两条一期用例
- **操作对象**：`tools/bench/matrix-crosschunk-stress.json`、`tools/bench/suites/crosschunk-stress-sync.json`、新 case 文件
- **具体动作**：新增远坐标 `sync` 持久队列释放与空白对照 case
- **预期结果**：可直接批跑一期 crosschunk stress
- **关键里程碑**：是

### 步骤 5：补使用说明
- **操作对象**：`docs_dev/使用说明.md`
- **具体动作**：补一期 crosschunk stress 命令入口和两条 case 用途说明
- **预期结果**：后续可直接复跑与对照分析
- **关键里程碑**：否

### 步骤 6：执行回归验证
- **操作对象**：bench 相关脚本与 Gradle 测试任务
- **具体动作**：先做 bench 静态校验，再执行全量自动化测试门禁
- **预期结果**：确认一期改动没有破坏原有 bench 与 mod 回归面
- **关键里程碑**：是

## 预期结果

- `RunCase` 可以显式关闭默认 case chunk 预加载。
- 性能 case 可以通过 `drive.steps` 直接描述 `wait + command + input` 混合时间线。
- 项目内新增一组专用的 crosschunk stress 入口，先覆盖 `sync` 持久队列释放与空白对照。
- 后续 resident / force-load / 最小回环专项可以在同一 runner 能力上继续扩展，而不需要重做 bench 基础设施。
