# ItemStack测试需先Bootstrap初始化

## 阻塞点
- 新增 `ChunkActivatorItemDataTest` 后，测试在 `new ItemStack(Items.STONE)` 时直接触发 `BuiltInRegistries` 初始化，并抛出 `Not bootstrapped`。
- 由于该异常会让相关静态类进入失败态，后续同轮测试里的其他物品/NBT 测试也会连带报 `NoClassDefFoundError`，表面上像是多处一起坏掉。

## 处理方法
- 在测试类的 `@BeforeAll` 中先执行：

```java
SharedConstants.tryDetectVersion();
Bootstrap.bootStrap();
```

- 不要依赖“别的测试类可能已经帮忙引导过注册表”；只要当前测试会直接使用 `ItemStack`、`Items` 或其他依赖内建注册表的 Minecraft 类型，就在本测试类里显式做 bootstrap。

## 复用建议
- 以后新增纯数据契约测试但要构造 Minecraft 物品/方块对象时，先复制这段引导模板，再写断言。
- 如果出现 `Could not initialize class BuiltInRegistries` 或 `Not bootstrapped (game_event)`，优先先查是否缺了这段初始化，而不是先怀疑业务代码。
