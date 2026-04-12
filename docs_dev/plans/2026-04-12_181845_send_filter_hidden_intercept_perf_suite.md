# send filter 隐藏拦截态性能边界 suite

生成时间：2026-04-12 18:18:45
文件名：2026-04-12_181845_send_filter_hidden_intercept_perf_suite.md

## 任务背景
- 当前已确认将“send 过滤器隐藏拦截态 / 重进后重演”视为设计空白边界场景，不按功能错误处理。
- 本轮目标只关注性能侧是否存在异常热点、卡顿或 spark 指标异常，不关注该边界场景下的功能正确性。
- 用户要求将该场景沉淀为可重复执行的游戏内 bench 用例，并在实现后实际跑通，同时写入保留问题。

## 方案详情

### 现状分析
- 现有 `tools/bench/run-bench.ps1` 的 `RunCase` 会自动采集 spark，适合本轮“只看性能”的目标。
- 现有 `tools/bench/run-bench-suite.ps1` 支持 dedicated server 单 entry 独立起停，并支持 `reuseWorldFrom`，适合表达 `a -> b -> 重进 a 的 c`。
- `RunCase` 支持 `clearArena=false` 与 `reuseExisting=true`，因此 `c` 可以复用 `a` 留下的物理世界态，而不是重新摆放。
- `docs_dev/保留问题.md` 已启用自动更新，可直接把该边界场景记为持续观察项。

### 技术方案
- 新增一组 `p27` performance case，统一使用 `triggerSource/core` 术语。
- `a` 使用持久物理高电平驱动单个 `sync triggerSource`，并在 `core` 邻接 `send filter` 上配置 `nodeSetMode=blocklist + signalThresholdSource=neighbor_max_input + signalMode=upper_bound`，构造最小自反馈拦截边界。
- `b` 复用同一拓扑与过滤器配置，将输入改为 `2 tick` 周期方波，仅观察 spark 是否出现异常热点。
- `c` 复用 `a` 的世界，关闭清场并对现有 `triggerSource/core` 执行 `reuseExisting`，只做重启后的持续观察，不重新建链、不重新放置。
- 新增 dedicated server suite，把 `a -> b -> c` 固定为一条可重放链路。
- 将该组 case 纳入 `tools/bench/matrix-crosschunk-stress.json`，并同步更新 `docs/使用说明.md` 与 `docs_dev/保留问题.md`。

### 影响范围
- `tools/bench/cases/performance/p27/`
- `tools/bench/suites/`
- `tools/bench/matrix-crosschunk-stress.json`
- `docs/使用说明.md`
- `docs_dev/保留问题.md`
- `docs_dev/reviews/`

### 技术取舍
- 不做功能断言型 `functional case`，避免把设计空白问题错误地固化成功能基线。
- `a/c` 采用物理世界持久高电平，保证“重进 a”时世界态可以真实复现；`b` 采用 bench 输入驱动，直接复用现有 2 tick 方波模板语义，减少额外红石结构噪声。
- 矩阵继续挂在 `crosschunk-stress` 下，作为“边界专项压力观察”而不是常规功能矩阵处理。

### 预期结果与风险
- 预期新增 3 个可执行 performance case 和 1 个 suite，可在 dedicated server 上直接完成 spark 采集。
- 预期文档明确该场景的验收口径仅为“spark 性能是否异常”，不以功能结果作为通过条件。
- 风险在于该边界场景本身属于设计空白，运行后可能出现隐藏锁存或显示不同步；本轮只记录为保留问题，不额外修实现。

### 语义对齐说明
- 命名仅使用 `triggerSource/core`：是。
- 输入/输出方向保持 `triggerSource -> core`：是。
- 文案与注释无旧术语泄漏：按新增内容控制为是。
- 与 `LinkNodeType.TRIGGER_SOURCE/CORE` 映射是否一一对应：是。
- 兼容层是否仅“读旧写新”：本轮不涉及兼容层改动。
- 残余风险：无。

## 原子步骤清单

### 步骤 1：新增 p27 performance case
- **操作对象**：`tools/bench/cases/performance/p27/*.json`
- **具体动作**：新增 `a/b/c` 三个 case，分别表达持久输入、2 tick 方波输入、重启后复用世界复测。
- **预期结果**：bench 能识别并打印三组 case 摘要。
- **关键里程碑**：是

### 步骤 2：新增 suite 并接入矩阵
- **操作对象**：`tools/bench/suites/*.json`、`tools/bench/matrix-crosschunk-stress.json`
- **具体动作**：新增 dedicated server suite，串起 `a -> b -> c`，并将新增 case 挂入 crosschunk stress 矩阵。
- **预期结果**：单 case 与 suite 两条执行入口都可发现新场景。
- **关键里程碑**：是

### 步骤 3：更新使用说明与保留问题
- **操作对象**：`docs/使用说明.md`、`docs_dev/保留问题.md`
- **具体动作**：补充运行命令、验收口径、spark 取数说明，并把该边界场景写入保留问题。
- **预期结果**：用户可按文档直接复现，保留问题中有持续观察说明。
- **关键里程碑**：否

### 步骤 4：执行 dedicated server 实跑验证
- **操作对象**：Gradle 构建、bench suite 脚本、外部 mcserver
- **具体动作**：先构建当前工作区运行 jar，再同步 server jar，最后运行 suite 完成实际 spark 采集验证。
- **预期结果**：suite 至少完整跑通一次，能产出结果与 spark 链接。
- **关键里程碑**：是

### 步骤 5：补写审查文档
- **操作对象**：`docs_dev/reviews/YYYY-MM-DD_HHmmss_*.md`
- **具体动作**：按五段式审查模板记录本轮实现、数据流、风险与并发审查。
- **预期结果**：交付时具备完整审查留痕。
- **关键里程碑**：否

## 预期结果
- 仓库内新增一组可长期复用的 send filter 隐藏拦截态性能边界 case。
- dedicated server 能实际跑通 `a -> b -> c`，并把 spark 结果写入 bench 结果目录。
- 文档和保留问题同步更新，后续只需继续盯 spark 是否出现异常热点、MSPT 异常或健康报告恶化。
