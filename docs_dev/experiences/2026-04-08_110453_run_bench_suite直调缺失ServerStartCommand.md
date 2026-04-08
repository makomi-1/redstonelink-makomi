# run-bench-suite 直调缺失 ServerStartCommand

## 阻塞点

- 直接调用 `tools/bench/run-bench-suite.ps1` 时，如果没有显式传 `-ServerStartCommand`，入口参数不会给默认值，底层 dedicated server 启动器会直接抛出 `ServerStartCommand is required.`，见 [run-bench-suite.ps1](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/run-bench-suite.ps1#L15)、[BenchSuite.Server.ps1](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/lib/BenchSuite.Server.ps1#L375)。
- 本轮第一次 dedicated OCC suite 实跑已经先完成了 `remapJar + SyncLatestModJar`，但因为漏传启动命令，suite 在首个 entry 开始前就失败了；这类失败属于编排参数缺失，不是 functional case 语义错误，见 [summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105818/summary.json#L14)、[summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105818/summary.json#L17)、[summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105818/summary.json#L74)、[summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105818/summary.json#L88)。

## 解决方法

- 保留第一次 run 已完成的“先构建、再同步 server jar”结果，随后使用同一工作区产物重跑 suite，并显式补上 `-ServerStartCommand '.\\start.bat'`；第二次 run 的 4 个 entry 全部成功，通过了本轮真实 dedicated 回归，见 [summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105901/summary.json#L14)、[summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105901/summary.json#L109)、[summary.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/run/profiles/bench-suite-results/20260408_105901/summary.json#L219)。
- 后续凡是“直接调 `run-bench-suite.ps1`”的命令，都应把 `-ServerStartCommand '.\\start.bat'` 当成必填项；如果只是跑固定的 dedicated 套件，优先复用项目里已经带默认启动命令的包装脚本。现有自动更新使用说明也已经把这一点写明，见 [使用说明.md](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/docs_dev/使用说明.md#L684)。
