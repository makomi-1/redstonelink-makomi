# bench spark activity轮询采集修复

生成时间：2026-03-22 17:46:22
文件名：2026-03-22_174622_bench_spark_activity轮询采集修复.md

## 任务背景
bench 真实跑 dedicated server 时，`spark profiler stop` 与 `spark health --upload --memory` 已经成功执行，且结果能在游戏对话栏中看到，但 `run-bench.ps1` 结果 JSON 里的 `spark.start/stop/health` 仍为空。这说明当前脚本依赖的 RCON 同步返回通道并不稳定，无法可靠承接 spark 的最终上传结果。

## 方案详情

### 现状分析
- bench 当前通过 `Invoke-RconCommand` 直接读取一次命令返回并写入结果 JSON。
- `spark` 结果在 dedicated server 实测中会出现在玩家聊天/广播通道，而不是稳定出现在该次 RCON 同步响应体。
- 若仅继续拉长 RCON 超时时间，仍无法保证一定拿到 spark 的最终 URL 或 health 链接。

### 技术方案
- 保留现有 RCON 发命令路径，不改 bench 的命令调度方式。
- 新增 spark activity 文件定位与轮询逻辑：
  - 在发送 spark 命令前记录 activity 文件快照。
  - 发送 `spark profiler start`、`spark profiler stop --comment {case_id}`、`spark health --upload --memory` 后，轮询 activity 文件中新增的记录。
  - 从新增记录中提取 profiler/health 对应的 URL、类型、时间戳等结构化信息，再写回结果 JSON。
- 对外尽量保持现有命令行不变，只新增最小可选路径参数或自动推断逻辑。

### 影响范围
- `tools/bench/run-bench.ps1`
- `docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：补 spark activity 读取基础能力
- **操作对象**：`tools/bench/run-bench.ps1`
- **具体动作**：增加 activity 文件路径解析、JSON 读取、快照与新增记录筛选函数。
- **预期结果**：脚本能在不依赖 RCON 返回文本的情况下读取 spark 最新活动记录。
- **关键里程碑**：是

### 步骤 2：改造 spark 采集流程
- **操作对象**：`tools/bench/run-bench.ps1`
- **具体动作**：在 start/stop/health 前后接入快照与轮询逻辑，将结构化 spark 活动结果写入 bench 结果对象。
- **预期结果**：结果 JSON 中能稳定拿到 spark 采集结果，不再依赖聊天栏人工复制。
- **关键里程碑**：是

### 步骤 3：补充使用说明
- **操作对象**：`docs_dev/使用说明.md`
- **具体动作**：说明 dedicated server 场景下 spark 结果读取改为 activity 文件轮询，并标明路径约定。
- **预期结果**：用户可理解为什么 bench 不再依赖 RCON 回显。
- **关键里程碑**：否

### 步骤 4：执行针对性验证
- **操作对象**：`tools/bench/run-bench.ps1`
- **具体动作**：使用本地 DryRun 和静态读取验证流程，确认新增字段、编码和 diff 范围正常。
- **预期结果**：本地逻辑可运行，后续只需外部 dedicated server 做最终真机验证。
- **关键里程碑**：否

## 预期结果
bench 在 dedicated server + spark 场景下，可稳定把 spark 采样结果写入 `run/profiles/bench-results/*.json`，无需依赖 RCON 同步回包或手工从聊天栏抄链接。
