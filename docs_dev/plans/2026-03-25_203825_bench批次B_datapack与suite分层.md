# bench批次B_datapack与suite分层

生成时间：2026-03-25 20:38:25
文件名：2026-03-25_203825_bench批次B_datapack与suite分层.md

## 任务背景

bench 批次 A 已完成定义层与模板层整理，`case/template/suite` 的基础结构已经稳定，`run-bench.ps1` 与 `run-bench-suite.ps1` 也具备消费新结构的能力。下一步进入批次 B，目标是继续收口：

- datapack 公共动作
- suite 分层正规化

本轮不提前拆 PowerShell 主执行引擎，避免与后续批次 C/D 的脚本重构边界重叠。

## 方案详情

### 现状分析

- datapack 目前只有：
  - `tools/bench/datapack/rl_bench/data/rl_bench/function/load.mcfunction`
  - `tools/bench/datapack/rl_bench/data/rl_bench/function/prepare/common.mcfunction`
  - `tools/bench/datapack/rl_bench/data/rl_bench/function/reset/common.mcfunction`
- suite 目前只有：
  - `tools/bench/suites/first-mandatory.json`
- bench 脚本当前仍通过：
  - `matrix.defaults.prepareFunctions`
  - `run-bench-suite.ps1 -SuitePath`
  驱动 datapack 与 suite，因此本轮适合采用“入口不变、内部收口”的方式。

### 技术方案

1. datapack 公共动作库化
- 新增最小可用的子目录与子函数：
  - `helper/common/*`
  - `fixture/clear/*`
  - `fixture/player/*`
  - `assert/state/*`
  - `assert/trace/*`
- 保留 `load/prepare/common/reset/common` 作为对外稳定入口，但内部改为 `function rl_bench:...` 调用子函数。
- 先把当前已经稳定存在的动作拆出来：
  - scoreboard 初始化
  - scoreboard 重置
  - bench 运行期实体清理
  - 世界稳定化 gamerule / time / weather

2. suite 六层正规化
- 新增 suite：
  - `smoke.json`
  - `functional-regression.json`
  - `restart-crosschunk.json`
  - `performance-baseline.json`
  - `nightly-full.json`
- 保留 `first-mandatory.json` 作为当前首批必跑入口。
- 六层结构统一通过 `defaults + entries` 组织，继续复用批次 A 建立的 suite 读取方式。

3. 使用说明同步
- 在 `docs_dev/使用说明.md` 中补充：
  - 六层 suite 的定位
  - 推荐的 `run-bench-suite.ps1 -SuitePath ...` 用法
  - `run-first-mandatory-suite*.ps1` 仍然只服务于首批必跑

### 影响范围

- `tools/bench/datapack/rl_bench/data/rl_bench/function/**`
- `tools/bench/suites/**`
- `docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：拆分 datapack 公共动作
- **操作对象**：`tools/bench/datapack/rl_bench/data/rl_bench/function/**`
- **具体动作**：新增 helper / fixture / assert 子函数，并重写 `load/prepare/common/reset/common` 为调度壳
- **预期结果**：datapack 公共动作具备后续扩展边界
- **关键里程碑**：是

### 步骤 2：建立 suite 六层结构
- **操作对象**：`tools/bench/suites/**`
- **具体动作**：新增 smoke、functional-regression、restart-crosschunk、performance-baseline、nightly-full
- **预期结果**：开发自测、首批回归、专项回归、性能基线、夜间全量具备独立 suite
- **关键里程碑**：是

### 步骤 3：补充使用说明
- **操作对象**：`docs_dev/使用说明.md`
- **具体动作**：写明 suite 分层与调用方式
- **预期结果**：suite 使用入口可查可复用
- **关键里程碑**：否

### 步骤 4：执行验证
- **操作对象**：suite 解析入口、Gradle 测试
- **具体动作**：验证新增 suite 可解析，并执行全标签自动化测试
- **预期结果**：确认批次 B 未破坏 bench 主流程
- **关键里程碑**：是

## 预期结果

- datapack 从“单文件堆动作”变成“入口壳 + 子函数目录”
- suite 从“只有首批必跑”变成“六层结构”
- 使用说明补齐 suite 分层入口
- 为批次 C 拆 `run-bench.ps1` 提前稳定好 datapack / suite 两端边界
