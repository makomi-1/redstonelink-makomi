# 运行时配方缺失先查 RecipeManager 解析错误与 datagen 漂移

## 阻塞点

表面现象是 JEI 看不到某个物品配方，但资源目录里对应的 recipe JSON 明明存在，容易误判成 JEI 兼容问题或客户端缓存问题。

## 解决方法

1. 先查 `run/logs/latest.log` 或 `debug.log`，搜索 `Parsing error loading recipe`、`Couldn't parse data file`。
2. 如果日志里已经报出具体 recipe id，优先判断是不是 Minecraft 根本没成功加载该配方，而不是 JEI 漏显示。
3. 再对照项目里其他已正常生效的 recipe JSON，检查 ingredient 写法是否一致，尤其是对象式 `{"item": "..."}` 与手写简写格式的差异。
4. 如果项目同时维护 datagen 配方源和运行时资源文件，必须继续核对两边是否漂移；只修运行时 JSON 容易在下次 datagen 后再次被覆盖回错误状态。

## 结果

这次问题最终不是 JEI 隐藏配方，而是两个过滤器 recipe 在运行时解析失败；同时 datagen 源与运行时资源已漂移。修复时同步改运行时 JSON 和 datagen 源，问题才算真正闭环。
