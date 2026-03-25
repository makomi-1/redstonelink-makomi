# 第三批 ActivatableTargetBlockEntity 职责拆分

生成时间：2026-03-25 09:19:32
文件名：2026-03-25_091932_第三批ActivatableTargetBlockEntity职责拆分.md

## 任务背景

当前 `ActivatableTargetBlockEntity` 已经同时承担目标端仲裁、并发来源桶管理、持久化快照、同步观测与读档恢复等多类职责，文件体积与理解成本都过高。  
在前两批命令层与配置层拆分完成后，本轮继续推进抽象基类整理，但必须保持子类契约与外部调用面不变，避免把组织优化演变成语义重设计。

## 方案详情

### 现状分析

- `ActivatableTargetBlockEntity` 对外暴露的公共入口已经稳定：
  - 查询类：`isActive`、`getResolvedOutputPower`、`getResolvedStrength`、`getSyncMaxSourceSerialsSnapshot`、`getConfiguredMode`、`getEffectiveMode`
  - 触发类：`triggerByPlayer`、`triggerBySource`、`syncBySource`
  - delta 类：`applyDispatchDelta`、`removeActivationSource`、`removeSyncSource`
  - 加载恢复类：`hasPendingLoadBlockStateSync`、`consumePendingLoadBlockStateSync`、`onPulseTick`
- 子类 `LinkCoreBlockEntity` 与 `LinkRedstoneDustCoreBlockEntity` 只覆写节点类型、方块状态同步与脉冲调度，不应被迫感知内部重构。
- 当前内部主要缠绕点：
  - 仲裁与候选解析
  - 并发来源桶与结构真值重算
  - NBT 持久化快照读写
  - fanout 去重、输出功率缓存、加载后静默校正
- 现有内部测试大量依赖反射读取私有字段，若不一起调整，后续任何拆分都会被字段名绑死。

### 技术方案

在不新增继承层、不修改子类契约的前提下，按包内 helper/component 做职责切片：

1. 保留 `ActivatableTargetBlockEntity` 作为 façade
   - 继续承载现有公共入口、外部可见类型与子类覆写点
   - 内部改为协调仲裁、桶状态、持久化与观测组件

2. 抽仲裁组件
   - 收拢 `authority/arbitration` 状态、同帧 merge cache、候选选择与优先级判断
   - 保持现有 `TimeKey`、`EventMeta`、`EffectiveMode` 语义不变

3. 抽并发来源桶组件
   - 收拢 `sync/pulse/toggle/runtimeSimulated` 四类桶
   - 收拢 `toggle` 帧级快照、来源 upsert/remove/prune 与三类结构真值重算
   - 统一产出供仲裁与持久化使用的快照

4. 抽持久化快照 helper
   - 收拢 NBT key、并发桶序列化/反序列化、legacy fallback
   - 基类仅保留 `loadAdditional/saveAdditional` 入口与状态回填

5. 抽同步观测组件
   - 收拢 `resolvedOutputPower`、fanout 去重缓存、读档后待校正标记
   - 保持现有客户端同步与邻居扇出语义不变

6. 同步调整内部测试
   - 将旧的字段反射访问迁移到新的组件/快照结构
   - 保持现有语义断言覆盖范围不降级

### 影响范围

- `src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- `src/main/java/com/makomi/block/entity/` 下新增 helper/component
- `src/test/java/com/makomi/block/entity/ActivatableTargetBlockEntityInternalTest.java`
- 仅在必要时调整与之直接耦合的极少量读取点；不改子类公共契约

### 技术取舍

- 选择 component/helper，而不是新增抽象父类或继承层：
  - 优势：符合“不要新增继承层”的约束，调用方与子类无感
  - 代价：基类仍保留 façade 协调代码
- 保留现有外部类型定义在基类中：
  - 优势：避免大范围 import 与调用点迁移
  - 代价：类型定义不会在本轮进一步下沉
- 本轮只做职责切片，不做语义改写：
  - 优势：风险低，便于用现有内部测试护栏回归
  - 代价：少量中转方法和历史命名会暂时保留

### 语义对齐说明

- 命名是否仅使用 `triggerSource/core`：
  - 是。本轮新增命名、注释和说明统一使用 `triggerSource/core`
- 输入/输出结构是否保持方向一致：
  - 是。仍保持 `triggerSource -> core`
- 文案与注释是否无旧术语泄漏：
  - 是。本轮新增内容不引入旧术语
- 与 `LinkNodeType` 映射是否一一对应：
  - 是，保持 `triggerSource=TRIGGER_SOURCE`、`core=CORE`
- 兼容层是否仅“读旧写新”：
  - 本轮不新增业务兼容层；NBT 键名维持现状，不做破坏性改名
- 残余风险：
  - 主要是内部测试需要从旧字段反射切换到新结构；无新增业务语义风险

## 原子步骤清单

### 步骤 1：冻结 façade 边界
- **操作对象**：`ActivatableTargetBlockEntity`
- **具体动作**：先明确公共入口、子类覆写点、外部类型定义与调用方稳定面
- **预期结果**：后续拆分不改外部契约
- **关键里程碑**：是

### 步骤 2：抽仲裁组件
- **操作对象**：`ActivatableTargetBlockEntity` 与新增仲裁组件
- **具体动作**：迁移 authority/arbitration/candidate/merge cache 逻辑
- **预期结果**：仲裁职责从基类主体中分离
- **关键里程碑**：否

### 步骤 3：抽并发来源桶组件
- **操作对象**：`ActivatableTargetBlockEntity` 与新增桶组件
- **具体动作**：迁移四类桶、toggle 帧快照、来源增删与结构真值重算
- **预期结果**：桶管理与真值重算集中到单独组件
- **关键里程碑**：否

### 步骤 4：抽持久化快照 helper
- **操作对象**：`ActivatableTargetBlockEntity` 与新增持久化 helper
- **具体动作**：迁移 NBT key、load/save 细节、快照 DTO 与 legacy fallback
- **预期结果**：基类只保留存档入口和状态回填
- **关键里程碑**：否

### 步骤 5：抽同步观测组件
- **操作对象**：`ActivatableTargetBlockEntity` 与新增观测组件
- **具体动作**：迁移输出功率缓存、fanout 去重、加载后静默校正标记
- **预期结果**：观测与同步辅助逻辑独立收敛
- **关键里程碑**：否

### 步骤 6：调整内部测试
- **操作对象**：`ActivatableTargetBlockEntityInternalTest`
- **具体动作**：将字段反射调整为新的组件/快照读取方式
- **预期结果**：测试继续覆盖仲裁、桶、持久化、加载恢复
- **关键里程碑**：是

### 步骤 7：执行回归验证
- **操作对象**：Gradle 测试任务
- **具体动作**：运行本地回归，确认本轮仅为组织优化无行为回归
- **预期结果**：形成可追溯验证证据
- **关键里程碑**：是

## 预期结果

- `ActivatableTargetBlockEntity` 从“巨型实现类”收敛为“稳定 façade + 内部组件协调类”
- 子类契约、外部调用点与 `triggerSource/core` 语义保持不变
- 后续若继续优化热点，可直接围绕桶组件、仲裁组件和持久化 helper 单独演进
