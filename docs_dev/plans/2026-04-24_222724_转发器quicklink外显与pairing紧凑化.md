# 转发器quicklink外显与pairing紧凑化

生成时间：2026-04-24 22:27:24
文件名：2026-04-24_222724_转发器quicklink外显与pairing紧凑化.md

## 任务背景
用户确认继续完善转发器与快速连接工具（QTL）的联动体验，当前存在四个缺口：一是缓存 `core` 时仍无法把 quick-link 正确应用到转发器；二是手持 QTL 对准转发器时仍显示普通节点高亮，而不是转发器亮绿色主题；三是工具缓存对象还没有对应的穿墙线框外显，且相连对象需要只绘制最外层轮廓；四是由转发器入口打开的 `core/triggerSource` pairing GUI 只切了主题，没有做紧凑布局。

## 方案详情

### 现状分析
- `QuickLinkApplyService` 已有 `applyToRepeaterFromCache(...)`，但客户端 `resolveQuickLinkTarget(...)` 和服务端 `resolveRequestedApplyTarget(...)` 仍按普通节点/过滤器/区块激活器三类处理，导致转发器 apply 场景没有走到专用分支。
- `QuickLinkOutlineRenderer` 目前只在默认方块描边事件里改“当前命中目标”的单框颜色，且只识别普通节点和过滤器；没有转发器绿色分支，也没有缓存对象世界渲染。
- `AbstractMultiPairingScreen` 的布局固定为“标题 + 当前连接 + 模式按钮行 + 输入框 + 双按钮”，转发器上下文虽然禁用了频道模式，但仍保留了整行空白。
- 转发器实体本身已具备双身份语义：`matchesNodeIdentity(...)` 同时接受 `core/triggerSource`，因此问题集中在网络入口与客户端渲染分流，不需要重做实体语义。

### 技术方案
- quick-link apply 场景新增转发器目标 token 与专用解析分支，使客户端/服务端都能显式把转发器识别为独立目标，而不是落回普通节点路径；OCC 基线按“实际被修改的一侧配置”映射到 `core/triggerSource` 既有 revision 语义。
- 客户端渲染侧扩展现有 quick-link 渲染：
  - 当前准星命中转发器时使用亮绿色描边
  - 新增缓存对象穿墙线框渲染器，读取手持 QTL 的 `serial` 缓存，在客户端已加载区块里解析对应对象，并按缓存类型主题色分组绘制
  - 同色相连对象先做体素并集，再绘制并集后的轮廓线框，避免内部相连面的边重复显示
- 给 `AbstractMultiPairingScreen` 增加布局配置钩子，默认布局不变，仅在 `displayContextToken=link_repeater` 的 `core/triggerSource` pairing GUI 下启用紧凑参数，移除模式按钮空行并压缩整体高度。

### 影响范围
- quick-link 客户端目标解析与世界渲染：`src/client/java/com/makomi/client/network/**`、`src/client/java/com/makomi/client/render/**`
- quick-link 服务端目标解析与 OCC 提交：`src/main/java/com/makomi/network/**`
- pairing GUI 紧凑布局：`src/client/java/com/makomi/client/screen/**`
- 回归测试：`src/test/java/com/makomi/network/**`、`src/test/java/com/makomi/client/screen/**`

## 原子步骤清单

### 步骤 1：修正 quick-link 到转发器的目标路由
- **操作对象**：`QuickLinkNetworkClientHandlerSupport`、`QuickLinkNetworkServerHandlerSupport`
- **具体动作**：新增转发器目标 token；客户端 apply 命中时识别转发器；服务端解析并路由到转发器专用 apply 分支；按输入/输出修改侧映射 OCC 校验
- **预期结果**：`core` 缓存与 `triggerSource` 缓存都能正确应用到转发器对应配置
- **关键里程碑**：是

### 步骤 2：补 QTL 命中高亮与缓存对象穿墙外显
- **操作对象**：`QuickLinkOutlineRenderer`、必要的新渲染辅助类、`RedstoneLinkClient`
- **具体动作**：为转发器补亮绿色命中描边；新增缓存对象穿墙线框渲染；对相连缓存对象做体素并集，只绘制最外层轮廓
- **预期结果**：QTL 当前目标与缓存对象都能按主题亮色正确外显
- **关键里程碑**：是

### 步骤 3：紧凑化转发器上下文 pairing GUI
- **操作对象**：`AbstractMultiPairingScreen`、`CorePairingScreen`、`TriggerSourcePairingScreen`
- **具体动作**：增加布局配置入口，仅转发器上下文启用紧凑布局，去掉空模式按钮预留并压缩整体面板高度
- **预期结果**：由转发器打开的 `core/triggerSource` pairing GUI 更紧凑，普通 pairing GUI 不受影响
- **关键里程碑**：是

### 步骤 4：补测试与全量回归
- **操作对象**：`QuickLinkNetworkServerHandlerSupportTest`、`AbstractMultiPairingScreenLayoutTest` 及必要补测
- **具体动作**：补转发器 apply/OCC 路由和紧凑布局断言，随后运行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`
- **预期结果**：改动具备自动化验证与可追溯结果
- **关键里程碑**：是

## 预期结果
- 缓存 `core` 时可正确应用到转发器输出配置，缓存 `triggerSource` 时可正确应用到输入配置
- 手持 QTL 指向转发器时显示亮绿色目标描边
- 手持 QTL 时可看到缓存对象的对应主题穿墙线框，且相连对象只保留最外层轮廓
- 转发器入口打开的 `core/triggerSource` pairing GUI 更紧凑

## 语义对齐检查结果
- 命名是否仅使用 `triggerSource/core`：是
- 输入/输出结构是否保持方向一致：是，`triggerSource -> input`，`core -> output`
- 文案与注释是否无旧术语泄漏：是
- 与 `LinkNodeType` 映射是否一一对应：是
- 兼容层是否仅“读旧写新”：是
- 残余风险：缓存对象穿墙外显默认只覆盖客户端当前已加载区块中的对象，不主动跨未加载区块补点
