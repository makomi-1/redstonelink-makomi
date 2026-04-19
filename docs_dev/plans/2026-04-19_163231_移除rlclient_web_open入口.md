# 移除rlclient_web_open入口

生成时间：2026-04-19 16:32:31
文件名：2026-04-19_163231_移除rlclient_web_open入口.md

## 任务背景

当前 `/rlclient web open` 打开的本地网页首页已没有实际业务作用，但 graph 页面与 recording 页面仍继续依赖本地网页桥和独立入口。用户要求按最小范围移除这个无效首页入口。

## 方案详情

### 现状分析

- `/rlclient web open` 在客户端命令树中单独注册，执行后仅调用首页打开逻辑。
- `/rlclient web graph`、录制页按钮、graph/recording 资产自动打开仍依赖本地网页桥。
- 因此不能删除本地网页桥主体，只应删除无效首页命令入口。

### 技术方案

- 删除 `/rlclient web open` 命令分支。
- 删除对应的 `executeOpenWebApp(...)` 处理函数。
- 同步修正文档注释，避免继续描述已移除入口。
- 保留 graph/recording 相关命令、页面打开逻辑和反馈文案，确保兼容现有使用路径。

### 影响范围

- `src/client/java/com/makomi/RedstoneLinkClient.java`

## 原子步骤清单

### 步骤 1：移除命令入口
- **操作对象**：`RedstoneLinkClient.registerClientCommands`
- **具体动作**：删除 `/rlclient web open` 注册分支，并保留 `/rlclient web graph` 相关分支
- **预期结果**：客户端不再暴露无实际作用的首页命令
- **关键里程碑**：是

### 步骤 2：删除无效处理函数
- **操作对象**：`RedstoneLinkClient.executeOpenWebApp`
- **具体动作**：移除只服务于首页入口的处理函数
- **预期结果**：客户端代码与命令树保持一致，不再保留无用入口实现
- **关键里程碑**：是

### 步骤 3：回归验证
- **操作对象**：Gradle 自动化测试
- **具体动作**：运行 `.\gradlew.bat test testIntegration testClient testSlow --no-daemon`
- **预期结果**：确认移除首页命令后无回归
- **关键里程碑**：是

## 预期结果

用户仍可通过 graph/recording 相关入口打开有效网页页面，但 `/rlclient web open` 不再出现，减少无用命令和歧义。
