# graph聚合展开态与triggerSource聚合修复

生成时间：2026-04-18 19:08:04
文件名：2026-04-18_190804_graph聚合展开态与triggerSource聚合修复.md

## 任务背景

当前 graph 网页前端存在两个聚合显示回归：

1. 频道模式下聚合块文案显示为“未展开”，但实际成员节点已经出现在画布中，显示状态与可视结果不一致。
2. 序号模式下 `triggerSource` 聚合块缺失，导致本应压缩的来源侧高扇出结构没有被聚合表达出来。

用户要求继续只修前端视图层，不改真实保存语义。

## 方案详情

### 现状分析

- 频道模式当前把聚合块是否展开直接绑定到 `aggregateNode.expanded`，但最终画布里成员节点是否可见还会受强制显示、搜索命中等“可视裁剪”影响，所以存在“逻辑未展开、视觉已展开”的错位。
- 序号模式的第二阶段 `triggerSource` 聚合仍然建立在原始 `source -> core` 真实边上，并且会跳过任何目标集合中包含已进入 `core aggregate` 的来源节点，因此在当前较低阈值下，很多来源侧聚合直接被第一阶段吃掉。

### 技术方案

- 对两种模式统一引入“视觉展开态”概念：只要聚合块成员节点实际进入最终画布，就把该聚合块视为已展开，用于节点文案、样式、布局边和包围框渲染。
- 保留序号模式第一阶段 `core aggregate`，但把第二阶段 `triggerSource aggregate` 的输入从“真实边”改成“第一阶段之后的可见连接图”：
  - 第二阶段允许把 `triggerSource aggregate` 连接到 `core aggregate`
  - 仍继续维护底层真实 `core` 集合，用于文案、详情和搜索匹配
- 频道模式只修视觉展开态，不重写其本轮已接入的聚合结构。

### 影响范围

- `webapp/src/components/graphViewer/canvas.tsx`
- `webapp/src/components/GraphViewer.tsx`

## 原子步骤清单

### 步骤 1：补统一视觉展开态
- **操作对象**：`webapp/src/components/graphViewer/canvas.tsx`
- **具体动作**：在最终画布节点集合生成后，回写聚合块的视觉展开态，并让布局边、包围框与节点标签都使用这套状态。
- **预期结果**：频道模式和序号模式都不会再出现“成员已显示但聚合块仍标未展开”的错位。
- **关键里程碑**：是

### 步骤 2：重构序号模式第二阶段 triggerSource 聚合输入
- **操作对象**：`webapp/src/components/graphViewer/canvas.tsx`
- **具体动作**：基于第一阶段后的可见连接图生成 `triggerSource aggregate`，允许其连接到 `core aggregate` 或真实 `core`。
- **预期结果**：序号模式恢复 `triggerSource` 聚合块，且不会把边数重新打爆。
- **关键里程碑**：是

### 步骤 3：对齐详情与搜索高亮
- **操作对象**：`webapp/src/components/GraphViewer.tsx`
- **具体动作**：同步聚合块详情、统计与搜索命中口径，确保新恢复的 `triggerSource aggregate` 和视觉展开态在右侧面板中一致。
- **预期结果**：主画布、详情面板、图例说明一致。
- **关键里程碑**：否

### 步骤 4：验证与文档化
- **操作对象**：构建测试命令与 `docs_dev/reviews/`
- **具体动作**：执行 `npm.cmd run typecheck`、`.\gradlew.bat buildWebapp --no-daemon`、`.\gradlew.bat test testIntegration testClient testSlow --no-daemon`，完成后写审查文档。
- **预期结果**：修复结果有完整验证与留痕。
- **关键里程碑**：是

## 预期结果

频道模式聚合块的展开文案会与实际画布一致，序号模式会重新出现 `triggerSource` 聚合块，同时仍保持真实保存语义只处理 `triggerSource -> core`，不把聚合结构写入 snapshot、draft 或服务端真值。
