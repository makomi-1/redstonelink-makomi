# 配对 GUI 别名编辑接入

生成时间：2026-04-13 08:50:39
文件名：2026-04-13_085039_pairing_gui_alias_edit.md

## 任务背景
现有节点别名第一版已经支持命令维护与读展示，但 `triggerSource/core` 配对 GUI 里的节点序号字段仍是只读文本。  
本轮需要把这两个 GUI 的节点序号字段扩展为“别名输入框 + 固定序号显示”的编辑形态，并通过 `Save` 按钮提交别名修改；不扩展到状态面板，也不支持 GUI 清空别名。

## 方案详情

### 现状分析
- 当前 `AbstractMultiPairingScreen` 只把节点序号作为头部副标题文本渲染，没有可编辑控件。
- `PairingNetwork` 打开 GUI 时只下发读展示所需的显示文本，没有 GUI 保存别名的上行协议。
- 服务端已经具备别名校验、持久化和展示同步能力，可复用现有 `NodeAliasSavedData` 与 `NodeAliasServerSupport`。

### 技术方案
- 调整 `triggerSource/core` 配对 GUI 布局，在标题下新增单行别名输入框、固定 `(#序号)` 文本和 `Save` 按钮。
- 打开 GUI 的 payload 额外下发原始 alias，客户端只把 alias 放进输入框，不再从展示文本反拆。
- 新增一条 GUI alias 保存网络协议，服务端复用现有别名校验与保存逻辑，并沿用现有展示同步链。
- 保存成功后回传最新 alias 快照，客户端更新输入框状态与展示文本；失败则显示结构化反馈。
- GUI 保存权限与 `node alias` 命令保持一致，继续按 `otherPermissionLevel` 校验。

### 影响范围
- `src/client/java/com/makomi/client/screen/**`
- `src/client/java/com/makomi/client/network/**`
- `src/main/java/com/makomi/network/**`
- `src/main/java/com/makomi/data/**`
- `src/main/resources/assets/redstonelink/lang/**`
- `src/test/java/com/makomi/**`

## 原子步骤清单

### 步骤 1：重构配对 GUI 头部序号区域
- **操作对象**：`AbstractMultiPairingScreen`、`CorePairingScreen`、`TriggerSourcePairingScreen`
- **具体动作**：新增 alias 输入框、固定序号文本与 `Save` 按钮，调整布局高度与头部区域包围盒
- **预期结果**：两个配对 GUI 都具备可编辑别名的头部区域
- **关键里程碑**：是

### 步骤 2：扩展打开 GUI 的 alias 初始数据
- **操作对象**：`PairingNetwork`、`PairingNetworkPayloadSupport`、客户端 handler
- **具体动作**：打开 GUI 时新增原始 alias 字段，并传递到 screen 初始化
- **预期结果**：GUI 打开时可回填当前别名
- **关键里程碑**：否

### 步骤 3：新增 GUI 保存别名协议
- **操作对象**：`PairingNetwork`、`PairingNetworkRegistrationSupport`、`PairingNetworkServerHandlerSupport`
- **具体动作**：新增 alias 保存的 C2S/S2C payload，服务端校验权限、节点有效性与别名规则后执行保存
- **预期结果**：点击 `Save` 可把别名写回服务端并同步读展示
- **关键里程碑**：是

### 步骤 4：补反馈、语言和测试
- **操作对象**：语言文件、payload 测试、screen/命令契约测试
- **具体动作**：补 GUI alias 保存成功/失败文案和最小回归测试
- **预期结果**：交互完整、回归可追踪
- **关键里程碑**：否

## 预期结果
完成后，`triggerSource/core` 配对 GUI 的节点序号字段将改为“别名输入框 + 固定 `(#序号)` 文本 + `Save` 按钮”组合。  
用户可直接在 GUI 修改别名；真实主键仍是序号，当前连接列表与写链路语义保持不变，GUI 不提供清空别名能力。

## 语义对齐说明
- 命名是否仅使用 `triggerSource/core`：是
- 输入/输出结构是否保持方向一致：是
- 文案与注释是否无旧术语泄漏：是
- 与 `LinkNodeType` 映射是否一一对应：是，`triggerSource=TRIGGER_SOURCE`，`core=CORE`
- 兼容层是否仅“读旧写新”：是，真值仍为序号，新增 GUI alias 写入口

## 残余风险
- GUI alias 保存权限若与用户预期不一致，后续可能需要单独再调整为更细粒度门禁。
- 当前仍不支持 GUI 清空别名；若后续需要，需额外设计空值语义和按钮形态。
