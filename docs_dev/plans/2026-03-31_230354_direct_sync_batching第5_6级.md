# direct sync batching第5_6级

生成时间：2026-03-31 23:03:54
文件名：2026-03-31_230354_direct_sync_batching第5_6级.md

## 任务背景

当前 1~4 级已经完成：

1. `core` 端支持批提交；
2. 生命周期 replay、crosschunk ready release 等异步链路已接入目标级 batch scheduler；
3. ready drain 与定位缓存已落到 `CrossChunkDispatchRuntimeSupport`；
4. 默认运行语义仍保持“只有异步 loaded sync 进入 batch，direct 在线 sync 立即生效”。

但第 5 级目标尚未完成：`triggerSource -> core` 的 direct 在线 `SYNC` 仍然绕过统一调度层，导致：

1. loaded `SYNC` 仍分成 direct/async 两套提交口径；
2. direct 路径无法复用统一的目标级批桶；
3. 游戏内功能用例尚未覆盖 `all_sync` 模式下的 loaded direct `SYNC` 语义。

本轮按已确认范围实现第 5/6 级：

1. 给 loaded `SYNC` 增加 `directSyncBatching=off|queued_only|all_sync` 开关；
2. 默认保持 `queued_only`；
3. 让 `LinkedTargetDispatchService` 与 `InternalDispatchDeltaProjector` 的 loaded `SYNC` 路径都按同一配置口径决定 immediate / batch；
4. 补齐配置、路由与一组功能 bench 用例。

## 方案详情

### 现状分析

1. `InternalDispatchDeltaProjector` 当前仅在 `event.deliveryMode()==ASYNC_BATCH` 时对 loaded 目标走 batch，无法控制 direct loaded `SYNC` 的 batching 策略。
2. `LinkedTargetDispatchService` 的 direct 在线 `SYNC` fanout 仍是逐目标 `applyDispatchDelta(...)`。
3. `CoreDispatchBatchScheduler` 已具备 loaded target 的单条/批量 enqueue 能力，可作为统一目标级批桶。
4. 现有 functional bench 里已有 crosschunk 与多来源场景，但没有针对 `directSyncBatching=all_sync` 的 loaded direct `SYNC` 用例。

### 技术方案

1. 在跨区块配置中新增 `directSyncBatching` 枚举配置：
   - `off`
   - `queued_only`
   - `all_sync`
2. 对 loaded `SYNC_SIGNAL` 的 batching 策略统一解释为：
   - `off`：loaded `SYNC_SIGNAL` 一律 immediate；
   - `queued_only`：仅 async/queued loaded `SYNC_SIGNAL` 进入 batch；
   - `all_sync`：loaded `SYNC_SIGNAL` 不区分 direct/async，统一进入 batch。
3. 非 `SYNC_SIGNAL` 的 invalidation 行为保持现状：
   - `ASYNC_BATCH` 继续走 batch；
   - 本轮不把 `ACTIVATION/toggle/pulse` 接入状态批合并。
4. `LinkedTargetDispatchService` 在 `all_sync` 下把 loaded direct `SYNC` 改为 enqueue 到 `CoreDispatchBatchScheduler`，不再 immediate apply。
5. 第 6 级新增一组功能用例，验证多来源 loaded direct `SYNC` 在 `all_sync` 模式下仍能稳定输出正确目标 trace。

### 影响范围

1. `src/main/java/com/makomi/config/RedstoneLinkConfig.java`
2. `src/main/java/com/makomi/config/RedstoneLinkCrossChunkConfig.java`
3. `src/main/java/com/makomi/config/RedstoneLinkCrossChunkConfigParser.java`
4. `src/main/java/com/makomi/config/RedstoneLinkConfigTemplate.java`
5. `src/main/java/com/makomi/data/InternalDispatchDeltaProjector.java`
6. `src/main/java/com/makomi/data/LinkedTargetDispatchService.java`
7. `src/test/java/com/makomi/config/RedstoneLinkConfigInteractionAndCrossChunkParseTest.java`
8. `src/test/java/com/makomi/data/InternalDispatchDeltaProjectorTest.java`
9. `src/test/java/com/makomi/data/LinkedTargetDispatchServiceTest.java`
10. `tools/bench/cases/functional/**`
11. `tools/bench/suites/**`
12. `run/config/redstonelink-server.properties`
13. `docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：补配置模型与模板
- **操作对象**：crosschunk 配置模型、解析器、默认模板、运行配置样例
- **具体动作**：新增 `directSyncBatching` 枚举、字段、解析、默认值与说明
- **预期结果**：第 5 级拥有可控配置入口，默认保持 `queued_only`
- **关键里程碑**：是

### 步骤 2：统一 loaded sync 路由判定
- **操作对象**：`InternalDispatchDeltaProjector`
- **具体动作**：抽出 loaded delta batching 判定 helper，并按 `directSyncBatching` 接入 `SYNC_SIGNAL`
- **预期结果**：projector 的 loaded `SYNC` 走 immediate/batch 的口径统一可测
- **关键里程碑**：是

### 步骤 3：接入 direct 在线 sync 到统一 scheduler
- **操作对象**：`LinkedTargetDispatchService`
- **具体动作**：在 `all_sync` 模式下把 loaded direct `SYNC` 改为 enqueue 到 `CoreDispatchBatchScheduler`
- **预期结果**：direct 在线 `SYNC` 与异步 loaded `SYNC` 可复用同一目标级批桶
- **关键里程碑**：是

### 步骤 4：补配置/路由/服务测试
- **操作对象**：配置解析、projector、service 单元测试
- **具体动作**：补模式解析与路由判定测试
- **预期结果**：第 5 级核心语义可回归验证
- **关键里程碑**：否

### 步骤 5：补功能 bench 用例
- **操作对象**：functional case 与 suites
- **具体动作**：新增一组 `all_sync` 多来源 loaded direct `SYNC` 功能用例，并接入 suite
- **预期结果**：第 6 级具备游戏内可跑证据
- **关键里程碑**：是

### 步骤 6：回归验证
- **操作对象**：全标签自动化测试与编码校验
- **具体动作**：执行回归、检查 BOM/替换字符与 diff 范围
- **预期结果**：确认 5/6 级改动可运行且无明显回归
- **关键里程碑**：是

## 预期结果

1. `queued_only` 继续保持当前默认行为，不改变既有线上默认语义。
2. `all_sync` 模式下，loaded direct `SYNC` 与 async loaded `SYNC` 共享同一目标级批提交层。
3. 游戏内功能用例能覆盖 direct loaded `SYNC` batching 的关键链路，降低后续继续推进第 7 级缓存复用时的回归风险。

## 语义对齐说明

1. 命名统一保持 `triggerSource/core`。
2. 方向仍仅允许 `triggerSource -> core`。
3. 不引入旧术语输出，不改变 `LinkNodeType.TRIGGER_SOURCE/CORE` 映射。
4. 不引入 `com.makomi.api.v1.*` 依赖。
5. 残余风险：
   - `all_sync` 会把 direct 在线 `SYNC` 从“立即生效”改为“tick 末统一生效”；
   - 本轮不处理 `ACTIVATION/toggle/pulse` 的 direct batching；
   - 第 7 级的内存收紧与跨调用缓存共享仍留待后续继续收敛。
