# run-bench单功能用例需显式指定functional-matrix

## 阻塞点

用 `run-bench.ps1 -Action PrintCase` 或 `-Action RunFunctionalCase` 直接跑新增 functional case 时，如果不显式传 `-MatrixPath`，脚本默认读取 `tools/bench/matrix.json`，会表现成查不到刚注册到 `tools/bench/functional-matrix.json` 的用例。

## 解决方法

对单 functional case 的调试命令统一显式传：

```powershell
& '.\tools\bench\run-bench.ps1' `
  -Action PrintCase `
  -MatrixPath '.\tools\bench\functional-matrix.json' `
  -CaseId 'send_filter_runtime_nodeset_reconcile'
```

或：

```powershell
& '.\tools\bench\run-bench.ps1' `
  -Action RunFunctionalCase `
  -MatrixPath '.\tools\bench\functional-matrix.json' `
  -CaseId 'receive_filter_runtime_nodeset_reconcile' `
  -DryRun
```

## 适用场景

- 新增 functional case 后做 `PrintCase`
- 单 case `DryRun`
- 不走 suite，只想单独调某条 bench functional 用例

## 结果

按这个口径修正后，`x32/x33` 的 `PrintCase` 与 `DryRun` 都能正常解析并进入 functional 执行链路。
