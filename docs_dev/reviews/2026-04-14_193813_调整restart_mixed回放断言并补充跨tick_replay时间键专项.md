# 调整 restart mixed 回放断言并补充跨 tick replay 时间键专项

## 1. 功能与语义、架构设计

- 旧 `r05` 断言已按最新简化模型收口，语义从“移除 sync 后回落到事件信号”调整为“移除 sync 后直接 `none/0`”。关键语义入口见 [restart_mixed_replay_redstone_verify.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/r05/restart_mixed_replay_redstone_verify.json#L3) 和 [restart_mixed_replay_redstone_verify.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/r05/restart_mixed_replay_redstone_verify.json#L88)。
- 新增跨 tick replay 时间键专项，prepare 先构造“较早 sync、较晚 toggle 把目标翻低”，verify 在重启后确认旧 sync replay 不会重新点亮目标。关键 case 入口见 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L3) 和 [restart_replay_timekey_cross_tick_verify.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/r06/restart_replay_timekey_cross_tick_verify.json#L3)。
- 新 suite 通过显式配置 `syncTargetChunkLoadReplay.enabled=true`、`syncSourceAttachReplay.enabled=false` 把语义聚焦到 target attach replay 的旧时间键口径，避免混入 source attach replay。配置入口见 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L3) 和 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L12)。

## 2. 关键数据结构和算法性能分析

- 本轮改动只涉及 functional matrix、suite 和 case JSON，未修改运行时代码路径，也未引入新的热路径算法；性能成本仅为 bench 回归多增加 1 组 prepare/verify 场景。matrix 注册点见 [functional-matrix.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/functional-matrix.json#L67) 和 [functional-matrix.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/functional-matrix.json#L72)。
- 新 case 结构保持单 target、双 source、固定 fan-in，命令和 trace 规模都很小，适合作为 dedicated 语义回归而不是性能压测。结构定义见 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L10) 和 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L39)。

## 3. 数据流或调用链

- 新专项的数据流为：suite 入口选择 prepare entry，functional matrix 解析到 `x37` case，构造世界并写入“早 sync、晚 toggle”；随后 verify entry 通过 `reuseWorldFrom` 复用同一世界，直接在重启后的目标上做 trace 断言。入口链路见 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L18) 和 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L24)。
- prepare 阶段先写 sync，再等待，再写 toggle，最后用 trace 读取目标结果态；verify 阶段只 mount、wait、assert，不再注入新信号，确保断言落在 replay 恢复结果本身。阶段定义见 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L55) 和 [restart_replay_timekey_cross_tick_verify.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/r06/restart_replay_timekey_cross_tick_verify.json#L45)。

## 4. 安全、性能、兼容性、扩展性风险分析与建议

- 兼容性风险主要在“测试口径误判语义”而不是实现：`toggle` 在 mixed 场景里是“翻转当前目标态”，不是简单置高；本轮新专项已把 later toggle 明确写成 `toggle/0`，避免后续继续按旧直觉写错断言。相关断言见 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L90)。
- 旧 suite 文案已同步到最新模型，避免回归结果和说明文本相互矛盾。更新点见 [restart-mixed-replay.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-mixed-replay.json#L3)。
- 扩展建议：若后续还要覆盖 `source attach replay` 的时间键口径，应新增单独 suite，不要复用本专项的配置；本专项已经显式关闭 `syncSourceAttachReplay.enabled`，否则会把验证焦点从 target replay 稀释掉。配置点见 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L12)。

## 5. 并发冲突审查

- 本轮改动是 declarative bench 配置层改动，没有新增 Java 共享状态、锁、缓存或跨线程读改写逻辑；运行时并发风险等级为低。结构入口见 [restart_replay_timekey_cross_tick_prepare.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/x37/restart_replay_timekey_cross_tick_prepare.json#L1) 和 [restart_replay_timekey_cross_tick_verify.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/cases/functional/r06/restart_replay_timekey_cross_tick_verify.json#L1)。
- 需要关注的唯一“流程并发”点是 verify 依赖 prepare world reuse；若 prepare 失败，verify 会被 suite 直接短路。因此 suite 中的 `reuseWorldFrom/compareSerialsTo` 依赖关系应保持一对一，不宜在同一专项里混入额外 entry。依赖关系见 [restart-replay-timekey-cross-tick.json](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/tools/bench/suites/restart-replay-timekey-cross-tick.json#L22)。
