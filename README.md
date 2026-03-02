# RedstoneLink

## 项目概览
RedstoneLink 是一个基于 Minecraft Fabric 1.21.1 的红石联动模组。  
它通过“连接核心（Core）+ 触发源（按钮/拉杆）”的配对机制，把分散的红石触发行为组织成可管理、可追踪、可审计的联动网络。

- 当前版本：`1.0.0alpha`
- 运行环境：Minecraft `1.21.1`、Fabric Loader `0.18.4`
- 构建工具：Gradle + Fabric Loom
- 许可证：GPL-3.0

## 功能特性

### 1) 节点体系
- 连接核心（红石块）
- 连接核心（红石粉，支持六面附着）
- 连接核心透明变种（红石块 / 红石粉）
- 触发源：连接石按钮（切换）、连接木按钮（脉冲）、连接拉杆（切换）

### 2) 配对与联动
- 支持核心与触发源多对多配对
- 支持“已放置方块”与“物品”两种入口打开配对界面
- 支持多目标输入（批量设置链接）

### 3) 数据持久化与审计
- 世界级台账持久化（已分配、活跃、退役）
- 掉落物携带序号与链接快照
- 支持退役命令与审计命令

### 4) 视觉与资源本地化
- 核心/触发类贴图本地化（`redstonelink` 命名空间）
- 核心红石粉放置态支持激活亮态本地切换
- 核心红石粉激活粒子改为蓝色（替代原版红色）

## 快速开始

### 开发构建
```bash
./gradlew build
```

### 仅处理资源（调试模型/贴图时常用）
```bash
./gradlew processResources
```

### 运行客户端（开发环境）
```bash
./gradlew runClient
```

## 安装与使用

### 安装步骤
1. 安装匹配版本的 Minecraft + Fabric Loader。
2. 将构建产物放入 `mods` 目录。
3. 启动游戏后在创造物品栏中使用 `redstonelink` 分类或搜索物品名称。

### 基础玩法流程
1. 合成“红石连接原件”。
2. 合成核心与触发源。
3. 放置核心与触发源。
4. 通过潜行+空手交互打开配对界面。
5. 写入目标序号后触发联动。

## 命令清单（核心）
- `redstonelink pair main|off <serial>`：兼容单目标配对入口。
- `redstonelink pair_node button <source_serial> <target_serial>`：按钮到核心配对。
- `redstonelink set_links button|core <source_serial> [targets]`：多目标替换。
- `redstonelink retire core|button <serial> confirm`：手动退役。
- `redstonelink audit`：查看活跃/退役/链接审计信息。

## 当前状态与测试
- 状态：持续迭代中（`alpha` 阶段）。
- 验证：已执行基础构建验证与关键功能回归验证。
- 已知范围：重点覆盖核心链路；完整长时稳定性与极端场景仍在持续完善。

## 重要声明
- 本项目包含 AI 辅助生成代码。
- 当前尚未完成全量人工代码审查。
- 使用本项目可能产生兼容性或稳定性风险，风险由使用者自行评估与承担。

## 目录结构（核心）
- `src/main/java/com/makomi`：主逻辑、方块/方块实体、命令、数据
- `src/client/java/com/makomi`：客户端渲染与界面逻辑
- `src/main/resources/assets/redstonelink`：模型、贴图、语言与块状态
- `src/main/resources/data/redstonelink`：配方与数据资源
- `agents/plans`：阶段设计文档

## 许可证
本项目采用 GPL-3.0 协议，详见 [LICENSE](./LICENSE)。
