# 替代发布改名 redstonelink-makomi

生成时间：2026-03-16 19:00:00
文件名：2026-03-16_190000_替代发布改名redstonelink-makomi.md

## 任务背景
`redstonelink` 名称已被使用。当前目标是“替代发布”，不考虑与另一个同类模组并存，优先保证旧世界、旧命令和旧配置兼容。

## 方案详情

### 现状分析
- 当前运行时 `mod id` 为 `redstonelink`，命令根为 `/redstonelink`，配置文件为 `redstonelink-server.properties`。
- 资源命名空间与存档键均以 `redstonelink` 为前缀，若整体替换为新前缀会触发兼容风险。

### 技术方案
- 仅修改发布侧标识，保留运行时标识不变。
- 修改 `gradle.properties`：
  - `archives_base_name` 改为 `redstonelink-makomi`
  - `mod_name` 改为 `RedstoneLink Makomi`
- 保持以下内容不变：
  - `fabric.mod.json` 的 `id=redstonelink`
  - `RedstoneLink.MOD_ID=redstonelink`
  - `/redstonelink` 命令入口与确认提示
  - `redstonelink-server.properties` 配置文件名
  - `assets/data` 资源命名空间与 SavedData 键

### 影响范围
- 构建产物命名与展示名变化。
- 运行时注册名、资源命名空间、命令与存档兼容行为保持不变。

## 原子步骤清单

### 步骤 1：方案文档落盘
- **操作对象**：`docs_dev/plans/`
- **具体动作**：保存本次确认方案文档。
- **预期结果**：实现前有可追溯方案记录。
- **关键里程碑**：是

### 步骤 2：修改发布标识
- **操作对象**：`gradle.properties`
- **具体动作**：更新 `archives_base_name` 与 `mod_name`。
- **预期结果**：构建产物文件名与模组展示名体现 `redstonelink-makomi`。
- **关键里程碑**：是

### 步骤 3：验证关键兼容点未改动
- **操作对象**：`fabric.mod.json`、`RedstoneLink.java`、命令/配置入口
- **具体动作**：检索并确认 `mod id`、命令根与配置文件名仍为 `redstonelink`。
- **预期结果**：替代发布但不破坏既有世界兼容。
- **关键里程碑**：否

### 步骤 4：执行回归验证
- **操作对象**：Gradle 测试任务
- **具体动作**：执行 `.\gradlew.bat test testIntegration testClient testSlow testApiLegacy --no-daemon`。
- **预期结果**：验证改名未引入行为回归。
- **关键里程碑**：是

## 预期结果
发布产物与展示名称切换为 `redstonelink-makomi`，运行时兼容标识保持 `redstonelink`，可作为替代版本发布并尽量保持现有世界与使用习惯不变。
