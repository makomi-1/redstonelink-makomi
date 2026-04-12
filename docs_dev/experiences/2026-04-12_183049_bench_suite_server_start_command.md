# bench suite 缺省启动命令阻塞处置

生成时间：2026-04-12 18:30:49
文件名：2026-04-12_183049_bench_suite_server_start_command.md

## 阻塞点
- 首次执行 `run-bench-suite.ps1` 时，构建和 server jar 同步均已成功，但 suite 在 dedicated server 启动前直接失败。
- 失败信息为：`ServerStartCommand is required.`

## 原因定位
- 当前仓库的 `bench.path-config.json` 只提供了 `serverRoot` 与模板世界路径，不会自动补出 dedicated server 启动命令。
- 因此 suite 入口在未显式传入 `-ServerStartCommand` 时，会在真正拉起服务器前终止。

## 解决方法
- 保持原有 `-BuildBeforeSyncLatestModJar -SyncLatestModJar` 流程不变。
- 重新执行时显式补充：`-ServerStartCommand .\start.bat`
- 本次最终可用命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -SuitePath .\tools\bench\suites\send-filter-hidden-intercept-perf.json `
  -ServerStartCommand .\start.bat `
  -RconPassword redstonelink-bench `
  -BuildBeforeSyncLatestModJar `
  -SyncLatestModJar
```

## 复用建议
- 后续凡是跑 dedicated server suite，除非已有更上层包装脚本统一注入，否则都应显式传 `-ServerStartCommand .\start.bat`。
- 若后续 bench 自动化继续扩展，可考虑把启动命令也纳入路径配置，避免重复踩同一阻塞点。
