# revision接入与pairing乐观并发校验

生成时间：2026-04-03 20:58:45
文件名：2026-04-03_205845_revision接入与pairing乐观并发校验.md

## 任务背景

当前 `LinkSavedData` 与 Pairing GUI 仍是“读当前列表 -> 之后直接覆盖提交”的模型。`triggerSource` 视角和 `core` 视角都缺少显式版本基线，服务端无法识别界面打开后发生的并发变更，存在静默覆盖风险。用户已明确要求本轮接入 `graphRevision/sourceRevision` 与 `expectedSourceRevision`，并先用自动化测试把结构性风险锁住。

## 方案详情

### 现状分析

- `LinkSavedData` 当前没有图版本与来源版本真值，链接索引变更只做 `setDirty()`。
- `NodeSnapshotQueryService` 返回的 `NodeLinksSnapshot` 只有可见目标列表，没有 revision 信息。
- `PairingNetwork` 打开包只下发目标列表，提交包不携带 expected revision。
- `core` 视角批量覆盖除了单来源 revision 外，还需要最小化的全图基线，否则会漏掉“界面打开后新增/移除某个来源成员”的冲突。

### 技术方案

1. 在 `LinkSavedData` 增加运行时 `graphRevision` 与 `triggerSource -> sourceRevision` 真值，不改存档格式。
2. 在链接索引真实发生变更时统一递增 revision，并暴露读取接口给共享写闭环与读模型使用。
3. 扩展 `NodeLinksSnapshot` / `NodeSnapshotQueryService`，让当前连接快照可携带 `graphRevision/sourceRevision`。
4. 扩展 `PairingNetwork`：
   - 打开配对界面时下发当前目标列表与 revision 基线。
   - `triggerSource` 提交携带 `expectedSourceRevision`。
   - `core` 视角批量覆盖额外携带 `expectedGraphRevision`，用于拦截“界面打开后 core 成员集合已变化”的隐藏冲突。
5. 服务端 handler 在真正准备写入前先校验 expected revision，不匹配就拒绝并回传冲突提示。
6. 补 revision 递增、快照透出、payload 编解码、mismatch、core 视角批量覆盖相关测试，再执行全量自动化测试。

### 影响范围

- `src/main/java/com/makomi/data/LinkSavedData.java`
- `src/main/java/com/makomi/data/LinkSavedDataLinkIndexSupport.java`
- `src/main/java/com/makomi/data/NodeLinksSnapshot.java`
- `src/main/java/com/makomi/data/NodeSnapshotQueryService.java`
- `src/main/java/com/makomi/network/PairingNetwork.java`
- `src/main/java/com/makomi/network/PairingNetworkPayloadSupport.java`
- `src/main/java/com/makomi/network/PairingNetworkServerHandlerSupport.java`
- `src/client/java/com/makomi/client/network/PairingNetworkClientHandlerSupport.java`
- `src/client/java/com/makomi/client/screen/AbstractMultiPairingScreen.java`
- `src/client/java/com/makomi/client/screen/TriggerSourcePairingScreen.java`
- `src/client/java/com/makomi/client/screen/CorePairingScreen.java`
- `src/main/resources/assets/redstonelink/lang/*.json`
- 对应单测/集成测试

## 原子步骤清单

### 步骤 1：补齐运行时 revision 真值
- **操作对象**：`LinkSavedData`、`LinkSavedDataLinkIndexSupport`
- **具体动作**：新增 `graphRevision/sourceRevision` 运行时状态与读取接口，在真实链接拓扑变更时统一递增
- **预期结果**：图级和来源级 revision 有稳定真值
- **关键里程碑**：是

### 步骤 2：把 revision 透出到节点当前连接快照
- **操作对象**：`NodeLinksSnapshot`、`NodeSnapshotQueryService`
- **具体动作**：让读模型返回 `graphRevision/sourceRevision`，供 GUI 打开时建立版本基线
- **预期结果**：读取当前连接时可同时拿到 revision
- **关键里程碑**：否

### 步骤 3：扩展 PairingNetwork 协议与服务端冲突校验
- **操作对象**：`PairingNetwork`、payload 编解码、客户端 pairing screen、服务端 handler
- **具体动作**：打开包带 revision，提交包带 expected revision，服务端 mismatch 时拒绝写入并回传冲突反馈
- **预期结果**：GUI 配对提交具备显式乐观并发冲突检测
- **关键里程碑**：是

### 步骤 4：补测试并执行自动化回归
- **操作对象**：revision/pairing 相关测试
- **具体动作**：补单测与必要集成测试，然后执行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`
- **预期结果**：结构性风险有自动化门禁
- **关键里程碑**：是

## 预期结果

- `LinkSavedData` 拥有运行时 `graphRevision/sourceRevision`
- Pairing GUI 打开和提交链路接入 revision 基线与冲突拒绝
- `core` 视角批量覆盖不会再静默吞掉界面打开后新增的成员关系
- 自动化测试覆盖 revision mismatch 与批量覆盖回归场景
