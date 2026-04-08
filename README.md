![title.png](/docs_dev/background_icon.png)

[![CurseForge](https://img.shields.io/badge/CurseForge-Not%20Published-f16436?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods) [![Modrinth](https://img.shields.io/badge/Modrinth-Not%20Published-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/) [![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg?logo=gnu)](LICENSE)

中文  |  [English](docs/readme_en.md)

# 声明
- 该项目`使用AI`进行加速实现，并通过审查与测试以保证质量
- 目前仅支持`fabric-1.21.1`

# 简介

一个使用了`时间优先 + 同一时间固定优先级`仲裁模型，以及`序号配对`实现连接的无线红石类模组，支持`跨区块`、`中继延迟`
和`权限管理`等，提供连接（接收）核心的`透明`变种和`快速连接工具`等。

## 特点
- 理论上几乎`没有距离限制`
- 支持例如`1:1000`的结构化批量序号输入
- 支持配置`无延迟`
- 状态面板工具`跟踪节点状态`
- 同步类触发器`传输具体强度`

## 玩法
物品分为两类，`触发器`和`连接（接收）核心`：
- 触发器类：`切换/脉冲/同步` 方块和遥控器，
  `脉冲/切换` 按钮；`同步` 拉杆
- 接收核心类：接收核心块、接收核心粉末
交互方式为：
- 蹲下右键打开配对UI（右手为空），或使用快速连接工具直接或手动输入`采集`节点序号和`应用`设置连接
- 通过`红石信号输入`或`命令`激活触发器，从而`传输红石信号`到其所连接的接收核心

## 文档
- [全物品图鉴](https://makomi-1.github.io/redstonelink-makomi/item_guide.html)
- [配方](https://makomi-1.github.io/redstonelink-makomi/recipe_sheet.html)
- [详细使用说明](docs/使用说明.md)

## 冲突
潜在冲突风险的为修改原版`区块加载机制`和`物品交互`的模组
- [冲突模组名单](docs_dev/冲突模组名单.md)（待补充）
- [反馈链接](https://www.wjx.top/vm/wTsJKow.aspx)

# 未来计划
- [ ] 添加发送/接收过滤器
- [ ] 状态面板支持录制回放
- [ ] 支持自定义节点别名
- [ ] 扩展频道匹配
- [ ] UI美化
- [ ] 迁移到26.1+版本
- [ ] 可视化网络分析器
