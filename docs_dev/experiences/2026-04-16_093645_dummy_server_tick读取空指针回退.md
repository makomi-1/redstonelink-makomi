# dummy server tick读取空指针回退

## 阻塞点
- 新增频道收口单测时，`ChannelDispatchScheduler.flushPendingForTesting(...)` 和后续 `CoreDispatchBatchScheduler.enqueueLoadedTargetDispatchBatch(...)` 都会在 `server.overworld()` 上触发空指针，因为测试使用的是未完整初始化的 dummy `MinecraftServer`。

## 解决方法
- 不改测试去伪造完整 `levels` 容器，而是在两个调度器的 `resolveCurrentTick(...)` 内补“未完整初始化 server 时回退”的保护。
- 频道层回退到 `0 tick`；core 批层优先回退到目标实体 `level.getGameTime()`，保持正式服路径不变，只让最小单测桩更稳。

## 适用提示
- 以后凡是调度器/运行时服务在单测里要接收 dummy `MinecraftServer`，都应先检查是否直接调用了 `server.overworld()`、`server.getLevel(...)` 这类依赖完整 world map 的入口。
- 如果只是为了取当前 tick，优先提供安全回退而不是在测试里补造整套 server 世界结构。
