# crosschunk第7级resident与force_load收紧

生成时间：2026-04-01 10:03:28
文件名：2026-04-01_100328_crosschunk第7级resident与force_load收紧.md

## 任务背景

当前 `crosschunk` 路径已经完成前序缓存、ready drain 与批调度接入，但常态运行中仍存在较多 tick 内短命对象分配：

1. `resident` 常驻票据同步仍会在 end tick 过程中构建全量临时 `Map`；
2. `force-load` 与 ready drain 在每 tick 过程中仍会频繁创建短生命周期 `List/Map` 容器；
3. `direct sync` 主链路当前不是主要热点，本轮不进入。

用户已确认本轮只做第 7 级“内存/分配收紧”，不改现有功能口径、调度顺序与对外语义。

## 方案详情

### 现状分析

1. `CrossChunkDispatchTicketSupport` 的 resident 同步路径存在“按 tick 全量计算 desired/current 差异”的倾向，临时 `HashMap` 与快照复制会带来 young GC 压力。
2. resident 白名单的变化入口并未统一暴露成“脏状态”语义，导致 end tick 更容易退化为无差别重算。
3. `CrossChunkDispatchRuntimeSupport` 与 `CoreDispatchBatchScheduler` 在 `force-load`、ready drain、batch flush 过程中仍会反复创建 tick 内短命容器。
4. 当前 bench 与运行语义已依赖现有 flush 顺序、本 tick 末释放口径以及 `triggerSource -> core` 单向数据流，本轮不能破坏这些约束。

### 技术方案

1. 先处理 `resident` 路径：
   - 将 desired resident 票据收集改成状态内可复用 scratch 容器；
   - 尽量复用 desired/current 对比容器，避免不必要的 `Map.copyOf` 与整表重建；
   - 引入 resident dirty/epoch 判定，只在白名单或相关状态实际变化时触发重算。
2. 再处理 `force-load + ready drain` 路径：
   - 复用 `acceptedPendings`、`batchEntries`、wake snapshot 等 tick 内短命列表；
   - 评估并实现 `readyGroupsByChunk` / target group 内部容器在状态对象上的复用；
   - 保持既有 batch/flush 顺序和 preload 后释放时机不变。
3. 本轮明确不处理：
   - `direct sync`
   - `concurrent bucket` 真值重算
   - 新增 bench case

### 影响范围

1. `src/main/java/com/makomi/data/CrossChunkDispatchTicketSupport.java`
2. `src/main/java/com/makomi/data/CrossChunkDispatchService.java`
3. `src/main/java/com/makomi/data/CrossChunkDispatchRuntimeSupport.java`
4. `src/main/java/com/makomi/data/CoreDispatchBatchScheduler.java`
5. 视回归需要补充对应测试文件

## 原子步骤清单

### 步骤 1：resident 脏状态与 scratch 容器梳理
- **操作对象**：`CrossChunkDispatchTicketSupport`、`CrossChunkDispatchService`
- **具体动作**：定位 resident 白名单与票据同步的变更入口，补齐 dirty/epoch 标记，并将 desired resident 收集改为可复用 scratch 容器
- **预期结果**：resident end tick 不再默认全量构建临时 `HashMap`
- **关键里程碑**：是

### 步骤 2：resident desired/current 对比原地复用
- **操作对象**：`CrossChunkDispatchTicketSupport`
- **具体动作**：调整 desired/current 差异对比流程，尽量在复用容器上完成比对与应用，减少快照复制与整表新建
- **预期结果**：resident 路径对象分配下降，白名单生效语义保持不变
- **关键里程碑**：是

### 步骤 3：force-load 与 ready drain 临时容器复用
- **操作对象**：`CrossChunkDispatchRuntimeSupport`
- **具体动作**：复用 `acceptedPendings`、wake snapshot、ready group 等 tick 内短命容器，并清理旧的每 tick 新建路径
- **预期结果**：ready drain 与 preload 释放阶段 Eden 抖动下降
- **关键里程碑**：是

### 步骤 4：scheduler 批处理容器复用
- **操作对象**：`CoreDispatchBatchScheduler`
- **具体动作**：复用 `batchEntries` 等内部短命容器，在不改变 flush 顺序的前提下收紧临时对象分配
- **预期结果**：scheduler 侧批量收集与 flush 过程中减少额外分配
- **关键里程碑**：是

### 步骤 5：回归验证
- **操作对象**：相关测试与 Gradle 任务
- **具体动作**：执行针对性检查后运行全标签自动化测试，确认 resident 与 force-load/ready drain 行为语义未变
- **预期结果**：本轮收紧仅影响内部分配，不引入行为回归
- **关键里程碑**：是

## 预期结果

1. `resident` 路径常态运行的 young/old GC 压力下降。
2. `force-load` 与 ready drain 在高频 tick 下的短命对象分配减少。
3. `triggerSource -> core` 单向语义、现有 flush 顺序和 preload 后释放语义保持不变。

## 语义对齐说明

1. 命名继续统一使用 `triggerSource/core`，不引入 `button/source/target` 等旧术语回流。
2. 方向保持 `triggerSource -> core` 单向，`triggerSource` 仅作为来源，`core` 仅作为目标。
3. 与 `LinkNodeType.TRIGGER_SOURCE/CORE` 的映射关系保持不变。
4. 本轮仅收紧 `crosschunk` 内部缓存与调度容器，不引入 `com.makomi.api.v1.*` 依赖。
5. 兼容层保持“读旧写新”边界不变，本轮不新增新的对外字段或持久化键。
6. 当前残余风险：
   - 若 resident dirty 判定点覆盖不完整，可能退化为“仍然每 tick 全量计算”；
   - 容器复用若清理边界不完整，可能带来跨 tick 残留数据，需要在实现与测试中重点校验。
