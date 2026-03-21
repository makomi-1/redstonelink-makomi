# place fill confirm 统一修复

生成时间：2026-03-21 14:33:28
文件名：2026-03-21_143328_place_fill_confirm统一.md

## 任务背景
用户反馈 `place fill` 的 `confirm` 位置与其它命令不一致，且存在 `place confirm` 拦截体验问题。目标是将 `fill` 路径统一为“原命令末尾追加 `confirm`”，同时保持“仅非空气替换才需要确认拦截”。

## 方案详情

### 现状分析
- 现有 `place fill` 在检测到非空气替换时会缓存 pending，再要求执行独立命令 `/redstonelink place confirm`。
- 该行为与 `link set`、`retire batch`、`crosschunk whitelist set` 的“原命令尾部 `confirm`”不一致。

### 技术方案
- 仅调整 `place fill` 路径：
1. 在 `place fill ... <block>` 分支追加 `confirm` 子命令执行入口。
2. `fill` 执行逻辑新增 `confirmed` 参数；当 `replaceCount > 0` 且非 `force` 且未 `confirmed` 时拦截并提示重试。
3. `fill` 不再写入 pending 再走独立确认；直接按后缀确认执行。
- `place setblock` 与现有 `/redstonelink place confirm` 路径保持不变（按用户范围最小改动）。

### 影响范围
- `src/main/java/com/makomi/command/ModCommands.java`
- `src/main/resources/assets/redstonelink/lang/zh_cn.json`
- `src/main/resources/assets/redstonelink/lang/en_us.json`
- `docs_dev/使用说明.md`

## 原子步骤清单

### 步骤 1：扩展 fill 命令分支
- **操作对象**：`ModCommands`
- **具体动作**：在 `place fill` 的 `block` 分支追加 `confirm` 执行入口。
- **预期结果**：支持 `... fill <from> <to> <block> confirm`。
- **关键里程碑**：是

### 步骤 2：收敛 fill 确认拦截
- **操作对象**：`ModCommands`
- **具体动作**：为 `executePlaceFillInternal` 增加 `confirmed` 分支，仅在非空气替换且未 `force` 且未 `confirmed` 时拦截。
- **预期结果**：确认语义改为“原命令尾部确认”，并保持仅替换时拦截。
- **关键里程碑**：是

### 步骤 3：更新提示文案与说明
- **操作对象**：`zh_cn.json`、`en_us.json`、`使用说明.md`
- **具体动作**：新增/调整 fill 确认提示，文档补充命令用法。
- **预期结果**：用户提示与实际命令语义一致。
- **关键里程碑**：否

### 步骤 4：回归验证
- **操作对象**：Gradle 测试任务
- **具体动作**：执行全标签测试 `.\gradlew.bat test testIntegration testClient testSlow testApiLegacy --no-daemon`。
- **预期结果**：验证改动无回归。
- **关键里程碑**：是

## 预期结果
- `place fill` 改为与其它命令一致的尾部 `confirm` 语义。
- 非空气替换仍保持确认拦截，未替换场景不受影响。
- `place confirm` 误用导致的 fill 体验问题不再出现。
