# 重构ActivatableTargetBlockEntity派发支持

生成时间：2026-04-09 09:19:20
文件名：2026-04-09_091920_重构ActivatableTargetBlockEntity派发支持.md

## 任务背景
当前 `ActivatableTargetBlockEntity` 已经拆出了并发桶、仲裁、观测和持久化辅助组件，但类体仍然过大，约 `1200+` 行。  
剩余最大职责块集中在 `dispatch delta / batch mutation / runtime simulated sync / pulse tick` 这一组运行态派发落地逻辑。  
本轮目标是在不改变 `triggerSource -> core` 方向语义、不引入 API 层依赖的前提下，继续收口该类职责，降低单类复杂度，并在完成后执行自动化与非压测游戏内回归。

## 方案详情

### 现状分析
- `ActivatableTargetBlockEntity` 同时承担：
  - 对外目标实体 API
  - 运行态派发落地
  - 真值重算与派生态写回
  - NBT 生命周期
- 其中“运行态派发落地”块天然独立，且已经主要依赖现有组件：
  - `ActivatableTargetConcurrentBucketComponent`
  - `ActivatableTargetArbitrationComponent`
  - `ActivatableTargetObservationComponent`
- 当前测试存在对内部私有方法的反射调用，因此重构时需要尽量保持桥接方法名与调用顺序稳定。

### 技术方案
- 新增包内 helper：`ActivatableTargetDispatchSupport`
- 将以下职责迁入 helper：
  - delta 分流与落地
  - batch 排序、mutation 累加与批末提交
  - runtime simulated sync
  - pulse tick 回落处理
- `ActivatableTargetBlockEntity` 保留：
  - 对外 public API
  - 抽象钩子
  - 真值/派生态桥接方法
  - NBT 生命周期
- 为降低测试与行为回归风险，保留现有关键桥接方法名；仅把必要的内部方法/组件访问面放宽到包内可见，供 helper 复用。

### 影响范围
- `src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- `src/main/java/com/makomi/block/entity/ActivatableTargetDispatchSupport.java`
- 相关测试（仅在内部结构调整导致失配时最小修改）

## 原子步骤清单

### 步骤 1：抽离派发支持层
- **操作对象**：`ActivatableTargetBlockEntity`、新增 helper 类
- **具体动作**：把 delta 分流、batch mutation、runtime simulated sync、pulse tick 等流程迁入 `ActivatableTargetDispatchSupport`
- **预期结果**：主类移除最大职责块
- **关键里程碑**：是

### 步骤 2：保留桥接与行为顺序
- **操作对象**：`ActivatableTargetBlockEntity`
- **具体动作**：保留对外 API 与关键桥接方法名，按最小范围调整可见性，避免破坏现有反射测试与调用链
- **预期结果**：外部接口和内部行为顺序保持稳定
- **关键里程碑**：是

### 步骤 3：补最小测试适配
- **操作对象**：相关测试
- **具体动作**：仅在结构变更导致失配时做最小修改
- **预期结果**：重构后仍有自动化回归保护
- **关键里程碑**：否

### 步骤 4：自动化验证
- **操作对象**：Gradle 测试任务
- **具体动作**：执行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`
- **预期结果**：确认代码级回归门禁通过
- **关键里程碑**：是

### 步骤 5：游戏内非压测回归
- **操作对象**：bench 回归脚本与 dedicated server
- **具体动作**：先构建并同步当前工作区 jar，再执行 `tools/bench/run-regression-full-suite-build-sync.ps1`
- **预期结果**：形成基于当前工作区产物的 dedicated 非压测回归结论
- **关键里程碑**：是

## 预期结果
- `ActivatableTargetBlockEntity` 的主类体量明显下降，职责边界更清晰。
- `triggerSource/core` 语义、`triggerSource -> core` 方向约束、现有 pulse/sync/toggle 运行时行为保持不变。
- 自动化测试与非压测游戏内回归均可作为本轮交付证据。
