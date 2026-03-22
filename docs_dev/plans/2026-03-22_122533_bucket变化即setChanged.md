# bucket变化即setChanged

生成时间：2026-03-22 12:25:33
文件名：2026-03-22_122533_bucket变化即setChanged.md

## 任务背景
当前目标端的结构化真值 bucket 会在收到 delta 时立即更新，但 `setChanged()` 主要仍由派生态变化驱动。这样会出现一种情况：bucket 已变化、持久化内容理论上已更新，但若输出态未变，则区块实体未必及时标脏，结构化真值的落盘时机不够明确。用户确认按最小粒度修复为“bucket 变化即 `setChanged()`”。

## 方案详情

### 现状分析
- `ActivatableTargetBlockEntity` 在处理 sync / pulse / toggle delta 与 invalidation 时，会直接修改并发 bucket 与相关结构化真值。
- 这些 bucket mutation helper 目前大多不返回“是否实际变化”，上层只能按派生状态变化决定是否 `setChanged()`。
- save/load 已经把 bucket 作为持久化主体写入 NBT，因此问题不在序列化格式，而在“结构化真值变化后是否可靠标脏”。

### 技术方案
- 将 bucket 相关 mutation helper 调整为返回 `boolean changed`，显式表达“这次调用是否真的改动了结构化真值”。
- 在 `applySyncDelta`、`applyActivationDelta`、来源失效回滚等入口累计 `bucketChanged`。
- 只要 `bucketChanged == true`，无论输出态是否变化，都额外执行一次 `setChanged()`。
- 不调整现有时序仲裁、队列去重、TTL 或持久化语义，仅补齐标脏触发条件。

### 影响范围
- `src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- `src/test/java/com/makomi/block/entity/ActivatableTargetBlockEntityInternalTest.java`

### 语义对齐说明
- 命名是否仅使用 `triggerSource/core`：是，本轮不新增术语，仅修改内部 bucket 标脏逻辑。
- 输入/输出结构是否保持方向一致：是，不改来源/目标方向。
- 文案与注释是否无旧术语泄漏：是，本轮不新增对外文案。
- 与 `LinkNodeType` 映射是否一一对应：是，本轮不改映射。
- 兼容层是否仅“读旧写新”：无，本轮不涉及兼容层。
- 残余风险：若 helper 返回值覆盖不全，可能仍有少数 bucket 变更未触发标脏，需要靠测试兜住。

## 原子步骤清单

### 步骤 1：梳理 bucket 变更入口
- **操作对象**：`ActivatableTargetBlockEntity`
- **具体动作**：复核 sync / pulse / toggle 写入、清理、prune、失效回滚相关 helper 与调用链
- **预期结果**：明确所有会影响结构化真值持久化的变更点
- **关键里程碑**：否

### 步骤 2：补齐 bucket 实际变化返回值
- **操作对象**：`src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- **具体动作**：让 bucket mutation helper 返回是否变化，并在入口方法中累计 `bucketChanged`
- **预期结果**：上层能准确判断是否需要因结构化真值变化而标脏
- **关键里程碑**：是

### 步骤 3：接入额外标脏逻辑
- **操作对象**：`src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- **具体动作**：在处理 delta / invalidation 后，对 `bucketChanged` 为真的场景执行额外 `setChanged()`
- **预期结果**：即使输出态不变，只要 bucket 变化也会可靠标脏
- **关键里程碑**：是

### 步骤 4：补测试并回归验证
- **操作对象**：`ActivatableTargetBlockEntityInternalTest`、Gradle 测试任务
- **具体动作**：补充“输出不变但 bucket 更新”的测试，并执行定向与全量回归
- **预期结果**：确认结构化真值变化可被保存且无行为回归
- **关键里程碑**：是

## 预期结果
目标端在 bucket 或相关结构化真值发生实际变化时，将不再依赖输出态变化才标脏。这样保存链路能更稳定地反映最新 bucket 状态，同时不改变既有时序仲裁和跨区块派发语义。
