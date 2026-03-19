# configuredMode 与 effectiveMode 改造

生成时间：2026-03-19 18:02:20
文件名：2026-03-19_180220_configuredMode与effectiveMode改造.md

## 任务背景
- 当前 `node get` 的运行态输出 `mode` 读取的是目标端配置字段 `activationMode`。
- 该字段语义是“默认触发配置”，不是“当前运行态谁生效”，导致 sync/pulse 生效时界面仍显示 toggle，容易误判。

## 方案详情

### 现状分析
- 目标端 `ActivatableTargetBlockEntity` 仅暴露 `getActivationMode()`，未提供运行态生效模式。
- `node get` 运行态文案只输出一个 `mode` 字段，无法区分“配置态”和“生效态”。
- NBT 当前持久化键为 `ActivationMode`，直接改键有旧档兼容风险。

### 技术方案
- 将目标端字段与 API 语义改为 `configuredMode`（配置态默认触发语义）。
- 新增运行态 `effectiveMode` 推导：
  - `syncSignalMaxStrength > 0` -> `SYNC`
  - 否则 `pulse` 窗口有效 -> `PULSE`
  - 否则 `toggleState = true` -> `TOGGLE`
  - 其余 -> `NONE`
- 命令运行态输出扩展为同时展示 `configuredMode/effectiveMode`。
- 持久化采用“读旧写新”：
  - 优先读 `ConfiguredMode`
  - 兼容读 `ActivationMode`
  - 统一写 `ConfiguredMode`

### 影响范围
- `src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java`
- `src/main/java/com/makomi/command/ModCommands.java`
- `src/main/resources/assets/redstonelink/lang/zh_cn.json`
- `src/main/resources/assets/redstonelink/lang/en_us.json`
- `src/test/java/com/makomi/block/entity/ActivatableTargetBlockEntityInternalTest.java`

## 原子步骤清单

### 步骤 1：目标端语义改名与运行态枚举
- **操作对象**：`ActivatableTargetBlockEntity`
- **具体动作**：字段/方法重命名为 `configuredMode`，新增 `EffectiveMode` 与推导方法，更新触发调用点。
- **预期结果**：配置态与生效态语义分离，类内统一新命名。
- **关键里程碑**：是

### 步骤 2：命令输出与文案更新
- **操作对象**：`ModCommands`、`zh_cn.json`、`en_us.json`
- **具体动作**：运行态输出改为 `configuredMode/effectiveMode`。
- **预期结果**：`node get` 可直接区分默认配置与当前生效来源。
- **关键里程碑**：是

### 步骤 3：测试回归
- **操作对象**：`ActivatableTargetBlockEntityInternalTest`
- **具体动作**：更新旧断言并补充/调整 `effectiveMode` 断言，执行相关测试。
- **预期结果**：行为可回归、兼容逻辑有测试覆盖。
- **关键里程碑**：是

## 预期结果
- 运行时观测不再把“配置态默认模式”误解为“当前生效模式”。
- 旧存档可继续读取，新增存档统一新键名。
