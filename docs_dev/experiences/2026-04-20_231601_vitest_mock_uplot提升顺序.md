# Vitest mock uPlot 提升顺序

## 阻塞点

在为 `RecordingChart` 补组件测试时，直接在测试文件顶层定义 `MockUPlot`，再通过 `vi.mock('uplot', () => ({ default: MockUPlot }))` 注入，会因为 `vi.mock` 工厂被提升执行而触发 `ReferenceError: Cannot access 'MockUPlot' before initialization`。

## 解决方法

- 将 `MockUPlot` 和 `mockPlotInstances` 放入 `vi.hoisted(() => { ... })` 中统一创建。
- `vi.mock` 工厂只引用 `vi.hoisted` 返回的对象，不再直接捕获后定义的顶层变量。
- 对 canvas 图表类库做组件测试时，优先 mock 最小可用接口，只保留 `constructor / setScale / destroy / posToVal / hooks.setCursor` 这些当前断言所需能力，避免 mock 过重。

## 可复用结论

以后在 Vitest 中 mock 这类“默认导出类 + 工厂提升”的依赖时，默认优先使用 `vi.hoisted` 包住 mock 类和实例池，避免再次踩到初始化时序问题。
