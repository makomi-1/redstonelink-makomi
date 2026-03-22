# bench suite 内层脚本参数 splat 修复

生成时间：2026-03-22 19:50:55
文件名：2026-03-22_195055_bench_suite内层脚本参数splat修复.md

## 任务背景
`run-bench-suite.ps1` 在调用内层 `run-bench.ps1` 时，把命名参数组装成了字符串数组，再用 `& $script @array` 调用。PowerShell 会把这类数组按位置参数展开，导致 `-Action` 被当作 `Action` 的实际值，最终触发 `ValidateSet` 校验失败。

## 方案详情

### 现状分析
- 外层 suite 已能正确完成模板世界复制、切换 `level-name`、启服与等待 RCON。
- 失败点仅出现在“进入内层单 case bench”这一跳。
- 内层 `run-bench.ps1` 的 `Action` 参数带 `ValidateSet("List", "PrintCase", "InstallDatapack", "RunCase")`，因此一旦 `-Action` 被误当作值就会立即报错。

### 技术方案
- 将 `run-bench-suite.ps1` 中的 `$benchArgs` 从字符串数组改为 hashtable。
- 保持原有参数集合不变，仅修正 PowerShell 的命名参数 splatting 方式。
- 不改内层 `run-bench.ps1` 行为，不改 bench 结果结构。

### 影响范围
- `tools/bench/run-bench-suite.ps1`

### 语义对齐说明
- 本次仅修复 PowerShell 调用方式，不涉及 `triggerSource/core` 业务语义变更。
- 未引入旧术语输出，也未新增 `com.makomi.api.v1.*` 依赖。
- 残余风险：无。

## 原子步骤清单

### 步骤 1：修正 suite 到单 case bench 的参数传递
- **操作对象**：`tools/bench/run-bench-suite.ps1`
- **具体动作**：把字符串数组 splat 改为 hashtable splat，并保留可选参数追加逻辑。
- **预期结果**：`RunCase` 能以命名参数方式正确进入内层 bench。
- **关键里程碑**：是

### 步骤 2：执行验证
- **操作对象**：PowerShell 语法检查与 Gradle 全标签测试
- **具体动作**：检查脚本可解析，并执行 `test/testIntegration/testClient/testSlow/testApiLegacy`。
- **预期结果**：修复可追溯，项目门禁通过。
- **关键里程碑**：否

## 预期结果
suite 在 dedicated server 启动并 RCON 就绪后，可以继续进入单 case bench，而不再因为 `Action` 参数校验失败中断。
