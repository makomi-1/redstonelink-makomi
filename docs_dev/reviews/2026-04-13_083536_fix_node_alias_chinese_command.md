# 修复节点别名命令中文输入审查

生成时间：2026-04-13 08:35:36
文件名：2026-04-13_083536_fix_node_alias_chinese_command.md

## 1. 功能与语义、架构设计（关键方法和入口）
- 本轮修复只作用于别名命令的参数解析层，把 `node alias set/resolve` 的末尾 `alias` 参数从 `word()` 调整为 `greedyString()`，让中文别名能够进入后续业务校验层，而不改变“别名仅支持读、不参与写链路”的既有语义；见 [NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L36)、[NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L44)、[NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L58)。
- `executeSet` 与 `executeResolve` 的业务逻辑未改，仍继续复用 `NodeAliasSavedData.validateAlias(...)` 做规则校验，因此中文支持来自“命令层放行 + 原校验层继续兜底”的组合，而不是放宽规则本身；见 [NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L68)、[NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L164)。

## 2. 关键数据结构和算法性能分析
- 本次没有新增持久化结构，也没有改变 alias 索引结构、命令执行复杂度或网络负载，仅是 Brigadier 末尾参数的解析方式调整，性能影响可忽略。
- `greedyString()` 只放在命令末尾参数上使用，没有引入“后续 literal 被吞掉”的路径歧义；它本质上是把更多原始输入交给既有校验层处理，而不是在解析层做额外扫描或拆分。

## 3. 数据流或调用链
- 用户输入 `node alias set triggerSource 12 大门1` 后，Brigadier 现在会把 `大门1` 原样交给 `executeSet(...)`，再进入别名校验与持久化逻辑；关键入口见 [NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L44)、[NodeAliasCommandRegistry.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/main/java/com/makomi/command/node/NodeAliasCommandRegistry.java#L68)。
- 回归测试新增了一条解析契约，用最小命令树直接验证“中文别名作为末尾参数能被 Brigadier 接收”，避免后续再误回退到 `word()`；见 [CommandEntryContractTest.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/test/java/com/makomi/command/CommandEntryContractTest.java#L437)、[CommandEntryContractTest.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/test/java/com/makomi/command/CommandEntryContractTest.java#L454)、[CommandEntryContractTest.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/test/java/com/makomi/command/CommandEntryContractTest.java#L465)。

## 4. 安全、性能、兼容性、扩展性风险分析与建议
- 由于 `greedyString()` 会吃掉末尾整段文本，它只适合放在命令的最后一个参数。本次 `set/resolve` 都满足该条件，因此兼容风险低；但后续如果给 `alias set` 再追加尾缀 literal，就必须重新评估命令树形态。
- 当前别名规则层仍然会拒绝空白、纯数字和特殊符号，因此这次修复不会把“中文可输入”扩展成“任意字符都可输入”。如果以后想支持空格别名，需要同步调整校验规则，而不只是依赖 `greedyString()`。
- 现有测试覆盖了“中文末尾参数可解析”，但没有新增真实 `CommandSourceStack` 集成测试；若后续命令树继续扩展，建议补一条更贴近真实注册入口的命令集成测试。

## 5. 并发冲突审查（共享状态、读改写覆盖、重入/线程边界、风险等级与建议）
- 本轮改动只涉及命令参数解析和测试契约，没有新增共享状态，也没有修改任何服务端持久化读改写时序。
- `executeSet/executeResolve` 仍在原有主线程命令执行边界内运行，本次不改变线程边界、重入条件或状态同步路径。
- 并发风险等级评估为“低”；建议继续保持“命令层仅负责解析，状态修改仍集中在既有服务端主线程逻辑”的边界。
