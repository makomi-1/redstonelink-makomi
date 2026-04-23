# 规则
该文档已根据当前源码、配置模板与 bench 脚本校对，并仅保留当前版本仍有效的使用说明。

# 正文
以下内容已按“普通玩家常用 -> 管理/运维 -> 诊断/自动化”排序，并拆分为分组章节，越靠前越适合先读。

## 快速导航

- 第一次上手、只想先连起来：直接看`一、普通玩家快速上手`。
- 想看序号外显、客户端显示效果：直接看`二、客户端显示与观察`。
- 想查命令、批量维护、服主管理：直接看`三、命令与服主管理`。
- 想配权限、隐私、写控或跨区块策略：优先看`四、权限与访问控制`和`五、跨区块与持久化`。
- 想排查异常、抓热点、跑回归或做性能测试：直接看`六、诊断与运行时工具`和`七、自动化测试与 Bench`。

## 一、普通玩家快速上手

- 适合：第一次使用模组、只想完成日常连线和查看状态的玩家。
- 建议先读：`快速连接工具` -> `发送/接收过滤器` -> `同步遥控器` -> `状态面板工具` -> `图可视化编辑器` -> `命令/GUI 批量序号输入格式`。

### 快速连接工具
- 物品名：`快速连接工具 / Quick Link Tool`。
- 当前版本 `serial/channel` 两种缓存都可直接工作；`channel` 模式会采集命中节点当前频道，并把频道缓存应用到节点或过滤器。其 GUI 背景与物品贴图会切到独立频道主题，便于和 `serial` 直接区分。
- 基础交互：
1. 潜行且左手为空时右键：打开快速连接工具缓存编辑界面。
2. 左键命中可连接节点：采集当前节点到缓存。
3. 站立右键命中可连接节点或对应过滤器：把当前缓存应用到命中对象。
4. 鼠标中键（跟随原版 `pick item` 绑定）：循环切换应用编辑模式 `覆盖 -> 增量增加 -> 增量删除 -> 覆盖`。
5. 快速连接模式键：默认 `B`；站立按切换 `serial/channel`，潜行按清空序号缓存与频道缓存。
- 采集规则：
1. `serial` 模式下，采集后会根据命中对象自动切换当前序号缓存类型：命中 `core` 时缓存类型变为 `core`，命中 `triggerSource` 时缓存类型变为 `triggerSource`。
2. 同为 `serial` 模式且缓存类型一致时，采集采用增量追加，并自动去重。
3. 若序号缓存类型发生变化，则会重建当前序号缓存后再写入本次采集结果。
4. 重复采集同一序号时不会重复写入，action bar 会提示该序号已在缓存中。
5. `channel` 模式下，只能采集当前已处于频道模式且频道值大于 `0` 的节点；采集结果是单个频道值，不做集合追加。
6. 过滤器不可采集；左键命中过滤器不会写入任何缓存。
- 应用规则：
1. 实际写入方向固定为 `triggerSource -> core`。
2. `serial` 模式下，若当前缓存类型与命中节点类型相同，则会提示方向不合法，不执行应用。
3. 当前应用编辑模式分三种：`replace`（覆盖）、`append`（增量增加）、`remove`（增量删除）；它们只对 `serial` 模式生效。
4. 将 `core` 缓存应用到 `triggerSource` 时，只修改当前命中 `triggerSource` 的一跳目标集合：
   - `replace`：当前 `triggerSource` 的目标集合替换为缓存中的 `core`
   - `append`：只补当前 `triggerSource -> 缓存 core`
   - `remove`：只删当前 `triggerSource -> 缓存 core`
5. 将 `triggerSource` 缓存应用到 `core` 时，只修改当前命中 `core` 的一跳来源集合：
   - `replace`：当前 `core` 的来源集合替换为缓存中的 `triggerSource`
   - `append`：只补 `缓存 triggerSource -> 当前 core`
   - `remove`：只删 `缓存 triggerSource -> 当前 core`
6. `channel` 模式下，频道缓存必须是大于 `0` 的 `long`；应用到节点时会把命中节点切到频道模式并写入该频道，同时按频道映射重建普通边；应用到过滤器时会把过滤器目标切到 `channel` 并写入频道值。
7. 命中节点时，快速连接工具的应用仍受服务端写控限制；若命中只读、limited 或受控名单限制，当前统一提示“没有足够权限”。
8. 命中过滤器时，不走链接写控，改为要求服务端 `server.command.permissionLevel`。
9. 当前序号缓存为空时，仅 `replace` 可继续应用，语义为“覆盖为空”：
   - 命中 `triggerSource` 时清空当前一跳 `core` 集合
   - 命中 `core` 时清空当前一跳 `triggerSource` 集合
   - 命中过滤器且当前为 `serial` 模式时清空当前序号目标
10. `append/remove` 仍要求序号缓存非空；空缓存下会直接失败并提示。
11. 将一批 `triggerSource` 缓存应用到 `core` 时，缓存项数同样受服务端 `server.maxTargetsPerSetLinks` 限制。
12. 命中 `send` 过滤器时，`serial` 模式只接受 `triggerSource` 缓存；命中 `receive` 过滤器时，`serial` 模式只接受 `core` 缓存。两类过滤器都可接受 `channel` 缓存。
13. 过滤器的 `serialExpression` 同样支持 `replace/append/remove` 三态编辑；`channel` 模式下直接覆盖频道值，不走增删量语义。
14. 过滤器应用不参与 OCC；基线往返仍会执行，但服务端会回传零 revision 并直接写入过滤器配置。
- 清空规则：
1. `潜行 + B` 清空序号缓存与频道缓存。
2. 清空后会保留当前模式、当前序号缓存类型与当前应用编辑模式，不会强制重置为默认值。
- 界面与反馈：
1. GUI 中可手动编辑序号缓存；切到 `channel` 后也可直接编辑频道缓存，并参与真实 collect/apply。`serial/channel` 会分别使用 `_sd/_cp` 物品贴图和对应主题背景。
2. 最近一次采集、应用、清空、模式切换、应用编辑模式切换或模式限制提示统一显示在 action bar，与 `B` 键模式提示位置一致。
3. GUI 侧序号缓存输入长度上限走客户端配置 `client.quickLinkSerialCacheMaxLength`，默认 `1024`。
4. `channel` 输入要求正 `long`；`0` 或空值都视为“当前没有有效频道缓存”。
5. 真正执行序号应用时，服务端仍会按 `server.command.linkSet.maxInputLength` 对缓存表达式再做一次长度校验。
6. 手持快速连接工具并命中对象时会显示描边：`core` 为亮蓝色，`triggerSource` 为亮橙色，过滤器为亮红色。
- 当前建议使用顺序：
1. 主手持快速连接工具。
2. 按 `B` 选择当前是 `serial` 还是 `channel` 工作流。
3. 左键采集一批 `core` / `triggerSource`，或采集一个频道值到缓存。
4. 若当前使用 `serial`，可再用中键切到需要的应用编辑模式。
5. 站立右键把缓存应用到合法方向的目标节点，或把对应缓存应用到过滤器。
6. 需要重来时按 `潜行 + B` 清空缓存。
- 合成配方：
1. `连接原件 + 木棍 + 木棍 -> 快速连接工具`
2. 图形如下：
   `  C`
   ` S `
   `S  `
   `C = redstonelink:redstone_link_component`
   `S = minecraft:stick`

### 发送/接收过滤器
- 物品名：`发送过滤器 / Send Filter`、`接收过滤器 / Receive Filter`。
- 基础交互：
1. 手持或已放置状态下，满足与其它可配置节点一致的打开条件时，都可打开过滤器配置界面。
2. 放置后以方块形式持续生效。
3. 挖起时掉落物会保留当前配置，重新放置会恢复。
4. 物品 tooltip 会显示当前配置快照，包括别名、目标模式以及当前序号组或频道值。
5. 手持快速连接工具并站立右键命中过滤器时，可把对应类型的序号缓存，或当前频道缓存，直接应用到过滤器。
- 服务对象：
1. `send` 过滤器只服务 `triggerSource`。
2. `receive` 过滤器只服务 `core`。
3. 过滤器不会改变真实写入方向，链路仍固定为 `triggerSource -> core`。
- 配置项：
1. `displayAlias`：过滤器别名；用于 GUI、tooltip 和近外显展示。
2. `targetMode`：`serial / channel`，两种目标模式二选一。
3. `serialExpression`：当 `targetMode=serial` 时使用，支持与配对 GUI 相同的 `N` / `A:B` 语法。
4. `channel`：当 `targetMode=channel` 时使用；有效值为大于 `0` 的 `long`，`0` 代表当前没有频道目标。
5. `nodeSetMode`：`disabled / whitelist / blocklist`，分别表示关闭、白名单、黑名单筛选。
6. `signalThresholdSource`：`fixed_input / neighbor_max_input`，分别表示固定阈值和邻居最大输入。
7. `fixedSignalThreshold`：固定阈值输入，范围 `0~15`。
8. `signalMode`：`disabled / upper_bound / lower_bound`，分别表示关闭、上界过滤、下界过滤。
- 作用范围与运行时：
1. 物理作用范围是以过滤器方块为中心、`X/Y/Z` 各方向半径 `8` 格的正方体。
2. 只有作用范围内且命中目标条件/信号条件的节点会被过滤。
3. 节点在做过滤判断时，会先读取自己当前的连接模式：节点处于 `serial` 模式时按序号匹配过滤器；节点处于 `channel` 模式时按频道匹配过滤器。
4. 过滤器配置变化或邻居输入变化后，会立即重采样并刷新运行态。
5. 对 `sync` 链路而言，过滤器从放行变为拦截时会触发失效；从拦截变为放行时会按当前快照补发。
6. `pulse / toggle` 不做历史补发，只影响后续新派发。

### 同步遥控器
- 物品名：`同步遥控器 / Linked Sync Linker`。
- 基础交互与其它遥控器一致：
1. 潜行且左手为空时右键：打开配对界面。
2. 站立且左手为空时右键：在 `15/0` 两态之间切换，并将当前同步强度派发到已连接 `core`。
- 行为说明：
1. 同步遥控器固定作为 `triggerSource` 使用，真实写入方向仍为 `triggerSource -> core`。
2. 它走的是“同步拉杆”同链路，同步的是当前强度，不是 `TOGGLE/PULSE` 激活语义。
3. 物品贴图会随当前状态切换：`0` 态显示关闭贴图，`15` 态显示开启贴图。
- 合成配方：
1. `同步拉杆 + 拉杆 -> 同步遥控器`
2. 图形与其它遥控器一致：
   `BL`
   `B = redstonelink:link_sync_lever`
   `L = minecraft:lever`

### 同步拉杆
- 交互语义为 `sync`：每次拨动后，已连接目标会对齐到拉杆当前状态（拉杆 `ON` -> 目标 `ON`，拉杆 `OFF` -> 目标 `OFF`）。
- 该对齐语义复用同步派发链路，不依赖 `toggle` 次数累计。
- 同步拉杆转发强度固定为 `ON=15`、`OFF=0`；同步发射器会继承外部输入强度（`0~15`）并转发。
- 当一个同步发射器同时接收多个红石输入时，对外只转发“当前最高输入强度”（`max`）。
- 若发射器保持高电平且最高输入强度不变，则不会重复转发；仅在开关态变化或最高强度变化时再次转发。
- `sync` 属于状态信号：虽然它也是通过派发进入目标端，但表达的是“目标应对齐到的当前状态”；`pulse/toggle` 才是事件信号。

### 状态面板工具
- 物品名：`状态面板工具 / State Panel Tool`。
- 基础交互：
1. 主手持有并右键：打开状态面板。
2. 面板内提供 `订阅 / 刷新 / 录制 / 清空` 四个动作按钮；点击 `录制` 会进入独立录制配置界面，不再是预留入口。
3. 订阅类型可在 `core` 与 `triggerSource` 之间切换。
- 输入与校验规则：
1. 输入框使用与配对 GUI 相同的序号表达式语法：支持 `N` 与 `A:B`，使用 `/` 分隔。
2. 新增订阅仅接受“已分配且未退役”的节点。
3. 命中隐私读控的节点不可新增订阅；服务端会直接返回隐私拒绝提示。
4. 已存在的重复订阅会被自动跳过，不会重复占用订阅位。
5. 最大输入长度仍复用 `server.command.linkSet.maxInputLength`，默认 `1024` 字符。
- 刷新与显示规则：
1. 成功新增订阅后，服务端会自动回传一次最新快照，不需要手动再点一次刷新。
2. 手动 `刷新` 受 `server.statePanel.refreshHz` 节流，默认 `5 Hz`；刷得过快会直接返回节流提示。
3. 若某条历史订阅当前已受隐私读控限制，该行会保留在列表中，但状态列显示为“已隐藏（隐私读控）”。
4. `清空` 会删除当前工具上的全部订阅并立即刷新为空列表。
- 配置项：
1. `server.statePanel.refreshHz`：状态面板整体刷新节流频率，默认 `5`。
2. `server.statePanel.maxSubscriptions`：状态面板总订阅上限（`core + triggerSource` 合计），默认 `50`。

- 录制与网页查看：
1. 录制配置界面可设置录制标题、采样间隔、每节点容量、定时录制时长，并从当前订阅中选择录制子集。
2. 录制配置界面支持“完成后打开网页”和“打开曲线页”两个入口；录制结果导出后会写入本地 recording 资产。
3. 开始录制受 `server.web.recording.permissionLevel` 控制；不满足权限时，录制会话不会启动。
4. 曲线页与 graph 页共用同一套本地网页桥，当前网页主入口只承载 `graph/recording` 两个独立页面。

### 图可视化编辑器
- 物品名：`图可视化编辑器 / Graph Visual Editor`。
- 基础交互：
1. 主手右键，或主手对方块使用：导出当前可见 `serial` 拓扑图快照，并自动打开本地 `graph` 页面。
2. 也可通过客户端命令 `/rlclient web graph` 请求导出当前可见图，或用 `/rlclient web graph open` 直接打开本地 `graph` 页面。
- graph 页面说明：
1. `graph` 页面支持在 `serial/channel` 两种视图间切换；`channel` 视图会把频道关系渲染成 `triggerSource -> channelHub -> core` 的两级结构。
2. 网页中的连接、频道、别名与跨模式调整都只会先写入本地草稿；点击 `Save` 前不会修改游戏真值。
3. graph 导出、网页保存预检与网页保存提交都受 `server.web.graph.permissionLevel` 控制；保存时还会继续经过现有写控、权限与 OCC 校验。
4. `Save` 之前会先走保存预检；本地草稿、布局和导出的图快照都保存在客户端本地网页资产目录中。
5. 网页支持中英文切换与主题切换；当前偏好会持久化到 `gameDir/redstonelink/web/preferences.json`，重启游戏后仍会继续使用。
- 导出与缓存说明：
1. 默认导出的是“当前可见 serial 图快照”，方便直接从游戏内当前上下文进入网页分析。
2. 当结构校验码未变化时，会优先复用已有本地图资产；缺失时才会重新导出并回源写入。

### 配对输入框提示交互
- 输入框初始化为空时不自动抢焦点，便于直接看到占位提示。
- 输入框初始化非空时保持自动聚焦，便于继续编辑已有内容。
- 输入框现为真正的多行输入框，文本会从上往下填充，并在内容过长时支持框内滚动。
- 输入框高度已联动按钮行与状态提示位置；后续再调输入框高度时，下方布局会自动跟随。
- 输入框占位提示改为短格式说明（避免过长遮挡）。
- 鼠标悬停输入框可查看完整规则与示例。
- 输入框最大长度由客户端配置 `client.pairingInputMaxLength` 控制（默认 `1024`）。
- 输入框聚焦时，普通回车用于换行，`Ctrl+Enter` 用于提交。

### 配对界面与悬停提示显示规则
- 适用范围：配对 GUI 当前连接、配对 GUI 悬停 tooltip、可配对物品悬停 tooltip。
- 显示格式：统一为结构化表达式，使用 `N` 与 `A:B`，分隔符为 `/`。
- 示例：`1:100/901:1903/2500`。
- 长文本：超出展示空间时附加 `(+n)`，其中 `n` 为被省略的序号数量。
- 说明：该显示为结构化视图，不保证保留用户当时输入的原始分段写法。

### 节点别名在配对 GUI 中的使用
- 当前仅 `triggerSource/core` 的配对 GUI 支持直接编辑“当前节点别名”。
- 配对界面头部现在显示为“别名输入框 + 固定 `(#序号)` 后缀”；例如别名为 `大门1`、序号为 `12` 时，界面效果为 `大门1(#12)`。
- 修改别名不再需要单独 `Save`；它会随 `Confirm` 一并提交，也就是和当前连接/频道输入同一次确认保存。该入口只保存当前打开节点本身的别名，不会修改任何连接关系。
- GUI 别名保存权限与 `/redstonelink node alias ...` 一致，走 `server.command.otherPermissionLevel`。
- 配对目标输入框仍只接受序号表达式 `N / A:B`，当前不会把别名直接当作配对写入输入。
- 若只知道别名、需要反查序号，仍使用 `/redstonelink node alias resolve <alias>`。

### 命令/GUI 批量序号输入格式
- 适用入口：`/redstonelink link set ... <targets>`、`/redstonelink node activate triggerSource <source_serials> [toggle|pulse]` 与配对 GUI 输入框。
- 统一分隔符：`/`。
- 统一语法：支持单值 `N` 与区间 `A:B`，可混合输入。
- 输入示例：`1:100/1/1000/901:1903/`（末尾 `/` 可省略）。
- GUI 多行输入兼容：可按行拆分填写；客户端发送前会自动把换行折叠为 `/`，服务端解析语义不变。
- 激活命令模式：在 `activate triggerSource` 中，`toggle|pulse` 作为批量序号后的独立关键字解析（例如 `1:100/901:1903 pulse`）。
- 白名单批量覆盖顺序：仅支持标准顺序 `<serials> resident confirm`，不再兼容 `confirm resident` 互换写法。
- 重复条目：允许重复输入，系统会提醒并在写入前自动去重。
- 非法条目：整体拒绝并返回无效段提示；超出数量上限时由服务端按对应配置返回上限提示。

### 语义对齐 role（source/target）与type（triggerSource/core）
- role 仅表示语义方向：`source`（来源）/`target`（目标），命令输入大小写不敏感。
- type 仅表示节点类型：`triggerSource`（映射 `LinkNodeType.TRIGGER_SOURCE`）/`core`（映射 `LinkNodeType.CORE`），命令输入大小写不敏感。
- 建议在文档与示例中统一使用标准写法：role 使用 `source/target`，type 使用 `triggerSource/core`。
- 配置层（`crosschunk.whitelist.sourceTypes/targetTypes` 与 `crosschunk.preset.<name>.sources/targets`）同样仅接受 `triggerSource/core`，不再接受 `button`、`trigger_source` 等别名。

### 配对界面乐观并发保护
- 当前配对 GUI 已接入 revision 基线：
1. 打开配对界面时，服务端当前连接快照会同时携带 `graphRevision/sourceRevision`。
2. `triggerSource` 视角提交时，服务端会校验该来源自己的 `sourceRevision`。
3. `core` 视角提交时，服务端会校验全图级 `graphRevision`，避免多个来源覆盖同一 `core` 时静默互相覆盖。
- 冲突反馈规则：
1. 若 `triggerSource` 自身连接已被其它操作修改，界面会返回“来源版本冲突”提示，并带上期望版本与当前版本。
2. 若 `core` 视角打开后整张连接图已变化，界面会返回“全图版本冲突”提示，并带上期望版本与当前版本。
3. 当前冲突处理策略是“拒绝本次提交并要求重新打开界面后再试”，不会在后台偷偷重放旧输入。
- 快照口径说明：
1. `NodeSnapshotQueryService` 现在统一为当前连接快照附带 revision 基线。
2. 当前真正启用 revision 冲突拦截的入口包括配对 GUI 提交与 quick-link 正式 apply；这些基线同时也为状态面板和后续编辑器复用同一快照口径打底。

### 连接核心片红石行为说明
- 连接核心片（含透明变种）仅激活其所附着方块，不会向其他方向参与线网扩散。
- 连接核心片不参与原版红石粉连线与计算（不作为原版红石粉网络节点）。

### 玩家交互状态覆写（接收方块）
- `连接核心块`、`连接核心块（透明）`、`连接核心片`、`连接核心片（透明）` 现已取消玩家右键直接激活，普通右键满足门禁时只会打开配对 GUI。
- 若需要观察接收方块状态，请以链路驱动结果为准；接收方块不再提供“玩家手动点一下就激活”的快捷入口。

### 遥控器触发链路对齐（2026-03-12）
- 两种遥控器（`redstonelink_toggle_linker` / `redstonelink_pulse_linker`）触发路径已改为复用触发源同链路派发实现。
- 遥控器触发现在与按钮/拉杆一致，目标未加载时同样进入跨区块持久队列/forceLoad 调度逻辑。
- 交互方式更新：
1. 按钮/发射器/拉杆仍保持站立右键主手触发，遥控器仍为潜行右键打开配对。
2. `连接核心块`、`连接核心块（透明）`、`连接核心片`、`连接核心片（透明）` 不再响应玩家右键直接激活；满足门禁时普通右键直接打开配对 GUI。
3. 已放置方块打开配对 GUI 默认要求潜行；若需要关闭该门槛，可把 `interaction.requireSneakToOpenPairing` 设为 `false`。

### 信号竞争处理模型说明
- 本模组把 `sync` 视为状态信号，把 `pulse/toggle` 视为事件信号。
- `sync` 表达目标当前应对齐到的状态，可转发具体红石强度，并享有来源级失效、补发与 replay 服务；推荐作为稳定红石机器、锁存器和长链自动化的主链路。
- `pulse/toggle` 只表达目标端事件结果：`pulse` 是短窗口事件，`toggle` 是对当前目标解析状态取反；它们不参与默认 relay/replay 主链。
- 目标端仍采用“先比较时间、同 tick 固定优先级”的仲裁模型，类间优先级固定为 `sync > pulse > toggle`。
- `sync` 在同 tick 类内按强度聚合（取 `max`）；`pulse/toggle` 共用一个事件域，同 tick 只保留一个事件结果，其中 `pulse` 高于 `toggle`。
- 同 tick 或更晚的 `sync` 会清掉事件持久化；因此 `sync` 失效后只会回退到仍存在的 `sync`，不会回露旧 `pulse/toggle` 事件。
- 与常见把所有触发都视为同类事件的无线红石模组相比，本模组明确区分“状态信号”和“事件信号”，因此默认配置更适合作为可重复、可维护的机器状态传输链路。

## 二、客户端显示与观察

- 适合：想看清序号、外显文本和客户端渲染效果的玩家。
- 建议先读：`连接核心片与连接核心块序号外显（客户端）`，再按需看另外两节。

### 连接核心片与连接核心块序号外显（客户端）
- 连接核心片与连接核心片（透明）会在方块附着面的外侧显示“十进制序号”文本（带千分位分组）。
- 连接核心块与发射器（triggerSource）也会显示同一套序号外显，显示位置在方块顶边上方一点。
- 近距离准星命中节点时，可在屏幕中心显示五到六行信息（深色背景），仅在近距离（8 格内）生效：
1. `[物品名]序号`
2. 激活状态（`ON` / `OFF`）
3. 最终 IO（`I/O`）
4. 当前连接（结构化表达式：`N` / `A:B`，分隔符 `/`，超长附加 `(+n)`）
5. 当前频道（仅节点当前处于频道模式时显示）
6. 跨区块身份
- 近距离准星命中过滤器时，也会显示六行摘要：
1. 过滤器标题（若已设置别名，则标题会带上别名）
2. 状态（`ON` / `OFF`）
3. 服务对象
4. 过滤目标（结构化序号组或频道）
5. 节点集模式 / 信号模式
6. 阈值来源 / 固定阈值 / 邻居输入
- 远外显显示格式为“仅序号”，例如 `12,345`。
- 不同类型使用固定颜色区分（`core` 为青色，`triggerSource` 为橙色，两个过滤器统一为红色；当前连接核心片显示为 `core` 颜色）。
- 客户端按键：默认 `K`，按一次切换一个模式：`远外显 -> 近外显 -> 远+近 -> 关闭`。
- 客户端配置文件：`config/redstonelink-client.properties`
1. `client.serialOverlayMode`：默认外显模式（`far/near/both/off`，默认 `far`）。
2. `client.serialOverlayMaxDistance`：远外显距离（格，范围 `4~256`，默认 `24`）。
3. `client.serialOverlayFontScale`：外显字体缩放（范围 `0.50~3.00`，默认 `1.00`）。
4. `client.serialOverlayToggleKey`：默认切换按键（推荐 `key.keyboard.k`，也兼容单字母如 `K`）。

### 客户端远外显渲染命令（独立根）
- 命令根：`/rlclient`（客户端本地命令，不进入服务端 `/redstonelink` 命令树）。
- 远外显模式切换：
1. `/rlclient display far_overlay occluded`：远外显文本被方块遮挡（不穿透）。
2. `/rlclient display far_overlay see_through`：远外显文本穿透显示。
- 配置落盘：命令会写入 `config/redstonelink-client.properties` 的 `client.serialOverlayFarSeeThrough`。
- 默认值：`client.serialOverlayFarSeeThrough=false`（默认不穿透）。

### 发射器与连接核心块半透明显示（客户端）
- 放置在世界中的发射器（toggle/pulse/sync）与连接核心块（core）使用半透明渲染层显示。
- 该显示调整仅影响客户端视觉表现，不改变红石行为与服务端判定。

## 三、命令与服主管理

- 适合：管理员、服主，或需要批量维护链路和节点的玩家。
- 建议先读：`新增命令入口` -> `批量命令确认规则（覆盖式）` -> `其他运维命令`。

### 新增命令入口
- 批量激活：`/redstonelink node activate triggerSource <source_serials> [toggle|pulse]`
- 白名单批量覆盖：`/redstonelink crosschunk whitelist set <role> <type> <serials> confirm`
- 批量退役：`/redstonelink node retire batch <type> <serials> confirm`
- 节点别名维护与反查：`/redstonelink node alias ...`

### 链接命令精简说明
- 已移除命令：`/redstonelink pair ...`、`/redstonelink pair_node ...`。
- 当前保留的链接写入入口：`/redstonelink link add/remove/set`。

### 批量命令确认规则（覆盖式）
- 所有批量覆盖式命令都需要 `confirm` 二次确认后才会真正生效。
- `link set`：当 `targets` 解析后数量大于 1 时，必须追加 `confirm`。
- `crosschunk whitelist set`：始终需要 `confirm`。
- `retire batch`：始终需要 `confirm`。
- `place fill`：非 `force` 执行一律拦截，需在原命令末尾追加 `confirm`（例如：`/redstonelink place fill <from> <to> <block> confirm`）。
- `confirm`、`resident`、`toggle`、`pulse` 都按独立命令节点解析，统一使用标准小写。

### 其他运维命令
1. `node get/list`：查询节点信息与状态。
   - `node get` 对在线 `core` 节点会额外输出一行运行态快照：`configuredMode/effectiveMode/active/resolvedStrength/output/maxSources`。
   - 其中 `maxSources` 为当前并列最大强度来源集合（升序），用于同强度并列场景审计。
   - `core` 状态采用结构真值持久化：`syncSourceStrengths`、`pulseEpoch+pulseUntilTick`、`toggleState`，`active/output` 为派生结果并在重启后重建。
2. `link get`：查询来源节点当前关联目标列表。
3. `audit summary text/csv`：输出审计汇总。
4. `place setblock dry_run/force`：单点放置流程预演与强制执行；`place fill` 仅保留 `force/confirm`。

### 节点别名命令
- `/redstonelink node alias set <type> <serial> <alias>`：给已分配且未退役的节点设置或更新别名。
- `/redstonelink node alias remove <type> <serial>`：移除指定节点当前别名。
- `/redstonelink node alias resolve <alias>`：按别名反查命中的节点；当只知道别名、不知道序号时，用这个命令拿回序号。
- `/redstonelink node alias list [type]`：列出全部别名，或仅列出某一类节点的别名。
- `type` 仅支持 `triggerSource|core`；命令权限走 `server.command.otherPermissionLevel`。
- 当前别名阶段是“只读辅助层”：近外显、部分 GUI/tooltip、命令读取会显示别名，但配对写入、`link set`、批量序号输入仍只接受序号。
- 别名校验规则：允许中文、字母、数字；不能为纯数字；若别名已被其他节点占用会直接拒绝。

### link set 目标上限默认值调整
- 配置项：`server.maxTargetsPerSetLinks`
- 默认值：`1024`
- 取值范围：`1~4096`（超出范围会自动夹紧）
- 适用入口：`/redstonelink link set ... <targets>`
- 裁决规则：客户端不再按本地数量上限前置拒绝，最终数量判定统一由服务端执行。

### /redstonelink 根命令权限等级配置
- 配置文件：`config/redstonelink-server.properties`
- 新增配置项：`server.command.permissionLevel`
- 默认值：`0`
- 取值范围：`0~4`（超出范围会自动夹紧）
- 生效范围：整个 `/redstonelink` 命令树（包含所有子命令）
- 自动化测试对应部分需要启动fabric loader(启动服务端或客户端)

## 四、权限与访问控制

- 适合：需要限制谁能看、谁能改，以及想直接抄推荐配置的服主。
- 建议先读：`权限配置总览与推荐矩阵`，再回查隐私和写控细节。

### 当前连接隐私命令与读取规则
- 服务端配置（`config/redstonelink-server.properties`）：
1. `server.currentLinksPrivacy.mode`：`hidden|masked|plain`。
2. `server.currentLinksPrivacy.overlayResponsePermissionLevel`：服务端是否回任何近外显包所需权限等级（`0~4`）；不满足时直接不回“当前连接/最终 IO”外显包，默认 `0`。
3. `server.currentLinksPrivacy.viewPermissionLevel`：查看受控连接所需权限等级（`0~4`）。
4. `server.currentLinksPrivacy.managePermissionLevel`：管理 `link privacy current_links mask` 命令所需权限等级（`0~4`）。
- 隐私名单命令（`/redstonelink link privacy current_links mask`）：
1. `add <type> <serial>`：加入加密名单。
2. `remove <type> <serial>`：移出加密名单。
3. `list <type>`：查看该类型加密名单。
4. `set <type> <serials> [confirm]`：批量覆盖（支持 `N`、`A:B`、`/` 分隔；`confirm` 为独立关键字，覆盖时需追加）。
- `type` 仅支持 `triggerSource|core`。
- 读取行为：
1. `hidden`：当前连接统一隐藏（显示为空/`-`）。
2. `plain`：当前连接不做隐私过滤。
3. `masked`：命中加密名单的来源节点受权限控制；同时对目标节点执行逐项过滤，配对 GUI 与近外显会剔除无权可见的受控目标；物品栏快照不受该过滤影响。
4. 命令读取（`/redstonelink node get`、`/redstonelink link get`）与配对 GUI、近外显共享同一隐私读取规则，不再输出绕过隐私控制的原始连接。
5. 状态面板新增订阅也复用同一来源节点读控；无权读取的节点不可新增订阅。若历史订阅已存在但当前玩家无权读取，该行仍保留在列表中，但状态列会显示隐藏态。

### 链接写入控制模式与受控名单
- 服务端配置（`config/redstonelink-server.properties`）：
1. `server.linkWriteControl.mode`：`full|limited|readonly`。
2. `server.linkWriteControl.limited.permissionLevel`：limited 模式越过“最大设置量”所需权限等级（`0~4`）。
3. `server.linkWriteControl.limited.maxSetSize`：limited 模式下单次设置允许的最大目标设置量。
4. `server.linkWriteControl.protected.permissionLevel`：命中受控名单后允许写入所需权限等级（`0~4`）。
5. `server.linkWriteControl.protected.managePermissionLevel`：管理受控名单命令所需权限等级（`0~4`）。
- 受控名单命令（`/redstonelink link write_control protected`）：
1. `add <type> <serial>`：加入受控名单（仅允许已分配且未退役序号）。
2. `remove <type> <serial>`：移出受控名单。
3. `list <type>`：查看该类型受控名单。
4. `set <type> <serials> [confirm]`：批量覆盖（支持 `N`、`A:B`、`/` 分隔；`confirm` 为独立关键字，覆盖需追加）。
- 写入拦截规则：
1. `readonly`：拒绝所有连接写入。
2. `limited`：按“本次目标设置量（set 后目标总数）”限制，不按增量差异数量限制。
3. 命中受控名单时，来源节点与受影响目标节点都要通过 `protected.permissionLevel` 权限校验。
4. `limited` 下对无权限玩家并非“全局不可改”，仅在命中受控名单（来源或受影响目标的当前连接修改）时拒绝；未命中时仍可写入（但仍受数量上限约束）。
5. `full`：不受受控名单约束（当前实现为直接放行）。

### 权限配置总览与推荐矩阵
- 说明：本节统一整理“读/写/命令”相关权限键与典型场景推荐值。
- 注意：当前配对 GUI 的写入提交走 `redstonelink link set ...`，因此会同时受 `/redstonelink` 根命令权限和写入控制策略约束。

### 权限项速查
| 配置项 | 默认值 | 作用范围 |
| --- | --- | --- |
| `server.command.permissionLevel` | `0` | `/redstonelink` 根命令权限门槛（包含 `link set`，因此也影响 GUI 提交） |
| `server.command.otherPermissionLevel` | `2` | 其他权限命令组门槛：`node activate`、`node retire`、`node get/list`、`node alias`、`link get`、`place`、`audit` |
| `server.web.recording.permissionLevel` | `2` | 状态面板录制网页功能门槛：开始录制与 recording 导出链路 |
| `server.web.graph.permissionLevel` | `2` | graph 可视化编辑网页功能门槛：graph 导出、网页预检与网页保存 |
| `server.currentLinksPrivacy.mode` | `masked` | 当前连接读取模式：`hidden/masked/plain` |
| `server.currentLinksPrivacy.overlayResponsePermissionLevel` | `0` | 服务端是否回近外显包（当前连接/最终 IO）所需最低权限 |
| `server.currentLinksPrivacy.viewPermissionLevel` | `2` | 查看 `masked` 受控连接所需权限等级 |
| `server.currentLinksPrivacy.managePermissionLevel` | `2` | `link privacy current_links mask` 管理命令权限 |
| `server.linkWriteControl.mode` | `limited` | 写入控制模式：`full/limited/readonly` |
| `server.linkWriteControl.limited.permissionLevel` | `2` | limited 模式下越过“最大设置量”限制所需权限 |
| `server.linkWriteControl.limited.maxSetSize` | `64` | limited 模式允许的最大“设置后目标总数” |
| `server.linkWriteControl.protected.permissionLevel` | `2` | 命中受控名单（来源/目标）后允许写入所需权限 |
| `server.linkWriteControl.protected.managePermissionLevel` | `2` | `write_control protected` 管理命令权限 |
| `server.command.linkSet.maxInputLength` | `1024` | `link set` 的 `targets` 原始输入最大长度（字符） |
| `server.command.activate.batchMaxSerials` | `1024` | `node activate` 批量来源数量上限 |
| `server.command.retire.batchMaxSerials` | `1024` | `node retire batch` 批量数量上限 |
| `server.command.privacy.currentLinksMask.maxSetSerials` | `1024` | `link privacy ... mask set` 批量数量上限 |
| `server.command.writeControl.protected.maxSetSerials` | `1024` | `link write_control protected set` 批量数量上限 |
| `server.command.crosschunk.whitelist.maxSetSerials` | `1024` | `crosschunk whitelist set` 批量数量上限 |
| `crosschunk.command.enabled` | `true` | 是否启用 `crosschunk` 命令树 |
| `crosschunk.command.permissionLevel` | `2` | `crosschunk` 命令树权限等级 |
| `server.command.rateLimit.actorGroup.other.baseCapacity` | `6` | other 命令组（activate/retire/place/audit/node/link 查询）个体基础容量 |
| `server.command.rateLimit.actorGroup.other.stepPerLevel` | `4` | other 命令组个体容量按权限等级的增量步长 |

### 命令频率防护（二期）
- 目标：防止高频命令刷屏/刷写，且避免“同权限玩家共享同一频率桶”导致单人独占容量。
- 防护层级（全部通过才放行）：
1. 全局窗口容量（Global）。
2. 权限层级窗口容量（Tier）。
3. 来源个体窗口容量（Actor，按来源身份区分）。
4. 来源+命令组窗口容量（ActorGroup）。
- 二期接入范围：
1. `link` 写组：`link add/remove/set`、`link write_control protected add/remove/list/set`。
2. `crosschunk` 命令组（含 `whitelist` 与 `preset` 子命令）。
3. `other` 组：`node activate`、`node retire`（含 `batch`）、`place`、`audit`、`node get/list`、`link get`。
- 关键配置（`config/redstonelink-server.properties`）：
1. `server.command.rateLimit.enabled`：总开关。
2. `server.command.rateLimit.windowTicks`：窗口长度（tick），范围 `1~2000`。
3. `server.command.rateLimit.global.capacity`：全局容量，范围 `1~200000`。
4. `server.command.rateLimit.tier.baseCapacity` / `stepPerLevel`：权限层级容量公式，`base` 范围 `1~200000`，`step` 范围 `0~200000`。
5. `server.command.rateLimit.actor.baseCapacity` / `stepPerLevel`：来源个体容量公式，`base` 范围 `1~200000`，`step` 范围 `0~200000`。
6. `server.command.rateLimit.actorGroup.linkRw.baseCapacity` / `stepPerLevel`：`link` 组个体容量公式，`base` 范围 `1~200000`，`step` 范围 `0~200000`。
7. `server.command.rateLimit.actorGroup.crosschunk.baseCapacity` / `stepPerLevel`：`crosschunk` 组个体容量公式，`base` 范围 `1~200000`，`step` 范围 `0~200000`。
8. `server.command.rateLimit.actorGroup.other.baseCapacity` / `stepPerLevel`：`other` 组个体容量公式，`base` 范围 `1~200000`，`step` 范围 `0~200000`。
- 容量公式：`容量 = base + permissionLevel * step`（权限等级 `0~4`）。
- 当前默认值按“约 50 名同权限玩家并发刷 `link add`”做基线估算：`global=3072`、`tier.base=600`、`tier.step=400`。
- 超限反馈：统一返回“操作过于频繁，请稍后再试”，不回显阈值细节。

### 推荐配置矩阵（按场景）
| 场景需求 | 推荐配置（关键项） | 跨区块相关建议 |
| --- | --- | --- |
| 单人开发/服主自测，功能全开 | `server.command.permissionLevel=2`；`server.currentLinksPrivacy.mode=plain`；`server.linkWriteControl.mode=full`；`crosschunk.command.permissionLevel=2` | 建议调试优先：`crosschunk.queue.enabled=true`、`crosschunk.forceLoad.enabled=true`、`crosschunk.forceLoad.mode=all`，便于快速验证跨区块链路。 |
| 小型合作服，普通玩家可通过 GUI/命令改连接，但限制规模 | `server.command.permissionLevel=0`；`server.currentLinksPrivacy.mode=masked`；`server.currentLinksPrivacy.viewPermissionLevel=2`；`server.linkWriteControl.mode=limited`；`server.linkWriteControl.limited.maxSetSize=8~32`；`server.linkWriteControl.limited.permissionLevel=2`；`server.linkWriteControl.protected.permissionLevel=2`；`server.linkWriteControl.protected.managePermissionLevel=3`；`crosschunk.command.permissionLevel=2` | 建议生产优先：`crosschunk.queue.enabled=true`、`crosschunk.forceLoad.enabled=true`、`crosschunk.forceLoad.mode=whitelist`；仅对关键链路维护 `crosschunk whitelist`，`resident` 只给常驻基础设施。 |
| 公共服，普通玩家只允许基础交互，不允许命令改连接 | `server.command.permissionLevel=2`；`server.currentLinksPrivacy.mode=masked`；`server.linkWriteControl.mode=readonly`；`server.linkWriteControl.protected.managePermissionLevel=3~4`；`crosschunk.command.permissionLevel=3~4` | 建议收敛跨区块入口：保留 `queue`，将 `forceLoad.mode=whitelist`；若不需要玩家运维，可将 `crosschunk.command.enabled=false` 或维持高权限仅管理员可用。 |
| 强保密服，连接信息默认不可见，仅管理组可查看/调整 | `server.command.permissionLevel=2`（或更高）；`server.currentLinksPrivacy.mode=hidden`（或 `masked` + 受控名单全量）；`server.currentLinksPrivacy.viewPermissionLevel=3~4`；`server.linkWriteControl.mode=limited/readonly`；`server.linkWriteControl.protected.permissionLevel=3~4`；`server.linkWriteControl.protected.managePermissionLevel=4`；`crosschunk.command.permissionLevel=4` | 建议最小暴露：`crosschunk.forceLoad.mode=whitelist`，并收窄 `crosschunk.whitelist.sourceTypes/targetTypes`；仅允许管理组维护白名单与 `resident`，普通玩家不开放跨区块命令。 |

### 场景落地建议
- 若希望“普通玩家可以使用 GUI 配对提交”，`server.command.permissionLevel` 不能高于玩家权限（通常设为 `0`）。
- 若希望“普通玩家可小规模改线，但禁止大规模覆盖”，优先用 `server.linkWriteControl.mode=limited` + `server.linkWriteControl.limited.maxSetSize`。
- 若希望“关键节点不允许被普通玩家改动”，把关键序号加入 `write_control protected` 名单，并提高 `protected.permissionLevel`。

## 五、跨区块与持久化

- 适合：需要处理跨区块链路、白名单常驻和重放策略的服主或维护者。
- 建议先读：`跨区块策略组合（2026-03-12）`，再按需查白名单、resident 和补发/退避细节。

### 退役与白名单强同步
- 任意退役路径（命令/批量/事件/方块流程）现在都走统一退役入口。
- 节点退役后会立即强同步清理该 `type+serial` 在 `source/target` 白名单中的条目，并联动清理 resident 标记。
- 该改动只补一致性，不改变退役命令语义与跨区块调度边界。

### resident 离线设置与延迟生效（覆盖旧规则）
- `crosschunk whitelist add ... resident` 与 `crosschunk whitelist set ... resident confirm` 支持离线序号设置，用于可携带/先配置场景。
- 离线期间不会续 resident 票据，也不会触发强制加载；节点重新上线后自动恢复生效。
- `linker` 仅是序号载体，不是在线节点入口；仅有 linker 序号时不会产生常驻强制加载效果。
- 不带 `resident` 的普通白名单入口保持原有“已分配且未退役”校验语义。

### 跨区块白名单常驻标签（resident）
- linker不可作为触发源常驻对象
- `resident` 负责长期持有已成功建票的白名单节点区块；票据本身不等于凭空补发一次 `sync`
- `whitelist add <role> <type> <serial> resident`：新增白名单并设置 `resident=on`。
- `whitelist add <role> <type> <serial>`：按严格命令语义写入并设置 `resident=off`（用于清零常驻）。
- `whitelist set <role> <type> <serials> confirm`：批量覆盖并统一 `resident=off`。
- `whitelist set <role> <type> <serials> resident confirm`：批量覆盖并统一 `resident=on`。
- `resident` 严格依赖白名单，不允许独立存在；`whitelist list` 会额外输出 resident 列表。
- 常驻区块加载仅使用本模组自有 ticket 类型，和其它模组的强制加载机制隔离。

### 区块激活器（Chunk Activator）
- 已放置区块激活器受邻居红石控制；仅在激活态下，按当前作用类型把节点集贡献到跨区块有效白名单。
- 同时维护 `triggerSource/core` 两套节点集与各自模式，当前只生效一套；切换作用类型不会清空另一套配置，每套容量 `32`。
- `force-load` 模式只贡献强加载资格；`resident` 模式会同时贡献强加载资格与 resident 常驻集合。
- 真值会持久化到世界级数据；普通区块卸载不会清空配置，只有物理破坏才会移除条目。

### 区块激活器与 sync 补发边界
- 对 `sync` 来说，自动补发的触发条件不是“有 resident/临时强加载”本身，而是“确实生成了一次离线 `sync` 传播事件”；票据只负责把目标变成可处理，真正会被补发的是那条离线 `sync` 事件。
- 新连接建立且目标区块已卸载：会尝试做一次 attach replay，把当前来源的可恢复 `sync` 状态发给新目标；若目标仍离线，则进入离线队列，后续自动补发。
- 目标离线时 signal 改变：会自动进入离线 `sync` 传播；`0 -> 15`、`15 -> 0`、强度变化都算，目标被拉起或自然上线后会自动落地。

### 跨区块接管提示（2026-03-13）
- 触发端在“强加载接管实际生效”时提示；持久队列转发不提示。
- 配置项（`config/redstonelink-server.properties`）：
1. `crosschunk.notify.enabled`：跨区块提示总开关（默认 `true`）。
2. `crosschunk.notify.mode`：提示模式（`simple` / `detailed`，默认 `simple`）。
- 显示规则：
1. 统一输出来源：`类型+序号`。
2. 仅展示强加载目标，每行显示 `类型+序号` 列表。
3. `simple` 每类最多展示 3 条，`detailed` 每类最多展示 50 条，超出部分显示 `(+n)`。

### 跨区块命令
- 命令根：`/redstonelink crosschunk`
1. `whitelist add <role> <type> <serial>`：新增运行态白名单。
2. `whitelist remove <role> <type> <serial>`：移除运行态白名单。
3. `whitelist list <role> <type>`：查看某角色+类型的白名单。
   - 输出结构：先输出一行分隔线 `------------------------------`，再输出一次 `role/type/count` 头信息。
   - 明细输出：每个序号单独一行，字段为 `serial/online/resident/dimension/chunk`（`online` 位于 `resident` 前）。
   - 其中 `chunk` 显示为 `chunkX,chunkZ`；节点离线或无在线节点时 `dimension` 与 `chunk` 显示为 `-`。
4. `whitelist clear <role> <type>`：清空某角色+类型的白名单。
5. `preset list`：列出只读 preset 名称。
6. `preset show <name>`：显示 preset 的 sources/targets 明细。

### 跨区块策略组合（2026-03-12）
- 语义约束：
1. `triggerSource` 仅可作为来源（映射 `LinkNodeType.TRIGGER_SOURCE`）。
2. `core` 仅可作为目标（映射 `LinkNodeType.CORE`）。
- 核心配置（`config/redstonelink-server.properties`）：
1. `crosschunk.queue.enabled`：跨区块持久派发队列总开关。
2. `crosschunk.queue.defaultTtlTicks`：持久派发队列通用 TTL（tick）。
3. `crosschunk.queue.maxPendingEntries`：持久派发队列总量硬上限（默认 `100000`，范围 `1~2000000`）；达到上限后，只拒绝新增 key，已存在 key 仍允许覆盖更新。
4. `crosschunk.dispatch.maxPerTick`：每 tick 从持久队列最多处理条目数（默认 `500`，范围 `1~20000`）。
5. `crosschunk.syncSignalPersistent`：是否启用 `sync` 不限时持久化兜底（默认 `false`）。
6. `crosschunk.syncSignalTtlTicks`：`syncSignalPersistent=false` 时，SYNC 事件 TTL（tick）。
7. `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst`：目标区块加载补发固定启用时，是否优先在 `CHUNK_LOAD` 当 tick 立即尝试补发；默认 `true`。
8. `crosschunk.syncSourceAttachReplay.enabled`：是否启用 `triggerSource` 重新 attach 时的 `sync-only replay`（默认 `false`）。
9. `crosschunk.directBatching`：loaded direct 链路的批提交模式。`off`=loaded direct `sync/toggle/pulse` 全部 immediate，`queued_only`=仅异步 loaded `sync` 进批提交，`all_direct`=loaded direct `sync/toggle/pulse` 与异步 loaded `sync` 统一进目标级批提交；默认 `all_direct`。
10. `crosschunk.dispatch.batchWindowTicks`：目标级批提交的固定延迟窗口（tick，范围 `0~2`）；`0`=当前 tick 对齐并允许 `END_SERVER_TICK` 后 same-tick late flush，`1`=固定延迟 `1 tick`，`2`=固定延迟 `2 tick`；默认 `0`。
11. `crosschunk.activation.pulse.relay.enabled`：是否启用 `pulse` 的普通 TTL relay（默认 `false`）。
12. `crosschunk.activation.pulse.ttlTicks`：`pulse` 普通 relay 的 TTL（tick）。
13. `crosschunk.activation.pulse.persistentExperimental`：是否启用 `pulse` 实验性不限时投递（默认 `false`）。
14. `crosschunk.activation.toggle.relay.enabled`：是否启用 `toggle` 的普通 TTL relay（默认 `false`）。
15. `crosschunk.activation.toggle.ttlTicks`：`toggle` 普通 relay 的 TTL（tick）。
16. `crosschunk.activation.toggle.persistentExperimental`：是否启用 `toggle` 实验性不限时投递（默认 `false`）。
17. `crosschunk.triggerSourceContextDetachInvalidation.enabled`：是否启用 `triggerSource` 的 `soft/context-detach invalidation`，仅剔除目标上的 `sync` 贡献（默认 `false`）。
18. `triggerSource` 的 `hard invalidation` 固定开启，不再提供独立配置项；来源离线/解绑/退役/删除等非 context-detach 失效仍只会剔除目标上的 `sync` 贡献。
19. `crosschunk.forceLoad.enabled`：强制加载总开关。
20. `crosschunk.forceLoad.mode`：`all` / `whitelist`。
21. `crosschunk.forceLoad.ticketTicks`：强制加载票据时长（tick）。
22. `crosschunk.forceLoad.maxPerTick`：每 tick 强制加载上限。
23. `crosschunk.forceLoad.maxPerSourcePerTick`：每来源每 tick 强制加载上限。
24. `crosschunk.resident.maxEntries`：resident 生效唯一节点总上限（默认 `128`，范围 `1~256`），按“手动 resident + 激活态区块激活器 resident 并集”去重计数。
25. `crosschunk.whitelist.sourceTypes` / `crosschunk.whitelist.targetTypes`：可参与白名单的类型。
26. `crosschunk.preset.<name>.sources` / `crosschunk.preset.<name>.targets`：只读 preset（`type:serial`）。
- 组合矩阵（未加载目标时）：
1. `queue=true` + `forceLoad=false`：仅持久队列缓冲（重启后可续跑）。
2. `queue=true` + `forceLoad=true` + `mode=whitelist`：持久队列 + 白名单强制加载。
3. `queue=true` + `forceLoad=true` + `mode=all`：持久队列 + 全量强制加载。
4. `queue=false` + `forceLoad=false`：直接跳过。
5. `queue=false` + `forceLoad=true` + `mode=whitelist`：仅白名单强制加载，未命中直接跳过。
6. `queue=false` + `forceLoad=true` + `mode=all`：全量强制加载（不依赖白名单）。
7. 防旧护栏：同 key 按版本单调拒旧；过期（TTL）事件直接丢弃。
8. 默认配置即推荐的信号模型是：来源 `hard invalidation` 固定开启；来源离线、解绑、退役、删除等真实下线会撤掉该来源在目标上的 `sync` 贡献。
9. 区块活动本身不决定来源逻辑有效性：区块暂时未加载、未活跃或仅发生 context detach，不会自动把来源判成无效；默认恢复主链是目标区块加载时的 `sync` 补发。
10. `sync` 默认不使用不限时持久化兜底（`crosschunk.syncSignalPersistent=false`）；未加载目标上的常规 relay/recovery 主链固定就是目标区块加载时的补发。
11. 打开 `crosschunk.syncSignalPersistent=true` 后，`sync` 才会按“最新状态”无限期等待目标恢复后补投递；更适合作为兜底策略，而不是默认主恢复链。
12. `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst=true` 时，会优先在 `CHUNK_LOAD` 当 tick 直接尝试补发；若目标这时尚未真正就绪，才回落到下一 tick 的本地重试队列。
13. 这条 `CHUNK_LOAD` 补发路径现已固定启用，不再提供独立总开关；若只想改时序，可改 `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst=false`，让其统一先延后一 tick。
14. `crosschunk.syncSourceAttachReplay.enabled=true` 时，`triggerSource` 重新 attach 后会按当前来源状态，对其已链接 `core` 重新发一次 `sync-only replay`；默认关闭，避免与放置后真实输入派发重复。
15. `crosschunk.directBatching=queued_only` 时，只有异步/队列链路命中的 loaded `sync` 进入 batch；loaded direct `sync/toggle/pulse` 仍立即生效。
16. `crosschunk.directBatching=all_direct` 时，loaded direct `sync/toggle/pulse` 与异步 loaded `sync` 会统一进入目标级批提交；这是当前默认值。
17. `crosschunk.directBatching=all_direct` + `crosschunk.dispatch.batchWindowTicks=0` 时，属于“最接近无额外 tick 级延迟”的固定延迟配置：所有 loaded direct `sync/toggle/pulse` 仍走统一 batch，但保持当前 tick 对齐，并允许 `END_SERVER_TICK` 后 same-tick late flush；它不是 immediate，只是尽量不引入额外 tick 级延迟。
18. `crosschunk.directBatching=off` + `crosschunk.dispatch.batchWindowTicks=0` 时，属于“最接近原版无延迟/到达即生效”的配置：loaded direct `sync/toggle/pulse` 不进入 direct batching，到达目标后立即应用；`batchWindowTicks=0` 仍建议保留，用于让异步 invalidation 等可批项目也保持无额外 tick 级延迟。
19. `pulse` 默认不做跨区块 relay；该能力仅作为兼容/实验入口保留，不属于推荐机器主链路。打开 `crosschunk.activation.pulse.relay.enabled=true` 后，才会按 TTL 缓冲；打开 `persistentExperimental=true` 后，可无限期等待目标加载后补发一次脉冲。
20. `toggle` 默认不做跨区块 relay；该能力仅作为兼容/实验入口保留，不属于推荐机器主链路。打开 `crosschunk.activation.toggle.relay.enabled=true` 后，才会按 TTL 缓冲；打开 `persistentExperimental=true` 后，可无限期等待目标加载后按净奇偶补发。
21. `pulse/toggle` 的 ready-drain / force-load 命中目标后，现也会复用 `crosschunk.dispatch.batchWindowTicks` 进入目标级批提交；生命周期 replay 仍保持 `sync-only`，不回放历史 `pulse/toggle` 事件。
22. `triggerSource` 的 `soft/context-detach invalidation` 只影响 `sync`，且默认关闭；开启 `crosschunk.triggerSourceContextDetachInvalidation.enabled=true` 后，来源仅因上下文脱附时也会剔除其在目标上的 `sync` 贡献并重算。
23. `triggerSource` 的 `hard invalidation` 固定开启；离线/解绑/退役/删除等非 context-detach 失效会持续自动剔除该来源在目标上的 `sync` 贡献，不会回滚目标已持久化的 `pulse/toggle` 事件结果。

### sync 目标区块加载补发
- 目标：在目标区块 `CHUNK_LOAD` 时，按来源端最近一次真实 sync 事件补发旧状态；该路径固定启用。
- 配置（`config/redstonelink-server.properties`）：
1. `crosschunk.syncTargetChunkLoadReplay.immediateAttemptFirst`：是否在 `CHUNK_LOAD` 当前 tick 先立即尝试一次补发（默认 `true`）；若当前 tick 目标仍未真正就绪，才回落到下一 tick 的本地重试队列。
- 行为说明：
1. 目标区块加载后若来源端存在 replay 快照，会按来源端原始 `tick/slot/seq` 补发 `sync`，不会把目标加载时刻错误盖成最新事件。
2. `immediateAttemptFirst=true` 时，会先尝试在 `CHUNK_LOAD` 当 tick 直接恢复；只有目标尚未真正就绪时，才延后到下一 tick 重试。
3. `immediateAttemptFirst=false` 时，保持保守模式：统一先延后一 tick，再走本地重试。
4. 该补发路径不再提供独立启停配置；命令 attach / replace 等新建链路路径仍不受这里的时序策略影响。

### 跨区块持久重试退避配置
- 目标：控制持久跨区块 pending 何时开始降频重试，以及降频后的重试间隔。
- 配置（`config/redstonelink-server.properties`）：
1. `crosschunk.retry.stage1.maxAttempts` / `crosschunk.retry.stage1.intervalTicks`：第 1 段最大失败次数与间隔。
2. `crosschunk.retry.stage2.maxAttempts` / `crosschunk.retry.stage2.intervalTicks`：第 2 段最大失败次数与间隔。
3. `crosschunk.retry.stage3.maxAttempts` / `crosschunk.retry.stage3.intervalTicks`：第 3 段最大失败次数与间隔。
4. `crosschunk.retry.stage4.intervalTicks`：第 4 段间隔，适用于超过第 3 段上限后的所有失败。
5. `crosschunk.retry.dropThreshold`：仅影响非持久事件的丢弃上限，不参与持久 pending 的分段重试。
- 行为说明：
1. 持久 `SYNC_SIGNAL` 属于不限时 pending，会长期保留并重试。
2. 默认分段表为：`1~99 -> 1 tick`，`100~499 -> 5 tick`，`500~999 -> 20 tick`，`>=1000 -> 100 tick`。
3. 每次失败后，持久 pending 会按当前失败次数所在的分段重算 `nextEligibleTick`，不再依赖旧的“两段式退避阈值”。
4. 目标区块一旦触发 `CHUNK_LOAD`，命中该区块的等待中持久 pending 会立即解除等待窗口，下一轮调度即可重新尝试派发。
5. 不限时 pending 默认不再触发通用 `warn/error` 重试阈值日志，仅在首次进入第 2 段及以上降频阶段时输出一次提示。

## 六、诊断与运行时工具

- 适合：要定位异常、复现问题、观察运行态或分析性能热点的维护者。
- 建议先读：`节点状态追踪与采样命令` -> `旧档 TP 卡顿最小诊断闭环（运行时）` -> `JFR 抓热点（推荐）`。

### 节点状态追踪与采样命令
- 目标：
1. 为 `core` 和 `sync triggerSource` 提供统一的即时状态读取与历史采样入口。
2. 便于 bench、线上诊断和后续状态面板复用同一份运行态数据。
- 前置条件：
1. 需在 `config/redstonelink-server.properties` 中保持 `server.command.nodeTrace.enabled=true`；该项默认开启。
2. 若 `core` 或 `triggerSource` 加载后自愈已开启，推荐保持该项开启，便于观察自愈前后的节点状态。
- 命令根：
1. `/redstonelink node trace mount <type> <serials> [every] [capacity]`
2. `/redstonelink node trace latest <type> <serials>`
3. `/redstonelink node trace read <type> <serial> [limit]`
4. `/redstonelink node trace unmount <type> <serials>`
5. `/redstonelink node trace list`
- 参数说明：
1. `type` 统一使用 `triggerSource|core`。
2. `serials` 支持批量序号格式：`N`、`A:B`，使用 `/` 分隔；`read` 仍保持单个 `serial`。
3. `every` 为采样周期，单位 tick，默认 `1`，最大 `1200`。
4. `capacity` 为 ring buffer 容量，默认 `128`，最大 `4096`。
5. `limit` 为读取最近样本条数，默认 `10`，最大 `256`。
- 当前支持范围：
1. `core`：支持在线/离线快照。
2. `triggerSource`：当前支持在线可识别的 `pulse/toggle/sync emitter`；挂载后即使暂时离线，也可继续按已知 `traceKind` 输出离线快照。
- 行为说明：
1. `mount/latest/unmount` 现在支持批量序号；当输入单个序号时，行为与旧版保持一致。
2. `mount` 会立即写入一条当前快照，不需要等待下一个 tick 才有第一条样本。
3. 历史样本按“最新优先”读取。
4. `RunFunctionalCase` 的第二阶段逐 tick 断言会先把 `read` 结果转成时间正序，再按 `mountRef/anchorRef` 做基线裁剪和有限窗口对齐。
5. `read` 本轮不做批量化，避免多节点历史样本一次性刷屏。
6. 采样时间粒度首版按服务器 `tick` 记录，`slot` 固定为 `0`。
7. `list` 仅展示当前服务端进程内已挂载采样器；服务端重启后需重新挂载。
- 示例：
```powershell
/redstonelink node trace mount core 101 1 256
/redstonelink node trace latest core 101
/redstonelink node trace read core 101 20
/redstonelink node trace mount triggerSource 202/205/208 4 128
/redstonelink node trace latest triggerSource 202:220
/redstonelink node trace unmount triggerSource 202/205/208
/redstonelink node trace list
```

### 输入播放命令
- 目标：
1. 用命令向一组 `triggerSource/core` 节点注入可重复的测试输入，便于 bench、压测和运行态诊断。
2. 第一版只做内存态 job，不写入持久化数据，也不覆盖第二阶段的 `pulse/toggle` 模拟抽象。
- 前置条件：
1. 需在 `config/redstonelink-server.properties` 中保持 `server.command.input.enabled=true`；该项默认开启。
2. 若 `triggerSource` 加载后自愈已开启，推荐保持该项开启，便于复现与诊断输入残留问题。
- 当前支持范围：
1. `triggerSource`：支持发射器输入口模拟，运行态按 `真实输入` 与 `模拟输入` 取较大值。
2. `core`：仅支持 `sync` 直输，走运行态临时桶，不写入真实来源桶。
3. 波形：仅支持 `square` 与 `custom`。
- 核心边界：
1. 所有输入 job 仅存在于当前服务端进程内；服务器重启后会全部丢失。
2. `core pulse/toggle` 贡献者键抽象本轮未实现，命令侧暂不支持。
3. 目标节点必须已分配、未退役，且当前在线或区块已加载。
4. 输入器产生的运行时模拟状态不会跨重启持久化；`core` 模拟 SYNC、`sync emitter` 运行时 replay 以及 emitter `POWERED` 状态都会在后续加载时按当前真实输入重新建立/校正。
- 命令根：
1. `/redstonelink input start triggerSource square <serials> <period_ticks> [high_ticks] [high_power] [low_power] [phase_ticks] [total_ticks]`
2. `/redstonelink input start triggerSource custom <serials> <sequence> [phase_ticks] [total_ticks]`
3. `/redstonelink input start core sync square <serials> <period_ticks> [high_ticks] [high_power] [low_power] [phase_ticks] [total_ticks]`
4. `/redstonelink input start core sync custom <serials> <sequence> [phase_ticks] [total_ticks]`
5. `/redstonelink input stop <job_id>`
6. `/redstonelink input list`
7. `/redstonelink input clear`
- 参数说明：
1. `serials` 支持与现有批量序号一致的输入格式：`N`、`A:B`，用 `/` 分隔。
2. `serials` 与 `sequence` 现在都按“空格终止的单个 token”读取，因此输入 `1:10 4`、`1/3/4 8`、`15/0/7/0 2` 时，后续整数参数会继续进入正常命令树解析。
3. `square` 的 `period_ticks` 为完整周期；`high_ticks` 为高电平持续 tick 数。
4. `custom` 的 `sequence` 支持两类格式：
   `0101` / `f0f0` 这类逐 tick 位串/十六进制串。
   `15/0/7/0` 这类显式功率列表。
5. `phase_ticks` 用于整体相位偏移。
6. `total_ticks=0` 表示无限持续，直到手动 `stop` 或 `clear`。
- 常用示例：
```powershell
/redstonelink input start triggerSource square 101:132 4 2 15 0 0 200
/redstonelink input start triggerSource custom 201/202 0101 0 80
/redstonelink input start core sync square 901:964 4 1 15 0 0 0
/redstonelink input start core sync custom 1201/1202 15/0/15/0 2 160
/redstonelink input list
/redstonelink input stop 1
/redstonelink input clear
```
- 行为说明：
1. 启动 job 时若存在离线目标、未加载区块目标或不支持的节点，会整体拒绝启动并返回对应序号。
2. `list` 展示当前运行中的 job、端点类型、波形摘要、目标数量、已运行 tick 和目标序号。
3. `clear` 会停止当前服务端上的全部输入 job，并同步清除相关运行态输入。

### 命令开关与加载后自愈开关
- bench 模式：
1. `server.command.benchmarkMode.enabled=true` 时，bench 相关命令可更方便地配合控制台或 RCON 执行。
2. 该开关不再控制 `input` 与 `node trace`。
- 独立命令开关：
1. `server.command.input.enabled=true`：是否启用输入播放命令与运行时服务，默认开启。
2. `server.command.nodeTrace.enabled=true`：是否启用节点状态追踪命令与采样服务，默认开启。
- 加载后自愈：
1. `server.runtime.loadResync.core.enabled=true`：启用 `core` 读档后的异步外显自愈，建议保持开启。
2. `server.runtime.loadResync.triggerSource.enabled=true`：启用 `triggerSource` emitter 读档后的异步输入自愈，建议保持开启。
3. `server.runtime.loadResync.maxRetry=40`：chunk 尚未就绪时允许的最大额外重试次数；`0` 表示只尝试当前这一轮，不再回队。
4. 两条自愈链路都采用非阻塞 `getChunkNow(...)` 消费；若超过重试预算仍无法取到 chunk，会记录一次告警日志，便于排查是否有任务被放弃。
5. 推荐搭配：
   `triggerSource` 自愈开启时，建议保持 `server.command.input.enabled=true`。
   `core` 或 `triggerSource` 自愈开启时，建议保持 `server.command.nodeTrace.enabled=true`。

### 高频 sync triggerSource 驱动连接核心块阵列的布局建议
- 适用场景：高频 `sync` triggerSource 驱动密集 `core` 阵列，尤其是连接核心块阵列。
- 结论：
1. 即使 `core` 阵列覆盖的区块总数不变，`triggerSource` 相对区块边界的位置也会显著影响性能。
2. 在同一个 `2x2 chunks` 的连接核心块阵列中，已观察到大致规律为：`阵列中心区块边界` 最重，`阵列边缘区块边界外沿` 次之，`阵列边缘区块边界内沿且与对应阵列部分同区块` 最轻。
3. 主要增幅不在跨区块派发或方块实体同步，而在原版 `setBlock -> neighbor update -> chunk/tracker/light` 链路。
- 布局建议：
1. 高频 `sync` triggerSource 尽量放在阵列内部某个区块的非边界区域。
2. 尽量避免把 `triggerSource` 放在 `2x2` 阵列的中心分界线上。
3. 若必须靠近边界，优先选择“仍与主要受影响阵列部分同区块”的内沿位置，不要放在外沿或中心边界。
4. 对连接核心块阵列，`triggerSource` 的边界位置敏感性通常高于“是否额外做一次方块实体客户端同步”这类微观差异。

### 高并发大型连接核心片路网注意事项
- 不推荐在同一时间高并发激活大型连接核心片路网，可能出现瞬时高延迟。
- 当前版本已移除“顶面 ACTIVE 客户端同步节流”配置项，不再支持该项调参。

### sync fanout 计数日志开关
- 目标：控制 `sync_fanout_slow` 是否附带 fanout 计数字段（`delta/total`）。
- 配置（`config/redstonelink-server.properties`）：
1. `crosschunk.diag.runtime.enabled`：运行时慢路径日志总开关。
2. `crosschunk.diag.runtime.warnThresholdMs`：慢路径阈值（毫秒）。
3. `crosschunk.diag.runtime.fanoutCounters.enabled`：是否输出 fanout 计数字段（默认 `false`）。
- 行为说明：
1. 当 `crosschunk.diag.runtime.fanoutCounters.enabled=false` 时，`sync_fanout_slow` 仅输出基础慢日志字段。
2. 当该开关为 `true` 时，日志会额外输出 fanout 计数（`fanoutRequest/centerNotify/neighborNotify/crossChunkSkip/fanoutDedupHit` 的 `Delta/Total`）。

### Lithium 诊断与严格模式清理
- 已移除命令：`/redstonelink diag lithium`。
- 已移除配置项：`compat.lithiumStrictMode`（`config/redstonelink-server.properties` 不再支持该键）。

### 旧档 TP 卡顿最小诊断闭环（运行时）
- 目标：定位三条关键慢路径是否触发。
1. `CHUNK_LOAD/CHUNK_UNLOAD` 生命周期投影。
2. `ENTITY_UNLOAD` 退役链路与待退役 tick 处理。
3. `sync fanout`（`triggerSource -> core` 扇出派发）。
- 配置（`config/redstonelink-server.properties`）：
1. `crosschunk.diag.runtime.enabled=true`
2. `crosschunk.diag.runtime.warnThresholdMs=25`
- 日志关键字（`latest.log` / `debug.log`）：
1. `[DiagRuntime] chunk_lifecycle_slow`
2. `[DiagRuntime] retire_entity_unload_slow`
3. `[DiagRuntime] pending_retire_tick_slow`
4. `[DiagRuntime] sync_fanout_slow`
5. `[DiagRuntime] link_saveddata_type_mismatch`（旧档类型兼容扫描：统计被 strict 解析丢弃的 type 原文）

### JFR 抓热点（推荐）
- 适用：复现“旧档 TP 后 tick 卡死/大抖动”。
1. 查进程：`jcmd -l`（Windows 若未加 PATH，可用 `%JAVA_HOME%\\bin\\jcmd.exe -l`）
2. 启动采样（120 秒）：`jcmd <PID> JFR.start name=rl_diag settings=profile filename=run/logs/rl_diag.jfr duration=120s`
3. 进入游戏复现 TP 卡顿。
4. 到时自动落盘；若需提前结束：`jcmd <PID> JFR.stop name=rl_diag`
5. 用 JMC 打开 `run/logs/rl_diag.jfr`，按 Hot Methods / Call Tree 查看 top hotspot。

### Spark 抓热点（可选）
- 适用：服务端安装 spark 后做快速火焰图。
1. 在服务端执行：`/spark profiler --timeout 120`
2. 复现 TP 卡顿。
3. 查看 spark 返回链接中的 top hotspot 与主线程栈。

### Spark 报告直连分析
- 适用场景：
1. bench 结果里只有 spark 链接，需要在不手点 viewer UI 的情况下直接读取指标或导出热点数据。
2. 需要把 spark 结果接入脚本化分析、问题复盘或后续性能门槛治理。
- 稳定入口：
1. 轻量 JSON 摘要：`https://spark.lucko.me/<code>?raw=1`
2. 完整 JSON：`https://spark.lucko.me/<code>?raw=1&full=1`
3. 原始二进制：`https://spark-usercontent.lucko.me/<code>`
- 说明：
1. `?raw=1` 走的是 spark 的 JSON service，适合直接读取 `metadata`、`TPS`、`MSPT`、内存、CPU 等摘要信息。
2. `?raw=1&full=1` 会返回完整 profile/heap JSON；对 profiler 来说，这比纯 viewer 页面更适合脚本化分析。
3. `spark-usercontent` 返回 viewer 实际使用的原始 spark 二进制；profiler 的 `Content-Type` 通常是 `application/x-spark-sampler`。
4. `path=` 查询对“对象 / 数组”更稳；例如 `?raw=1&path=$.metadata.platformStatistics.mspt.last1m`、`?raw=1&full=1&path=$.threads[0]`。
5. `path=` 直接取纯标量时，当前服务端表现不稳定，可能返回空串或 server error；更稳妥的做法是先取上层对象，再在本地继续解析。
- PowerShell 示例：
```powershell
$code = 'C9ErRWnBhQ'

# 1. 读取轻量摘要
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1" |
  Select-Object -ExpandProperty Content

# 2. 只取 MSPT 摘要对象，避免直接取标量
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1&path=$.metadata.platformStatistics.mspt.last1m" |
  Select-Object -ExpandProperty Content

# 3. 拉完整 profiler JSON
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark.lucko.me/$code?raw=1&full=1" `
  -OutFile ".\\run\\profiles\\spark-$code-full.json"

# 4. 直接下载原始 spark profiler 二进制
Invoke-WebRequest -UseBasicParsing `
  -Uri "https://spark-usercontent.lucko.me/$code" `
  -Headers @{ Accept = 'application/x-spark-sampler,application/x-spark-heap,application/x-spark-health' } `
  -OutFile ".\\run\\profiles\\spark-$code.sparkprofile"
```
- `spark2json` 兜底：
1. 官方仓库：`https://github.com/lucko/spark2json`
2. 最短 Docker 用法：`docker run -it --rm ghcr.io/lucko/spark2json node cli.js <code>`
3. 当 `?raw=1&full=1` 服务行为变化，或需要离线解析 `.sparkprofile` 文件时，优先切回 `spark2json`。
- 当前 bench 经验：
1. 如果只是判读 `TPS/MSPT/内存/CPU`，优先用 `?raw=1`。
2. 如果要做热点树、线程树或本地二次聚合，优先用 `?raw=1&full=1`。
3. 如果要长期稳定归档原始样本，额外保存一份 `spark-usercontent` 下载下来的 `.sparkprofile` 文件。

## 七、自动化测试与 Bench

- 适合：开发、回归测试、功能验证和性能基线采集。
- 建议先读：`自动化测试命令`，然后按需求进入三个 Bench 章节。

### 自动化测试命令
1. `./gradlew test`：稳定核心 `stable-core` 套件（默认门禁）。
2. `./gradlew testIntegration`：`integration` 标签测试。
3. `./gradlew testClient`：`client` 标签测试。
4. `./gradlew testSlow`：`slow` 标签测试。
5. `./gradlew testExtended`：稳定核心 + 扩展测试集合。
6. `./gradlew testCoverageReport`：生成 XML/HTML 覆盖率报告。
7. `./gradlew testCoverage`：执行扩展测试并校验覆盖率阈值。
8. `testApiLegacy` 已退役，不再作为可执行测试任务或 CI 入口保留。

### Bench 场景自动化与 spark 采集
- 目录：
1. 轻量性能矩阵：`tools/bench/matrix.json`
2. 正式性能基线矩阵：`tools/bench/matrix-baseline-256.json`
3. 重压性能矩阵：`tools/bench/matrix-performance-heavy.json`
4. crosschunk / loading stress 矩阵：`tools/bench/matrix-crosschunk-stress.json`
5. 物理世界端到端矩阵：`tools/bench/matrix-physical-world.json`
6. 执行脚本：`tools/bench/run-bench.ps1`
7. suite 编排脚本：`tools/bench/run-bench-suite.ps1`
8. bench datapack：`tools/bench/datapack/rl_bench`
9. dedicated server RCON 示例：`tools/bench/bench.server.properties.example`
- 前置条件：
1. 使用支持 RCON 的 dedicated server 场景；将 `server.properties` 参考 `tools/bench/bench.server.properties.example` 开启 `enable-rcon=true`。
2. bench 世界安装 spark，并确保能执行 `/spark ...` 命令。
3. 先把 datapack 安装到目标存档：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action InstallDatapack -SavePath run/saves/rl-bench`
4. 本文档中的脚本示例默认面向 Windows PowerShell 5.x；若你本机装了 PowerShell 7，也可自行改成 `pwsh`。
- 常用命令：
1. 列出场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action List`
2. 查看场景布局摘要：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -CaseId sync_1_to_64_core_dense`
3. 运行轻量纯性能场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -CaseId sync_1_to_64_core_dense -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
4. 查看正式 256 纯性能基线场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16`
5. 运行正式 256 纯性能基线场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId mixed_256_to_256_core_banded16 -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
6. 运行重压性能场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-performance-heavy.json -CaseId sync_1_to_2048_core_dense -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
7. 运行 crosschunk / loading stress 场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-crosschunk-stress.json -CaseId sync_queue_release_1024_single_shot -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
8. 运行物理世界端到端场景：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-physical-world.json -CaseId mixed_256_to_256_core_banded16 -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
9. 运行带玩家自动等待、初始化并自动传送到当前 case 观察位的单 case：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16 -SavePath 'D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template - 256' -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer '@a[tag=bench_runner,limit=1]' -PlayerReadyTimeoutMs 180000 -PlayerSetupCommands 'gamemode spectator @s' -AutoTeleportPlayerToObservationPoint`
10. 运行“外部客户端自动启动并自动进服”的单 case，并在放置后自动传送到观察位；若还要把真实客户端切前台并自动打开 `F3+2` tick 曲线，可同时传 `-BenchClientFocusWindow -BenchClientOpenTickCharts`：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunCase -MatrixPath .\tools\bench\matrix-baseline-256.json -CaseId sync_256_to_256_core_banded16 -SavePath 'D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template - 256' -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AutoStartBenchClient -BenchClientPlayerName op -BenchClientInstanceRoot 'D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client' -BenchClientStartCommand '.\start-client.bat' -BenchClientGameHost 127.0.0.1 -BenchClientGamePort 25565 -BenchClientFocusWindow -BenchClientOpenTickCharts -SyncLatestClientModJar -BuildBeforeSyncLatestModJar -PlayerSetupCommands 'gamemode spectator @s' -AutoTeleportPlayerToObservationPoint`
11. 若 `BenchClientStartCommand` 本身带有多层引号（例如 Prism 启动器命令），在 Windows PowerShell 5.x 下优先用“当前会话变量 + `& .\tools\bench\run-bench.ps1 ...`”调用，避免外层 `powershell -File` 先把该参数拆坏；例如：`$benchClientStartCommand='"D:\Prism Launcher\prismlauncher.exe" --dir "C:\Users\15166\AppData\Roaming\PrismLauncher" --launch "1.21.1" --profile "op" --server "127.0.0.1:25565"'; & '.\tools\bench\run-bench.ps1' -Action RunCase ... -BenchClientStartCommand $benchClientStartCommand`
12. 运行 send 过滤器隐藏拦截态 `a -> b -> c(重进 a)` 性能边界 suite：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 -SuitePath .\tools\bench\suites\send-filter-hidden-intercept-perf.json -RconPassword redstonelink-bench -BuildBeforeSyncLatestModJar -SyncLatestModJar`
- 当前内置样例：
1. 轻量性能：
   `sync_1_to_64_core_dense`
   `toggle_64_to_1_core_dense`
   `mixed_16_to_16_core_dense`
   `sync_1_to_256_core_dense`
   `mixed_64_to_64_core_dense`
2. 正式 256 基线：
   `sync_256_to_256_core_banded16`
   `mixed_256_to_256_core_banded16`
3. 重压性能：
   `sync_1_to_2048_core_dense`
   `toggle_1_to_2048_core_dense`
   `pulse_1_to_2048_core_dense`
   `sync_1024_to_1_core_dense`
   `toggle_1024_to_1_core_dense`
   `pulse_1024_to_1_core_dense`
   `sync_1024_to_1024_core_banded16`
   `mixed_1024_to_1024_core_banded16`
   `mixed_1024_to_1024_topology_mutation_batches`
4. crosschunk / loading stress：
   `sync_minimal_loop_single_pair`
   `send_filter_hidden_intercept_persistent_sync`
   `send_filter_hidden_intercept_sync_square_2tick`
   `send_filter_hidden_intercept_reload_replay`
   `sync_queue_release_1024_single_shot`
   `sync_queue_release_1024_blank_control`
   `sync_resident_256_distinct_chunks`
   `sync_force_load_128_bursty_distinct_chunks`
- 行为说明：
1. 脚本会通过 `/redstonelink place ...` 自动放置节点，再通过 `/data get block <pos> Serial` 回读 serial；建链阶段对 `broadcast_all`、`fan_in_first`、`banded` 会优先下发 `/redstonelink bench link apply ...` 结构化批量命令，若服务端不支持或返回命令级失败，再自动回退到旧的逐条 `link set`。
2. `tools/bench/matrix.json` 与 `tools/bench/matrix-baseline-256.json` 现在都属于“纯模组性能压测层”，统一通过输入器驱动 `triggerSource/core`，不再默认使用红石块控制带或 `node activate` 作为性能主驱动。
3. `tools/bench/matrix-performance-heavy.json` 现覆盖 `1 -> 2048` 广播、`1024 -> 1` 汇聚，以及 `1024 -> 1024` 的 sync/mixed 规则化多对多重压场景，适合定向上压。
4. `tools/bench/matrix-crosschunk-stress.json` 现覆盖五类专项：最小回环震荡、send 过滤器隐藏拦截态边界、`sync` 持久队列 `1024 pending` reload release、`256 resident` distinct chunks，以及 `128 force-load` 间歇突发；该矩阵默认支持在 `drive.steps` 中混合 `wait_ticks/command_assert` 时间线，并允许 case 显式关闭默认 case chunk 预加载。
5. 纯性能输入器模板现统一为 `0/15、2 tick、10Hz`，也就是 `periodTicks=2/highTicks=1`。
6. `tools/bench/matrix-physical-world.json` 保留红石块与命令驱动，专门用于物理世界端到端观察，便于对照实际游玩/F3 曲线。
7. 纯性能 matrix 现统一使用 `1600 tick = 80 秒` 驱动窗口，并按 `warmupTicks=400 / measureTicks=1200` 只统计后 60 秒 spark 结果。
8. 结果 JSON 默认按 matrix 分流：
   - lite：`run/profiles/bench-results/`
   - baseline-256：`run/profiles/bench-results-baseline-256/`
   - performance-heavy：`run/profiles/bench-results-performance-heavy/`
   - crosschunk-stress：`run/profiles/bench-results-crosschunk-stress/`
   - physical-world：`run/profiles/bench-results-physical-world/`
9. 纯性能 matrix 现在会在 case 结束后自动执行 `redstonelink input clear`，避免输入器 job 污染下一 case。
10. 纯性能 matrix 依赖 `server.command.input.enabled=true`；该项默认开启。
11. dedicated server 真实 `RunCase` 默认仍可提供 `-AsPlayer`，该玩家必须在线、具备命令权限，并处于目标场景所在维度；bench 脚本层仍会把相关命令包装成 `execute as <player> at <player> run ...`，但当 bench 客户端命令桥可用时，会自动剥掉该外壳并转交客户端桥执行，避免继续落回纯 RCON 语义。
12. 若服务器已开启 `server.command.benchmarkMode.enabled=true`，则 `RunCase` 可不传 `-AsPlayer`，bench 会直接用控制台/RCON 命令源执行 `place/link set` 与输入器命令；这更适合无人值守压测，也能剔除额外玩家干扰。
13. `send_filter_hidden_intercept_*` 与 `send-filter-hidden-intercept-perf.json` 属于设计空白边界性能观察；验收口径只看 spark profiler / spark health 是否出现异常热点、MSPT 异常或健康恶化，不以过滤器亮灭、core 外显或 reload 后功能状态作为通过条件。
13. 当提供 `-AsPlayer` 时，bench 现在会在执行 case 前自动轮询玩家上下文是否就绪；默认使用 `data get entity @s Pos` 作为探针，可通过 `-PlayerReadyProbeCommand` 覆盖。
14. `-PlayerReadyTimeoutMs` 与 `-PlayerReadyPollIntervalMs` 分别控制等待超时与轮询周期；当前默认轮询间隔已收紧到 `250ms`，若玩家在超时内仍未上线或选择器未命中，bench 会直接失败并给出最后一次探针响应。
- 新增专项 suite：`tools/bench/suites/server-basic-permission.json`，用于 dedicated server 下回归 `masked` 读控、`limited/protected` 写控与权限感知命令限流。该 suite 需要显式提供真实玩家 `-AsPlayer`，推荐传纯玩家名而不是复杂选择器，便于 `op/deop` 权限切换。
15. `-PlayerSetupCommands` 会在玩家就绪后、正式 `place/link/input start` 前，以相同玩家上下文依次执行；适合做 `tag/gamemode/tp` 等初始化。该参数只负责“等待并初始化已登录玩家”，不负责启动客户端或自动登录 bot。
16. `-AutoTeleportPlayerToObservationPoint` 为默认关闭的可选开关；启用后，bench 会在当前 case 的 `triggerSource/core` 放置与建链完成后，把玩家自动传送到主要结构最小轴向包围盒顶面中心上方，并朝向结构中心。该开关要求同时提供 `-AsPlayer`。
17. 当提供 `-AutoStartBenchClient` 时，bench 会先在外部客户端实例目录下写入 `config/redstonelink-bench-client.properties`，再启动客户端；客户端内的 RedstoneLink 会读取该文件并自动连接 `-BenchClientGameHost:-BenchClientGamePort`，掉线后按 `-BenchClientReconnectIntervalMs` 自动重连。当前默认 `BenchClientInitialConnectDelayMs=0`、`BenchClientReconnectIntervalMs=1000`。
18. `-AutoStartBenchClient` 第一版要求 `-BenchClientPlayerName` 是明确玩家名；若同时传 `-AsPlayer`，两者必须完全一致，暂不支持把复杂选择器当作自动启动客户端的主身份。
19. `-BenchClientCommandBridgeDispatchMode` 用于指定 bench 玩家命令桥的分发模式：`server_network` 保持“客户端发自定义 payload，服务端代执行”的兼容路径；`client_direct_command` 则让客户端直接调用原生命令发送链，并通过客户端收到的系统消息回填 response 文件。权限专项或需要逼近真实玩家口径时，优先使用 `client_direct_command`。
20. `-BenchClientInstanceRoot` 用于定位外部客户端实例的 `mods`、`config` 等目录；`-BenchClientStartCommand` 既可以直接启动实际游戏进程，也可以是 Prism 这类启动器命令。若使用启动器，bench 会按 `BenchClientInstanceRoot/BenchClientWorkingDirectory` 自动识别后续真正的 `java/javaw` 客户端进程并改为托管它。
21. Prism 一类启动器常会先起 `prismlauncher.exe`，随后再分叉真实 `javaw.exe`，而且其 Java 命令行可能只包含实例目录上一级（如 `...\instances\1.21.1\`）而不直接包含 `...\minecraft\`；bench 现已兼容这一启动/识别时序。
22. 若 Prism 启动阶段的认证/准备较慢，bench 会延长真实客户端进程发现窗口；若短时间内仍拿不到 `tracked pid`，但实例 `logs/latest.log` 已出现新的启动输出，则会先把会话保留为“已观察到启动证据”，后续继续通过玩家 ready 与收尾阶段的进程重发现完成验证，不再把“客户端已实际拉起”误判成启动失败。对带 `--server` 的 launcher auto-join 场景，bench 还会把最小初始连接延迟抬到 `20000ms`，尽量避开 case 入口阶段的 `reload/prepare` 时序。
23. `-SyncLatestClientModJar` 会把当前仓库构建出的最新运行 jar 同步到外部客户端的 `mods` 目录；单 case 只同步客户端，不会热替换已经运行中的 dedicated server mod。
24. 若同时需要 dedicated server 与外部客户端严格使用同一份 jar，推荐在 suite 中同时启用 `-SyncLatestModJar` 与 `-SyncLatestClientModJar`，再用 `-BuildBeforeSyncLatestModJar` 只构建一次。
25. `pulse/toggle` 的纯性能输入器压测会复用 emitter 自身边沿语义；若希望结果口径稳定，建议保持 `server.emitterEdgeMode=rising`。
26. spark 结果不再依赖 RCON 同步回显，脚本会在发出 spark 命令后轮询 `activity.json`。默认路径推导规则为：若 `SavePath` 位于 `.../saves/<world>` 或 `.../rl-cases/<world>`，则优先查 server root 下的 `spark/activity.json`，并回退尝试 `config/spark/activity.json`、`plugins/spark/activity.json`；其它路径则优先查 `SavePath` 父目录下的 `spark/activity.json`。
27. 若服务端 spark 活动文件不在上述默认位置，可显式传入 `-SparkActivityPath <绝对路径>`；若上传较慢，可用 `-SparkActivityTimeoutMs` 调大等待时间。
28. `-BenchClientFocusWindow` 会在 bench 识别出真实客户端进程后尽力把该窗口切到前台；若被系统前台策略拒绝，只记录结果，不会让 case 失败。
29. `-BenchClientOpenTickCharts` 会让客户端在进服稳定后幂等打开 `F3+2` 对应的 tick 曲线；配套延迟可用 `-BenchClientPostJoinActionDelayMs` 调整，默认 `1000ms`。
30. bench 客户端在自动进服后的启动窗口内，会自动清理启动链路残留界面，以及误弹出的 `ESC/回到游戏` 暂停类菜单；该清理只在短时间内生效，不会长期拦截你后续手动按 `ESC` 打开的菜单。
31. 若启动后仍停在菜单，可查看客户端日志中的 `Bench client automation observed startup in-world screen`，其中会打印实际 screen 类名与 `pauseScreen` 标记，便于继续定位是原版暂停菜单还是其它界面。
32. suite entry 现在支持可选 `templateWorldPath`；当某个功能/压力用例需要从专用模板世界起跑时，可只对该 entry 覆盖模板，而不影响同一 suite 里的其它 entry。

### Bench 功能验证脚本
- 目录：
1. 功能矩阵：`tools/bench/functional-matrix.json`
2. 执行脚本：`tools/bench/run-bench.ps1`
- 目标：
1. 复用现有 bench 骨架验证 `input start`、`node activate`、`node trace` 的联动功能。
2. 覆盖 `sync/pulse/toggle` 三类典型行为，并显式验证 `range/slash/mixed` 序号表达式。
- 当前内置样例：
1. 命令运行时第一期：
   `place_runtime_basic`、`node_query_retire_audit_basic`、`input_admin_basic`、`policy_runtime_basic`、`crosschunk_command_basic`
2. 功能行为回归：
   `link_lifecycle_add_remove_set_clear`、`sync_core_range_square_basic`、`sync_triggerSource_slash_square_basic`、`sync_triggerSource_custom_mixed_basic`、`pulse_activate_sparse_zip`、`toggle_activate_fanin_even_odd`、`input_activate_trace_command_alignment`
3. 跨区块与重启：
   `crosschunk_sync_triggerSource_zip_square`、`restart_persist_toggle_prepare`、`restart_persist_toggle_verify`
4. 第二期边界/跨维度/重启扩展：
   `command_boundary_matrix`
   `crosschunk_resident_offline_restore`
   `crossdim_sync_overworld_to_nether_zip_square`
   `crossdim_sync_nether_to_end_zip_square`
   `crossdim_sync_end_to_overworld_zip_square`
   `restart_input_job_prepare`
   `restart_input_job_verify`
   `crosschunk_sync_reload_replay_enabled`
   `crosschunk_sync_reload_replay_disabled`
   `crosschunk_force_load_mode_all`
   `crosschunk_force_load_mode_whitelist_unmatched`
   `reload_template_world_boot_basic`
   `crosschunk_target_attach_filters_multi_source_physical_remove`
   `direct_sync_batching_all_sync_multi_source_square`
   `crosschunk_pending_queue_batching_multi_source_manual_reload`
   `crosschunk_force_load_preload_batching_multi_source`
- 常用命令：
1. 列出功能 case：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action List -MatrixPath .\tools\bench\functional-matrix.json`
2. 查看功能 case 摘要：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action PrintCase -MatrixPath .\tools\bench\functional-matrix.json -CaseId sync_core_range_square_basic`
3. 运行单个功能 case：`powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench.ps1 -Action RunFunctionalCase -MatrixPath .\tools\bench\functional-matrix.json -CaseId sync_triggerSource_custom_mixed_basic -SavePath run/saves/rl-bench -RconHost 127.0.0.1 -RconPort 25575 -RconPassword redstonelink-bench -AsPlayer BenchBot`
- 行为说明：
1. `RunFunctionalCase` 仍会复用 `/redstonelink place ...`、`link set` 与 serial 回读流程，不单独维护第二套摆放逻辑。
2. 功能 phase 会优先按真实服务端 `gametime` 等待 tick，而不是只靠本地 `Start-Sleep` 估算。
3. 第二阶段主断言面已切到 `node trace read` 的逐 tick 对比；`trace latest` 仅保留给少量稳态场景或补证。
4. `trace mount` 会立即写入一条当前快照；`trace_read_tick_assert` 会按 `mountRef` 自动裁掉这条基线样本，从 `mountTick + 1` 开始对齐真实驱动窗口。
5. 严格逐 tick 断言要求对应挂载 phase 使用 `every=1`，并通过 `anchorRef` 把样本窗口限制在驱动命令附近的有限 `n tick` 范围内，不再做任意相位旋转。
6. 当 `trace_read_tick_assert` 以 `input start` phase 为 `anchorRef`，且目标端变化是在 `END_SERVER_TICK` 内经 `CoreDispatchBatchScheduler` flush 时，target trace 可能从 `jobStartTick + 1` 才看到新值；这不等价于 `crosschunk.dispatch.batchWindowTicks=0` 未生效，而是当前采样相位导致的可见性结果。
7. 当前相关顺序是：`NodeStateTraceService` 先在 `END_SERVER_TICK` 采样，`InputPlaybackService` 随后处理输入，`CoreDispatchBatchScheduler` 最后 flush；因此同一 tick 内即使已经完成 `window=0` 的 batch 结算，当前 tick 的 trace 仍可能记录旧态。
8. 因此，逐 tick 验证 `window=0` 时不要只看 target 是否在 `jobStartTick` 当 tick 立刻变化；若要验证“`END_SERVER_TICK` 已过后的 same-tick late-arrival 补 flush”分支，需要单独构造 late-arrival 用例。
9. 对 `sync` 与 `toggle` 来说，当前 `trace_read_tick_assert` 里的主要偏差是“观测平移”：以 `input start` 为锚点时，`triggerSource/core` 常从 `jobStartTick + 1` 才出现首个可见样本。这表示当前 trace 采样相位偏后，不等于运行时真实生效也晚了 1 tick。
10. 对 `pulse` 来说，除同样存在首拍观测平移外，还会有“可见高电平宽度少 1 tick”的现象：默认 `server.pulseDurationTicks=4` 时，`input start` 驱动的目标 trace 常见为 3 个高样本。这是因为触发当 tick 的采样已经结束，而到期 tick 又会先执行方块 scheduled tick 回落，导致首尾两个边界 tick 不一定都能被 `END_SERVER_TICK` 采到。
11. 因此，当前 bench 逐 tick 口径应理解为：`sync/toggle` 主要看相对窗口与模式切换，不把 `jobStartTick` 当作唯一真实首拍；`pulse` 则按“配置 4、trace 常见观测 3 高样本”的口径写断言。不要把 trace 高样本数直接等同于 pulse 真值有效期。
12. 当前内置逐 tick 模板包括：`sync_square`、`sync_custom`、`pulse`、`toggle_hold`。
13. 结果 JSON 默认写入 `run/profiles/bench-functional-results/`。
14. `command_assert` / `commandTemplate` 现在支持 `{{serialRefs:refA+refB:csv}}` 这类占位符：`serialRefs` 会把多个 serial 引用合并后按升序去重，`csv` 会按 `node get` 的 `maxSources` 输出口径渲染成 `1, 2, 3`。
15. phase 若需要一次引用多个 serial 集合，可使用 `serialRefs: ["refA", "refB"]`；该写法与旧的 `serialRef` / `sourceGroup` 二选一，不混写。

### Bench suite 每 case 新档编排
- 适用场景：
1. 每个 case 必须在全新世界中执行，避免旧档残留链接、区块状态或 spark 历史干扰结果。
2. 外部 dedicated server 需要按 case 顺序自动切 `level-name`、启停服并收集结果。
- 核心边界：
1. `tools/bench/run-bench.ps1` 仍只负责“单世界单 case”。
2. `tools/bench/run-bench-suite.ps1` 只负责外层生命周期编排：复制模板世界、切 `level-name`、启停 dedicated server、调用单 case bench、写 suite summary。
3. suite 通过 `-BenchAction` 决定子脚本动作：
   - `RunCase`：性能/采样 case
   - `RunFunctionalCase`：功能验证 case
- 推荐准备：
1. 先在 `tools/bench/bench.path-config.json` 配置 bench 本地路径；当前支持 `serverRoot`、`templateWorld`、`reloadTemplateWorld`、`prismLauncher`、`prismRootDir`。
2. `serverRoot` 指 dedicated server 根目录；`templateWorld` 与 `reloadTemplateWorld` 既可写绝对路径，也可写相对 `serverRoot` 的目录名。
3. `reload_template_world_boot` 一类 suite 条目会通过 `{{param:reloadTemplateWorld}}` 读取 reload 模板，不再写死绝对路径。
4. suite 运行时会自动把 case 世界创建到 `<serverRoot>\rl-cases\`。
5. `server.properties` 保持开启 RCON；若希望完全无人值守，建议同时开启 `server.command.benchmarkMode.enabled=true`。
6. Windows PowerShell 5.x 下，带 `-CaseIds @(...)` 的“多 case”命令不要用 `powershell -File` 调外层脚本；应在当前会话里用 `& .\tools\bench\run-bench-suite.ps1 ...` 直接执行，否则后续 case id 可能被误绑定成位置参数。
7. 若当前 PowerShell 会话禁止本地脚本执行，可先运行：`Set-ExecutionPolicy -Scope Process Bypass -Force`
- 常用命令：
1. 跑单个 case 新档：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -CaseIds sync_1_to_64_core_dense `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
2. 跑单个功能 case 的最短命令：
说明：以下命令默认从 `tools/bench/bench.path-config.json` 读取 `serverRoot` 与 `templateWorld`；若当前工作站已完成本地配置，可直接省略 `-ServerRoot` / `-TemplateWorldPath`。
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunFunctionalCase `
  -MatrixPath .\tools\bench\functional-matrix.json `
  -CaseIds sync_core_range_square_basic `
  -RconPassword redstonelink-bench
```
3. 一次性跑完整功能 case 集的最短命令：
```powershell
& .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunFunctionalCase `
  -MatrixPath .\tools\bench\functional-matrix.json `
  -CaseIds @(
    'sync_core_range_square_basic'
    'sync_triggerSource_slash_square_basic'
    'sync_triggerSource_custom_mixed_basic'
    'pulse_activate_sparse_zip'
    'toggle_activate_fanin_even_odd'
  ) `
  -RconPassword redstonelink-bench `
  -ContinueOnFailure
```
4. 如果服务器未开启 `server.command.benchmarkMode.enabled=true`，给单例或全量命令补玩家上下文：
```powershell
-AsPlayer BenchBot
```
5. 顺序跑多个 case：
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -CaseIds @(
    'sync_1_to_64_core_dense'
    'toggle_64_to_1_core_dense'
    'mixed_16_to_16_core_dense'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -ContinueOnFailure

```
6. 顺序跑功能验证 case：
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunFunctionalCase `
  -MatrixPath '.\tools\bench\functional-matrix.json' `
  -CaseIds @(
    'sync_core_range_square_basic'
    'sync_triggerSource_slash_square_basic'
    'sync_triggerSource_custom_mixed_basic'
    'pulse_activate_sparse_zip'
    'toggle_activate_fanin_even_odd'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AsPlayer BenchBot `
  -ContinueOnFailure
```
7. 如果服务器未开启 benchmark mode，仍可补玩家上下文：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -CaseIds sync_1_to_64_core_dense `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AsPlayer BenchBot
```
8. 顺序跑新档 suite，并在每个 case 前自动等待玩家选择器就绪后做初始化：
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -MatrixPath '.\tools\bench\matrix-baseline-256.json' `
  -CaseIds @(
    'sync_256_to_256_core_banded16'
    'mixed_256_to_256_core_banded16'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AsPlayer '@a[tag=bench_runner,limit=1]' `
  -PlayerReadyTimeoutMs 180000 `
  -AutoTeleportPlayerToObservationPoint `
  -PlayerSetupCommands @(
    'gamemode spectator @s'
  )
```
9. 顺序跑新档 suite，并自动启动外部客户端、自动入服、自动跨重启重连，同时把同一个本地构建 jar 同步到 server/client：
```powershell
& 'D:\OpenProjects\RedstoneLink\rl-release-no-mixin\tools\bench\run-bench-suite.ps1' `
  -ServerStartCommand '.\start.bat' `
  -MatrixPath '.\tools\bench\matrix-baseline-256.json' `
  -CaseIds @(
    'sync_256_to_256_core_banded16'
    'mixed_256_to_256_core_banded16'
  ) `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AutoStartBenchClient `
  -BenchClientPlayerName op `
  -BenchClientInstanceRoot 'D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client' `
  -BenchClientStartCommand '.\start-client.bat' `
  -BenchClientGameHost 127.0.0.1 `
  -BenchClientGamePort 25565 `
  -BenchClientFocusWindow `
  -BenchClientOpenTickCharts `
  -SyncLatestModJar `
  -SyncLatestClientModJar `
  -BuildBeforeSyncLatestModJar `
  -AutoTeleportPlayerToObservationPoint `
  -PlayerSetupCommands @(
    'gamemode spectator @s'
  )
```
10. 直接运行“首批必跑”纯跑版包装脚本：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-first-mandatory-suite.ps1 `
  -RconPassword redstonelink-bench
```
11. 直接运行“首批必跑”构建并同步版包装脚本：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-first-mandatory-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
12. 若希望仍直接调用 `run-bench-suite.ps1`，也可显式启用“先 `remapJar` 再复制最新 jar”：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\first-mandatory.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -BuildBeforeSyncLatestModJar `
  -SyncLatestModJar
```
- suite 外部客户端说明：
1. `-AutoStartBenchClient` 启用后，suite 会在首个 case 的 dedicated server 通过 `Wait-RconReady` 之后再启动一次外部客户端实例；这样即使 `BenchClientStartCommand` 自带 `--server`，也不会在服务端未 ready 时抢先连接。后续每轮 case dedicated server 重启后，客户端依赖模组内 bench 自动连接控制器自动回连。
2. suite 结束时，脚本会尝试关闭该外部客户端进程，并清理自动写入的 `config/redstonelink-bench-client.properties`。
3. 若 `BenchClientStartCommand` 是启动器命令，suite summary 里的 `launcherProcessId/gameProcessId/trackedProcessKind` 可用于确认 bench 最终托管的是启动器还是实际 Minecraft 进程。
4. 若 `-SyncLatestClientModJar` 开启，suite 会与 `-SyncLatestModJar` 共享同一份本地构建 jar，从而保证 server/client 运行版本一致。
5. 推荐把外部客户端实例单独放到专用目录，例如 `D:\OpenProjects\RedstoneLink\mcclient\rl-bench-client`，避免污染日常游玩实例。
6. 若传 `-BenchClientFocusWindow`，suite 启动客户端后会尽力把真实 Minecraft 窗口切到前台。
7. 若传 `-BenchClientOpenTickCharts`，客户端每次成功回连后都会幂等补开 `F3+2` tick 曲线，不会因重复 reconnect 把图表关掉。
8. suite summary 顶层会额外记录 `benchClientRequested/benchClientStartAttempted/benchClientStartSucceeded/benchClientStartError` 四个字段，用来区分“没请求自动客户端”“根本没尝试启动”“尝试了但失败”和“已成功启动”。
9. 若请求了 `-AutoStartBenchClient`，但 suite 在 dedicated server ready 后仍拿不到 bench client 会话，当前会直接失败，不再继续进入长时间的玩家就绪等待；排障时优先看控制台里的 `Bench client requested / Starting bench client / Bench client start failed` 日志。
- suite 分层：
1. `smoke.json`：最小烟雾验证，只确认 datapack、世界准备和最小功能 case 可跑通。
2. `command-basic.json`：命令与基础功能回归入口。
   第一期开启 `place_runtime_basic`、`node_query_retire_audit_basic`、`input_admin_basic`、`policy_runtime_basic`、`crosschunk_command_basic`。
   同时保留稳定功能场景 `link_lifecycle_add_remove_set_clear`、`sync_core_range_square_basic`、`input_activate_trace_command_alignment`。
   `bench/internal` 类命令暂未并入默认入口，避免未开启 benchmark mode 的环境直接失败。
3. `functional-phase2.json`：第二期功能覆盖，补命令边界、resident 离线恢复、输入 job 重启语义、跨维度 sync、`syncTargetChunkLoadReplay`、`forceLoad.mode`、pending queue batching、force-load preload batching 与 `directBatching=all_direct` 组合。
4. `first-mandatory.json`：当前首批必跑，仍可直接使用 `run-first-mandatory-suite*.ps1` 两个包装脚本。
5. `functional-regression.json`：功能回归，覆盖 F02/F03/F04。
6. `restart-crosschunk.json`：跨区块与重启专项，覆盖 X01/X04/R02。
7. `performance-lite.json`：轻量纯性能场景，统一改为输入器驱动，只做脚本连通性、spark 链路与快速性能回归。
8. `performance-baseline.json`：正式纯性能基线，从 `256x256` 规则化多对多场景起步。
9. `performance-physical-world.json`：物理世界端到端场景，保留红石块/命令驱动，观察真实输入与邻居更新成本。
10. `crosschunk-stress-sync.json`：crosschunk 压力一期，只跑 `sync_queue_release_1024_single_shot` 与 `sync_queue_release_1024_blank_control`。
11. `crosschunk-stress-full.json`：扩展压力集，补 `sync_minimal_loop_single_pair`、`sync_resident_256_distinct_chunks` 与 `sync_force_load_128_bursty_distinct_chunks`。
12. `nightly-full.json`：夜间全量，覆盖当前全部功能、跨区块/重启与纯性能基线场景；物理世界端到端场景单独运行。
- 推荐回归封装与命令文件：
1. 推荐把功能/生命周期回归统一收敛到 4 类：
   - `smoke`：最小烟雾验证，只看 bench 链路与最小功能是否可跑。
   - `regression-core`：命令、基础行为、link 生命周期、input/trace 主链路。
   - `regression-lifecycle`：跨区块、跨维度、重启、reload replay、force-load、resident、模板世界加载。
   - `regression-full`：`core + lifecycle` 的完整回归集合。
2. 对应 suite 文件：
   - `tools/bench/suites/smoke.json`
   - `tools/bench/suites/regression-core.json`
   - `tools/bench/suites/regression-lifecycle.json`
   - `tools/bench/suites/regression-full.json`
3. 对应包装脚本：
   - `tools/bench/run-smoke-suite.ps1`
   - `tools/bench/run-smoke-suite-build-sync.ps1`
   - `tools/bench/run-regression-core-suite.ps1`
   - `tools/bench/run-regression-core-suite-build-sync.ps1`
   - `tools/bench/run-regression-lifecycle-suite.ps1`
   - `tools/bench/run-regression-lifecycle-suite-build-sync.ps1`
   - `tools/bench/run-regression-full-suite.ps1`
   - `tools/bench/run-regression-full-suite-build-sync.ps1`
4. 这些回归包装脚本默认都不会自动启动客户端，也不会自动把玩家传送到观察位；尤其 `regression-lifecycle` / `regression-full` 故意避免自动 TP，防止 crosschunk / loading 类用例被额外玩家位移干扰区块加载。
5. 若服务器未开启 `server.command.benchmarkMode.enabled=true`，仍可显式传 `-AsPlayer <玩家>`；这只用于借用命令上下文，不会启用自动 TP。
- 推荐回归包装脚本命令：
1. 纯运行 smoke：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-smoke-suite.ps1 `
  -RconPassword redstonelink-bench
```
2. 构建并同步服务端最新 jar 后运行 smoke：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-smoke-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
3. 纯运行核心回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-core-suite.ps1 `
  -RconPassword redstonelink-bench
```
4. 构建并同步服务端最新 jar 后运行核心回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-core-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
5. 纯运行生命周期回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-lifecycle-suite.ps1 `
  -RconPassword redstonelink-bench
```
6. 构建并同步服务端最新 jar 后运行生命周期回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-lifecycle-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
7. 纯运行完整回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-full-suite.ps1 `
  -RconPassword redstonelink-bench
```
8. 构建并同步服务端最新 jar 后运行完整回归：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-regression-full-suite-build-sync.ps1 `
  -RconPassword redstonelink-bench
```
9. 若只想定向跑某几个 case，可对上述任意脚本追加 `-CaseIds`：
```powershell
& .\tools\bench\run-regression-core-suite.ps1 `
  -CaseIds @(
    'c01_place_runtime'
    'f03_sync_range'
  ) `
  -RconPassword redstonelink-bench
```
10. 若本轮需要显式玩家上下文，但仍不希望自动 TP，可追加：
```powershell
-AsPlayer BenchBot -PlayerSetupCommands 'gamemode spectator @s'
```
- 运行其他 suite 时，统一直接传 `-SuitePath`：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\smoke.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
- 跑服务器基本权限专项并强制使用客户端直发命令桥：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\server-basic-permission.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -AsPlayer BenchBot `
  -AutoStartBenchClient `
  -BenchClientPlayerName BenchBot `
  -BenchClientCommandBridgeDispatchMode client_direct_command
```
- 跑 10Hz 重压性能 suite：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\performance-heavy.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
- dedicated server 定向跑 `mixed_1024_to_1024_core_banded16` 单 case（当前 `1024 -> 1024` mixed 重压入口，建议隐藏窗口并适度拉长启动等待）：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -BenchAction RunCase `
  -MatrixPath '.\tools\bench\matrix-performance-heavy.json' `
  -CaseIds mixed_1024_to_1024_core_banded16 `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench `
  -ServerWindowMode Hidden `
  -StartupTimeoutMs 240000
```
- 跑 crosschunk 压力一期 suite：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\crosschunk-stress-sync.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
- 跑扩展 stress suite：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-bench-suite.ps1 `
  -ServerStartCommand '.\start.bat' `
  -SuitePath '.\tools\bench\suites\crosschunk-stress-full.json' `
  -RconHost 127.0.0.1 `
  -RconPort 25575 `
  -RconPassword redstonelink-bench
```
- 性能 suite 包装命令文件：
1. `tools/bench/run-performance-lite-suite.ps1`
2. `tools/bench/run-performance-lite-suite-build-sync.ps1`
3. `tools/bench/run-performance-heavy-suite.ps1`
4. `tools/bench/run-performance-heavy-suite-build-sync.ps1`
5. 以上 4 个脚本默认都已包含：
   - 外部客户端自动进服
   - 自动传送到当前 case 观察位
   - 客户端窗口聚焦
   - 自动打开 `F3+2` tick 曲线
   - dedicated server 默认隐藏启动（`ServerWindowMode=Hidden`）
   - dedicated server 默认请求高优先级（`ServerPriorityClass=High`），并优先对真实 Java server 进程而不是外层 `cmd.exe` wrapper 生效
   - 默认玩家/档位：`BenchClientPlayerName=op`、Prism 实例 `1.21.1`
6. 跑轻量性能包装版：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-lite-suite.ps1
```
7. 跑重压性能包装版：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-heavy-suite.ps1
```
8. 跑“先 remapJar 并同步 server/client 最新模组”的轻量性能包装版：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-lite-suite-build-sync.ps1
```
9. 跑“先 remapJar 并同步 server/client 最新模组”的重压性能包装版：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-heavy-suite-build-sync.ps1
```
10. 若只想跑指定 case，可直接覆盖 `-CaseIds`：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\bench\run-performance-heavy-suite.ps1 `
  -CaseIds sync_1_to_2048_core_dense
```
11. 若要覆盖 dedicated server 行为，可显式传 `-ServerWindowMode Normal|Minimized|Hidden` 与 `-ServerPriorityClass Idle|BelowNormal|Normal|AboveNormal|High|RealTime`；原始 `run-bench-suite.ps1` 默认 `ServerWindowMode=Normal` 且不主动请求优先级，性能/必跑包装脚本默认 `ServerWindowMode=Hidden`、`ServerPriorityClass=High`。
- 性能 suite 用例列表与分类：
1. `performance-lite.json`：轻量快速回归，适合验证脚本链路、客户端自动观察链路与 spark 采样链路。
2. `performance-lite.json` 广播类：
   `sync_1_to_64_core_dense`：单个 sync triggerSource 广播到 `8x8` core 阵列。
3. `performance-lite.json` 汇聚类：
   `toggle_64_to_1_core_dense`：`8x8` toggle triggerSource 阵列汇聚到单个 core。
4. `performance-lite.json` 混合多对多类：
   `mixed_16_to_16_core_dense`：sync / pulse / toggle 三组来源混合驱动 `4x4` core 阵列。
   `mixed_64_to_64_core_dense`：`64` 个 mixed triggerSource 同时驱动 `8x8` core 阵列，属于 lite 内较高负载入口。
5. `performance-lite.json` 广播增强类：
   `sync_1_to_256_core_dense`：单个 sync triggerSource 广播到 `16x16` core 阵列，作为 lite 内正式压测入口。
   `sync_minimal_loop_single_pair`：`1 sync -> 1 core -> 1 sync -> 1 core` 交叉闭环，只对其中 1 个 sync 施加 `1 tick` 初始激活，再观察回路后续震荡与静息过程。
6. `performance-heavy.json`：10Hz 输入器重压集合，适合做纯模组高压采样与回归。
7. `performance-heavy.json` 单源广播类：
   `sync_1_to_2048_core_dense`：单个 sync triggerSource 广播到 `64x32` core 阵列。
   `toggle_1_to_2048_core_dense`：单个 toggle triggerSource 广播到 `64x32` core 阵列。
   `pulse_1_to_2048_core_dense`：单个 pulse triggerSource 广播到 `64x32` core 阵列。
8. `performance-heavy.json` 多源汇聚类：
   `sync_1024_to_1_core_dense`：`1024` 个 sync triggerSource 汇聚到单个 core。
   `toggle_1024_to_1_core_dense`：`1024` 个 toggle triggerSource 汇聚到单个 core。
   `pulse_1024_to_1_core_dense`：`1024` 个 pulse triggerSource 汇聚到单个 core。
9. `performance-heavy.json` 规则化多对多类：
   `sync_1024_to_1024_core_banded16`：`1024 -> 1024` 的 sync 规则化多对多映射，是更高一级正式重压基线。
   `mixed_1024_to_1024_core_banded16`：`1024 -> 1024` 的 mixed 规则化多对多映射，覆盖 sync / pulse / toggle 混合运行态。
   `mixed_1024_to_1024_topology_mutation_batches`：`1024` 个 mixed triggerSource 在持续输入期间连续执行多轮结构化批量 relink，专门观察运行期大批量拓扑替换成本；heavy suite 会对该 entry 局部放宽 `LINK_RW` 命令限流，避免测到限流而不是测到拓扑修改本身。
10. `crosschunk-stress-sync.json`：
   `sync_queue_release_1024_single_shot`：目标离线时触发一次 `1 -> 1024` 的真实高态切换，再 reload 目标，观察 `1024 pending` 的集中释放。
   `sync_queue_release_1024_blank_control`：保留同一套 unload/reload 时间线，但不制造状态切换，用于扣除 reload 本底与观察开销。
11. `crosschunk-stress-full.json` 补充项：
   `sync_minimal_loop_single_pair`：`1 sync -> 1 core -> 1 sync -> 1 core` 交叉闭环，只对其中 1 个 sync 施加 `1 tick` 初始激活，再观察回路后续震荡与静息过程。
   `sync_resident_256_distinct_chunks`：`1 -> 256`，目标分散到 `256` 个 distinct chunks，并通过 resident 持有，观测 resident 常驻下的稳定同步成本。
   `sync_force_load_128_bursty_distinct_chunks`：`1 -> 128`，目标分散到 `128` 个 distinct chunks，并依赖短 ticket force-load 制造多轮自动加载/卸载突发。
- 推荐用法：
1. 本地快速自测先跑 `smoke.json`。
2. 命令/功能入口回归优先跑 `command-basic.json`、`first-mandatory.json` 或 `functional-regression.json`。
3. 改动跨区块或持久化逻辑时补跑 `restart-crosschunk.json`。
4. 只想快速看脚本、客户端自动观察链路与 spark 链路时，优先跑 `run-performance-lite-suite.ps1`。
5. 做纯模组高压采样或回归时，优先跑 `run-performance-heavy-suite.ps1`。
6. 若本轮还需要先构建并同步 server/client 两端最新模组，改用对应 `*-build-sync.ps1`。
7. 做纯模组性能基线时跑 `performance-baseline.json`。
8. 要对照真实世界输入/F3 体感时补跑 `performance-physical-world.json`。
9. 夜间巡检或较大版本收口时跑 `nightly-full.json`；若本轮还需要物理世界端到端证据，再单独追加 `performance-physical-world.json`。
- 输出说明：
1. 单 case bench 结果仍写入 `run/profiles/bench-results/`。
2. suite 汇总写入 `run/profiles/bench-suite-results/<timestamp>/summary.json`。
3. case 世界默认保留在 `mcserver\rl-cases\<worldName>`，便于复盘；若确认只关心结果文件，可追加 `-DeleteCaseWorldOnSuccess` 删除成功 case 的世界目录。
4. suite 执行期间会临时把 `server.properties` 的 `level-name` 改成 `rl-cases/<worldName>`。
5. 当 `-BenchAction RunFunctionalCase` 时，suite summary 会额外记录 `passed/checksCount/failedChecksCount`；此模式不依赖 spark。
6. 当启用 `-SyncLatestModJar` 或 `-SyncLatestClientModJar` 时，suite summary 会额外记录本轮使用的本地 jar，以及 server/client 两端各自复制后的 jar 与被替换掉的旧 `redstonelink*.jar`。
7. 当某个 suite entry 覆盖了 `templateWorldPath` 时，suite summary 的对应 result 会记录该 entry 实际使用的模板世界路径，便于复盘“是否确实从专用模板起跑”。
- 注意事项：
1. suite 启动前请先确认 dedicated server 未在运行，否则无法保证 `level-name` 切换后真的加载新世界。
2. `-ServerStartCommand` 必须是前台阻塞型启动命令；若脚本内部自行 `start` 新窗口并立即退出，suite 将无法可靠等待服务端结束。
3. suite 结束后会自动把 `server.properties` 内容恢复为运行前的原始文本。
 4. `-BuildBeforeSyncLatestModJar` 需要与 `-SyncLatestModJar` 或 `-SyncLatestClientModJar` 一起使用；推荐默认 `BuildTask=remapJar`，这样只生成运行 jar，不额外跑整套 `build`。
 5. 如需强制指定本次要同步的产物，可传 `-ModJarPath <path>`；否则脚本会自动从 `build/libs` 里选择最新的运行 jar，并排除 `*-sources.jar`。
