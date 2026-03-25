# bench批次D_run_bench_suite拆分与结果统一

生成时间：2026-03-25 23:29:58
文件名：2026-03-25_232958_bench批次D_run_bench_suite拆分与结果统一.md

## 任务背景

bench 批次 C 已完成 `run-bench.ps1` 的模块化拆分，单 case 执行引擎已经收口为“入口壳 + lib 模块”。下一步进入批次 D，继续处理 suite 层：

- 拆分 `run-bench-suite.ps1`
- 统一 suite summary 的结果结构

本轮目标不是改变外部命令用法，而是把当前“世界管理、服端启动、RCON 停服、模组同步、结果归并”堆在一个脚本内的结构收口，为后续继续扩展 suite 体系提供稳定边界。

## 方案详情

### 现状分析

- `tools/bench/run-bench-suite.ps1` 目前承担：
  - suite 配置与 case 列表解析
  - matrix/template 读取
  - 世界复制与 `server.properties` 修改
  - dedicated server 启停、RCON 就绪与停服
  - 可选 `remapJar + mod jar` 同步
  - case 结果发现、summary 写回与 serial compare
- 结果结构虽然已经包含 `entryId/caseId/status/resultPath` 等字段，但功能项、性能项、重启项之间仍有“字段只在某些分支存在”的问题，后续机读和分析会继续变脆。

### 技术方案

1. 新增 `tools/bench/lib/BenchSuite.*.ps1` 模块：
- `BenchSuite.Config.ps1`
  - UTF-8 文件读写
  - suite/matrix/template 读取
  - suite entries 解析
  - 路径解析与默认值处理
- `BenchSuite.World.ps1`
  - case world 名称生成
  - 世界复制
  - `server.properties` 中 `level-name` 修改
- `BenchSuite.Server.ps1`
  - RCON 连接/发包
  - dedicated server 启停
  - RCON ready、stop、进程退出等待
- `BenchSuite.ModSync.ps1`
  - gradle build
  - 本地 jar 解析
  - 服务器 mods 目录同步
- `BenchSuite.Results.ps1`
  - bench 结果文件发现
  - result json / summary 摘要读取
  - serial compare
  - suite summary 写出
  - 公共结果骨架构建

2. `run-bench-suite.ps1` 保留为轻量入口壳：
- 参数定义
- 全局路径与运行期变量
- dot-source `BenchSuite.*`
- 主入口调度

3. 统一 suite summary 结果结构
- 每个 `summary.results[*]` 固定具备以下公共字段：
  - `entryId`
  - `caseId`
  - `scenarioId`
  - `layer`
  - `benchAction`
  - `matrixPath`
  - `worldName`
  - `worldLevelName`
  - `worldPath`
  - `worldReuseSource`
  - `status`
  - `startedAt`
  - `completedAt`
  - `resultKind`
  - `checks`
  - `passed`
  - `activityResults`
  - `serialCompare`
  - `resultPath`
- 规则：
  - 功能项没有 spark 时 `activityResults` 仍写空对象，不省略字段
  - 非重启项 `serialCompare` 写 `null`，不再只在重启项存在
  - 性能项 `checks/passed` 明确允许为 `null`

### 影响范围

- `tools/bench/run-bench-suite.ps1`
- `tools/bench/lib/BenchSuite.Config.ps1`
- `tools/bench/lib/BenchSuite.World.ps1`
- `tools/bench/lib/BenchSuite.Server.ps1`
- `tools/bench/lib/BenchSuite.ModSync.ps1`
- `tools/bench/lib/BenchSuite.Results.ps1`

### 语义对齐说明

- 命名是否仅使用 `triggerSource/core`：本轮只重组 bench suite 脚本，不新增业务命名输出。
- 输入/输出结构是否保持方向一致：不涉及业务方向写入逻辑。
- 文案与注释是否无旧术语泄漏：只增加 suite 工具层注释，不新增旧业务术语输出。
- 与 `LinkNodeType` 映射是否一一对应：无改动。
- 兼容层是否仅“读旧写新”：不涉及存档兼容。
- 残余风险：结果结构统一后，依赖旧字段形态的外部分析脚本若写死字段存在性，可能需要同步适配；通过 `first-mandatory` 回归和 summary 实际输出验证兜底。

## 原子步骤清单

### 步骤 1：建立 BenchSuite 模块骨架
- **操作对象**：`tools/bench/lib/BenchSuite.*.ps1`
- **具体动作**：新增 Config / World / Server / ModSync / Results 五个模块文件
- **预期结果**：`run-bench-suite.ps1` 后续函数迁移有稳定落点
- **关键里程碑**：是

### 步骤 2：迁出 suite 配置、世界、服端、mod sync 逻辑
- **操作对象**：`run-bench-suite.ps1`、`BenchSuite.Config.ps1`、`BenchSuite.World.ps1`、`BenchSuite.Server.ps1`、`BenchSuite.ModSync.ps1`
- **具体动作**：按职责分组迁移函数
- **预期结果**：suite 主脚本不再直接承载底层实现细节
- **关键里程碑**：是

### 步骤 3：统一结果结构并迁出结果模块
- **操作对象**：`BenchSuite.Results.ps1`
- **具体动作**：迁出结果发现、summary 写入、serial compare，并构建统一结果骨架
- **预期结果**：功能/性能/重启项的 summary 结构可机读、可比较
- **关键里程碑**：是

### 步骤 4：重写 run-bench-suite 主入口
- **操作对象**：`tools/bench/run-bench-suite.ps1`
- **具体动作**：只保留参数、全局状态、模块加载和主入口调度
- **预期结果**：外部 CLI 不变，内部结构轻量化
- **关键里程碑**：是

### 步骤 5：执行验证
- **操作对象**：suite 入口、首批必跑、Gradle 测试
- **具体动作**：跑 `first-mandatory` 或至少 `smoke + first-mandatory`，并执行全标签自动化测试
- **预期结果**：确认批次 D 没有破坏 suite 编排链路
- **关键里程碑**：是

## 预期结果

- `run-bench-suite.ps1` 收口为入口壳
- suite 的世界管理、服端生命周期、mod 同步、结果归并边界清晰
- `summary.json` 的结果结构更稳定，便于后续分析和报告导出
