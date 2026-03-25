# crosschunk-batchcandidate死代码清理

生成时间：2026-03-25 16:01:30
文件名：2026-03-25_160130_crosschunk-batchcandidate死代码清理.md

## 任务背景
在完成 crosschunk 派发簇的未使用方法清理后，
`CrossChunkDispatchService.BatchCandidate` 内仍残留一个未被任何主流程或测试使用的私有工厂方法 `rejected()`。
该方法与当前实现风格不一致，继续保留只会增加阅读噪声。

## 方案详情

### 现状分析
- `BatchCandidate.rejected()` 仅返回 `new BatchCandidate(-1, false)`。
- 当前所有调用点都直接构造 `new CrossChunkDispatchService.BatchCandidate(-1, false)`，没有复用该工厂。
- 方法为 `private static`，不属于任何外部契约。

### 技术方案
- 直接删除 `BatchCandidate.rejected()`。
- 不调整 `BatchCandidate` 记录结构，不顺带改写调用点。
- 删除后执行全标签回归，确认只是死代码清理。

### 影响范围
- `src/main/java/com/makomi/data/CrossChunkDispatchService.java`

## 原子步骤清单

### 步骤 1：删除未使用工厂方法
- **操作对象**：`CrossChunkDispatchService.java`
- **具体动作**：删除 `BatchCandidate.rejected()`
- **预期结果**：`BatchCandidate` 仅保留记录定义，不再携带无调用方的私有工厂
- **关键里程碑**：是

### 步骤 2：回归验证
- **操作对象**：Gradle 测试任务
- **具体动作**：执行全标签自动化回归
- **预期结果**：确认清理不影响构建和行为
- **关键里程碑**：是

## 预期结果
- crosschunk 派发簇再减少一处死代码。
- 记录类型定义更干净，阅读成本更低。
