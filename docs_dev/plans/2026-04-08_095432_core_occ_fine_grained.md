# core OCC 细粒度 revision

生成时间：2026-04-08 09:54:32
文件名：2026-04-08_095432_core_occ_fine_grained.md

## 任务背景
当前仓库的 OCC 设计中，`triggerSource` 已具备来源级 `sourceRevision`，但 `core` 侧仍使用全图 `graphRevision` 作为冲突门闩。  
这会把无关拓扑变更也判定为 `core` 冲突，属于偏保守实现。  
本轮用户确认仅收敛一件事：把 `core` 改成更细粒度的 OCC revision；不做“提交成功后自动刷新”。

## 方案详情

### 现状分析
- `LinkSavedData` 当前只维护 `graphRevision` 与 `triggerSourceRevisions`，没有 `core` 级 revision 真值。
- `LinkOccSupport`、pairing、quick-link、bench/internal 都共用“`triggerSource` 比 `sourceRevision`、`core` 比 `graphRevision`”这套 OCC 口径。
- `core` 真实写入仍统一拆解为若干 `triggerSource -> core` 正向写入，因此细粒度 revision 仍必须建立在正向数据层之上，不能重新引入反向真值模型。

### 技术方案
1. 在 `LinkSavedData` 新增运行时 `coreRevision` 真值，并在真实拓扑变更时对受影响的 `core` 递增。
2. 保留 `graphRevision` 作为全图诊断基线，但 `core` OCC 主判定改为比较 `coreRevision`。
3. 扩展共享 OCC 基线与冲突结果结构，统一暴露：
   - `graphRevision`
   - `sourceRevision`
   - `coreRevision`
4. pairing / quick-link / bench/internal 的 `core` 侧 expected revision 全部切到 `expectedCoreRevision`。
5. 冲突文案改为 `core_revision` 语义，避免继续输出“全图 revision 冲突”的旧表述。
6. 不修改 pairing 提交后的 UI 刷新策略，维持当前交互行为。

### 影响范围
- `src/main/java/com/makomi/data/LinkSavedData.java`
- `src/main/java/com/makomi/data/LinkSavedDataLinkIndexSupport.java`
- `src/main/java/com/makomi/data/NodeLinksSnapshot.java`
- `src/main/java/com/makomi/data/NodeSnapshotQueryService.java`
- `src/main/java/com/makomi/data/LinkOccSupport.java`
- `src/main/java/com/makomi/network/PairingNetwork*.java`
- `src/main/java/com/makomi/network/QuickLinkNetwork*.java`
- `src/main/java/com/makomi/data/QuickLinkOccSubmissionSupport.java`
- `src/main/java/com/makomi/command/bench/BenchOccCommandRegistry.java`
- `src/client/java/com/makomi/client/screen/*.java`
- `src/client/java/com/makomi/client/network/*.java`
- `src/main/resources/assets/redstonelink/lang/*.json`
- 相关 `src/test/**` 与 `tools/bench/cases/functional/**`

### 技术取舍
- 不删除 `graphRevision`：保留全图诊断价值，降低连带改动面。
- 不只改单一入口：shared OCC helper、pairing、quick-link、bench/internal 必须同步，避免同一 `core` 在不同入口出现两套冲突语义。
- 不新增底层反向写入：真实写入仍固定为 `triggerSource -> core`，`coreRevision` 只作为高层 OCC 真值。

## 原子步骤清单

### 步骤 1：补齐 core 级 revision 真值
- **操作对象**：`LinkSavedData`、`LinkSavedDataLinkIndexSupport`
- **具体动作**：新增 `coreRevisions` 与读取接口，并在真实拓扑变更时对受影响 `core` 递增
- **预期结果**：`core` 拥有独立细粒度 OCC 基线
- **关键里程碑**：是

### 步骤 2：收口共享 OCC 基线与冲突判定
- **操作对象**：`NodeLinksSnapshot`、`NodeSnapshotQueryService`、`LinkOccSupport`
- **具体动作**：让读模型与 OCC helper 同时暴露 `graphRevision/sourceRevision/coreRevision`，并将 `core` 冲突改为比较 `coreRevision`
- **预期结果**：共享 OCC 逻辑不再依赖 `graphRevision` 作为 `core` 主门闩
- **关键里程碑**：是

### 步骤 3：同步 pairing 与 quick-link 协议
- **操作对象**：`PairingNetwork*`、`QuickLinkNetwork*`、客户端 pairing/quick-link 相关类
- **具体动作**：将 `core` 侧 expected revision 字段改为 `expectedCoreRevision`，同步编解码、客户端发送与服务端校验
- **预期结果**：pairing / quick-link 对 `core` 的 OCC 粒度一致
- **关键里程碑**：是

### 步骤 4：同步 bench、文案与测试
- **操作对象**：`BenchOccCommandRegistry`、`lang`、`src/test/**`、`tools/bench/cases/functional/**`
- **具体动作**：更新 bench/internal 参数与摘要输出、冲突文案、单测与功能用例断言
- **预期结果**：文案、测试和工具链统一到 `coreRevision`
- **关键里程碑**：是

### 步骤 5：验证与审查文档
- **操作对象**：Gradle 全标签测试、`docs_dev/reviews/`
- **具体动作**：执行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`，并生成五段式审查文档
- **预期结果**：本轮改动具备自动化验证与审查留痕
- **关键里程碑**：是

## 预期结果
- `core` OCC 从全图粗粒度切换到节点级细粒度 revision。
- pairing / quick-link / bench/internal 对 `core` 的冲突语义保持一致。
- 真实写入方向继续固定为 `triggerSource -> core`。
- 本轮不引入提交后自动刷新逻辑，不改变现有 pairing 交互节奏。
