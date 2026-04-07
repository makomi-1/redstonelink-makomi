# bench dry-run 共享 SavePath 冲突

## 阻塞点
- 并行执行多个 `powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunFunctionalCase -DryRun ...` 时，如果都使用默认 `run/saves/rl-bench`，不同进程会在世界清理阶段同时操作同一目录。
- 实际表现为 `Bench.World.ps1` 内部 `Remove-Item` 命中“路径不存在”异常，导致个别 dry-run case 提前失败；这不是 case 语义错误，而是共享工作目录竞争。

## 解决方法
- 本轮改为串行补跑失败 case，确认 4 个 OCC dry-run case 最终都能通过。
- 后续若需要并发 dry-run，显式给每个进程传不同的 `-SavePath`，不要共享默认 `run/saves/rl-bench`。
- 若只是做模板/配置自检，优先串行 dry-run，避免把脚本工作目录竞争误判成 functional case 失败。
