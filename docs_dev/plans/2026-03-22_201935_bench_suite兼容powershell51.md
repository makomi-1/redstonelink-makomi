# bench suite 兼容 powershell 5.1

生成时间：2026-03-22 20:19:35
文件名：2026-03-22_201935_bench_suite兼容powershell51.md

## 任务背景
`run-bench-suite.ps1` 在 `Get-CaseWorldLevelName` 中使用了 PowerShell 7 的三元表达式 `? :`。用户当前通过 Windows PowerShell 5.1 的 `powershell -File ...` 执行脚本，会把 `?` 解析为 `Where-Object` 别名，导致脚本在运行时抛错，无法继续进入 suite 主流程。

## 方案详情

### 现状分析
- 问题点仅在 `Get-CaseWorldLevelName` 的目录名标准化逻辑。
- 业务语义本身没有问题，只是写法不兼容 Windows PowerShell 5.1。
- 用户当前 bench 命令明确使用 `powershell`，因此需要保证脚本在 5.1 下可执行，而不是仅兼容 `pwsh`。

### 技术方案
- 将该处三元表达式改为显式 `if/else`。
- 保持原有目录标准化语义不变：
  1. 空目录名 -> `""`
  2. 非空目录名 -> 统一斜杠、去掉首尾 `/`
- 不修改 suite 其它行为，不调整 case 世界目录策略。

### 影响范围
- `tools/bench/run-bench-suite.ps1`

### 语义对齐说明
- 本次仅修复 PowerShell 运行时兼容性，不涉及 `triggerSource/core` 业务语义变化。
- 未引入旧术语输出，也未新增 `com.makomi.api.v1.*` 依赖。
- 残余风险：无。

## 原子步骤清单

### 步骤 1：替换不兼容语法
- **操作对象**：`tools/bench/run-bench-suite.ps1`
- **具体动作**：把 `Get-CaseWorldLevelName` 中的三元表达式改为 `if/else`。
- **预期结果**：脚本可在 Windows PowerShell 5.1 下正常执行。
- **关键里程碑**：是

### 步骤 2：执行验证
- **操作对象**：脚本运行烟测与 Gradle 回归测试
- **具体动作**：验证 suite 脚本可在当前 shell 下进入主流程，并执行全标签测试。
- **预期结果**：兼容性修复可追溯，回归门禁通过。
- **关键里程碑**：否

## 预期结果
用户可以继续使用 `powershell -ExecutionPolicy Bypass -File ...` 直接运行 `run-bench-suite.ps1`，不会再因 PowerShell 7 专用语法中断。
