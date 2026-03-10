# Spark 使用文档（基线观测）

> 适用版本：Minecraft 1.21.1 + Fabric  
> 用途：作为兼容性矩阵中的性能观测基线，输出 TPS/MSPT 与热点证据。

## 1. 安装与前置

- 将 `spark` 模组放入服务端 `mods/`。
- 保证执行者拥有 OP 权限或对应命令权限。
- 建议在回归开始前记录环境信息（模组列表、版本号、测试地图）。

## 2. 基线命令（每轮回归至少一次）

```mcfunction
/spark tps
/spark health --upload
```

判定建议：

- 记录 `TPS`、`MSPT`、上传链接。
- 同一测试轮次使用同一地图和同一触发脚本，确保可比性。

## 3. 常规热点采样（推荐）

```mcfunction
/spark profiler start --timeout 120
```

- 在 120 秒内执行你的回归操作（如 E0~E7 场景）。
- 结束后保存 spark 返回的 viewer 链接作为证据。

## 4. 卡顿尖峰定位（按需）

```mcfunction
/spark tickmonitor --threshold-tick 50
/spark profiler start --only-ticks-over 150 --timeout 120
```

用途：

- 先确认是否出现慢 tick 尖峰，再定向采样慢 tick。

## 5. 证据归档模板

建议每轮测试记录：

1. 用例 ID（如 E1/E5）。
2. 服务端模组栈与客户端模组栈。
3. `/spark tps` 结果快照。
4. `profiler` viewer 链接。
5. 是否通过与异常说明。

## 6. 注意事项

- Spark 作为观测工具，不参与功能兼容阻断结论。
- 单机（集成服）可用于快速采样；版本冻结前应补独立服采样证据。
- 对比数据时保持触发频率、地图状态、玩家数量一致。

