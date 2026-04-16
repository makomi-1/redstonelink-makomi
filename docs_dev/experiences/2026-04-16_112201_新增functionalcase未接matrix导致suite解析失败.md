# 新增 functional case 未接 matrix 导致 suite 解析失败

## 阻塞点
- 新增 dedicated functional case 与 suite 后，首次执行 `run-bench-suite.ps1` 直接在配置阶段失败，报错 `Case not found in matrix`。

## 原因
- bench suite 不会直接扫描 `tools/bench/cases/functional/`。
- `run-bench-suite.ps1` 会先通过 `tools/bench/functional-matrix.json` 解析可用 case，再把 suite 里的 `caseId` 映射到具体文件。
- 只新增 case 文件和 suite 文件，不把路径写进 `functional-matrix.json`，suite 在真正启动 server 前就会失败。

## 解决方法
- 把新增 case 路径注册到 `tools/bench/functional-matrix.json` 的 `casePaths`。
- 注册后再重跑 suite，bench 才会进入构建、同步和 dedicated server 执行阶段。

## 复用提醒
- 以后凡是新增 functional case，都要同时检查三处是否闭环：
  1. `cases/functional/**` 下的 case 文件是否存在
  2. `tools/bench/functional-matrix.json` 是否已注册
  3. `tools/bench/suites/**` 是否正确引用对应 `caseId`
