# 配置层 RedstoneLinkConfig 拆分二期

生成时间：2026-03-24 22:55:12
文件名：2026-03-24_225512_配置层RedstoneLinkConfig拆分二期.md

## 任务背景

配置层一期已完成主解析、跨区块解析、默认模板与运行期辅助计算的拆分，`RedstoneLinkConfig.java` 已降到 1k 行以内。  
当前剩余的集中职责主要包括：

- 两个配置快照 record 及其默认值定义
- 三个“打开配对界面”交互策略方法

这些内容仍然混在门面类中，影响配置包的职责清晰度。

## 方案详情

### 现状分析

`RedstoneLinkConfig.java` 当前仍承担：

- 对外静态配置门面
- 配置快照结构定义与默认值工厂
- 交互策略判断

其中快照结构属于“配置数据模型”，交互策略属于“基于配置的行为判断”，都可以继续下沉到独立 support/model 文件。

### 技术方案

本轮继续采用“对外门面不变、内部职责继续拆分”的低风险路径：

1. 将 `Values` 拆为包内顶层 record，并保留默认值工厂。
2. 将 `CrossChunkValues` 拆为包内顶层 record，并保留默认值工厂。
3. 更新 `RedstoneLinkConfig`、解析器与运行期 support 对上述快照类型的引用。
4. 将配对 UI 打开条件判断拆到独立交互策略 support 类。
5. `RedstoneLinkConfig` 继续保留原有静态公开方法，仅委托到新结构，避免影响调用方。

### 技术取舍

- 不拆公开 enum：
  这些类型已作为 `RedstoneLinkConfig.*` 在代码中被广泛引用，本轮保持其嵌套位置，避免扩大影响面。
- 先拆快照与交互策略：
  它们是剩余最清晰、最独立的职责，改动收益高且兼容风险低。
- 不改 accessor 命名和配置键：
  保持对外行为稳定，确保这轮仍属于纯结构重构。

### 影响范围

- `src/main/java/com/makomi/config/RedstoneLinkConfig.java`
- 新增配置快照 model 文件
- 新增交互策略 support 文件
- `src/main/java/com/makomi/config/RedstoneLinkConfigParser.java`
- `src/main/java/com/makomi/config/RedstoneLinkCrossChunkConfigParser.java`
- `src/main/java/com/makomi/config/RedstoneLinkConfigRuntimeSupport.java`

## 原子步骤清单

### 步骤 1：拆出基础配置快照
- **操作对象**：新 model 文件
- **具体动作**：迁移 `Values` record 与其默认值工厂，并更新门面/解析器引用。
- **预期结果**：基础配置快照独立成文件。
- **关键里程碑**：是

### 步骤 2：拆出跨区块配置快照
- **操作对象**：新 model 文件
- **具体动作**：迁移 `CrossChunkValues` record 与其默认值工厂，并更新门面/解析器/运行期 support 引用。
- **预期结果**：跨区块配置快照独立成文件。
- **关键里程碑**：是

### 步骤 3：拆出交互策略 support
- **操作对象**：新 support 文件
- **具体动作**：迁移 held-item/linker/placed-block 三类打开条件判断，并由门面类委托调用。
- **预期结果**：配置门面不再直接承载交互策略细节。
- **关键里程碑**：否

### 步骤 4：执行配置层与全标签回归
- **操作对象**：配置测试与全标签测试
- **具体动作**：验证结构重构未改变配置语义与公开行为。
- **预期结果**：确认二期拆分稳定可用。
- **关键里程碑**：是

## 预期结果

- `RedstoneLinkConfig.java` 进一步瘦身并只保留门面职责。
- 配置数据模型与交互策略职责拥有独立落点。
- 公开静态入口、配置键、兼容语义与测试结果保持不变。
