# RedstoneLink 项目审查报告

审查时间：2026-03-03  
审查基线：`dev` 分支（`5d8dbdb`）  
审查方式：静态代码审查 + 构建验证（`gradlew test` / `gradlew check`）

## 结论概览
- 严重问题：1
- 中风险问题：2
- 低风险问题：1
- 测试缺口：存在（未发现自动化测试产物）

## 发现的问题（按严重级别排序）

### 1. [严重] 自动退役链路覆盖不全，创造模式破坏/非掉落销毁会遗留序号与链接
**证据**
- 退役事件仅处理 `ItemEntity` 且要求 `RemovalReason.KILLED`：  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/data/LinkNodeRetireEvents.java:19`  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/data/LinkNodeRetireEvents.java:24`
- 核心/按钮/发射器移除时只执行 `unregisterNode()`，不执行 `retireNode()`：  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/block/LinkCoreBlock.java:105`  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/block/LinkButtonBlock.java:93`  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/block/LinkSignalEmitterBlock.java:103`  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java:111`  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/block/LinkToggleLeverBlock.java:87`

**影响**
- 节点在可判定销毁场景下可能只“下线不退役”，链接台账残留，审计结果失真。  
- 该行为与“自动清理策略”目标冲突，长期运行会累计脏数据。

**建议**
- 在方块销毁路径补充“可判定销毁场景”退役逻辑（例如创造模式破坏、无掉落销毁）。  
- 对无法即时判断的路径增加后台清理/巡检策略（按序号回查在线节点与链接一致性）。

**措施**
- 先分析物品返回原因种类，再请求下一步
- 添加discard()原因，连接方式为或
- 部分实现-创造模式

### 2. [中] 遥控器配对入口硬编码“必须潜行”，与全局交互配置不一致

**措施**
- 产品层面确实要强制潜行，新增专用配置项并在文档中明确。

### 3. [中] 遥控器触发失败时统一提示“未设置目标”，丢失真实失败原因
**证据**
- 遥控器将“触发失败”与“目标为空”合并为同一提示：  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/item/LinkerItem.java:176`
- 触发结果对象本身携带 `reasonKey`：  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/api/v1/model/TriggerResult.java:9`
- API 层会返回明确失败原因（如 source serial 未分配/已退役）：  
  `/d:/OpenProjects/RedstoneLink/redstonelink-template-1.21.1/src/main/java/com/makomi/api/v1/internal/RedstoneLinkApiImpl.java:293`

**影响**
- 现场排障难度增加，用户和运维无法区分“未配对”与“源序号非法/已退役”等不同故障。  
- 增加误操作和重复工单概率。

**措施**
- 优先使用 `TriggerResult.reasonKey` 映射提示；仅在 `totalTargets==0` 时展示“未设置目标”。



### 4. [低] 元数据仍含模板仓库地址，影响发布可追溯性

**措施**
-替换为项目真实主页与源码仓库地址。

## 验证与测试覆盖情况
- 已执行：`gradlew test`、`gradlew check`（命令返回成功）。  
- 观察到：未生成 `build/test-results/test` 与 `build/reports/tests/test/index.html`，说明当前缺少自动化测试用例或未接入测试任务。  
- 建议优先补齐：  
  1. 退役与清理回归（创造破坏、岩浆销毁、区块卸载）。  
  2. 遥控器交互矩阵（潜行/非潜行、主副手、配置开关）。  
  3. 触发失败 reasonKey 映射与消息断言。

## 开放问题
1. 遥控器“配对必须潜行”是产品设计固定要求，还是应遵从全局配置？  
2. 创造模式破坏后是否要求立即退役，还是仅下线并允许后续人工退役？
