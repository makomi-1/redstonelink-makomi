# 移除rlclient_web_open入口

## 1. 功能与语义、架构设计（关键方法和入口）

- 本次改动只收敛在客户端命令注册层，删除无实际作用的 `/rlclient web open` 首页入口，保留 `/rlclient web graph` 及其页面打开逻辑不变，符合“最小粒度实现用户要求”。关键入口位于 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L282)。
- 注释同步改为只描述仍有效的 graph 相关入口，避免命令语义与实现漂移。对应位置见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L282)。
- `executeOpenGraphPage`、graph 导出命令和生命周期关闭网页桥逻辑仍保留，说明这次不是删除本地网页桥主体，只是去掉无意义首页入口。对应位置见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L335) 和 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L356)。

## 2. 关键数据结构和算法性能分析

- 本次没有引入新数据结构，也没有修改任何运行时图、节点、缓存或桥接资产结构；只是少注册一个客户端命令分支，因此运行时复杂度只会略微下降。命令树变化集中在 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L288)。
- 删除首页处理函数后，少了一次 `openHomePage()` 分支跳转与异常处理路径，但不影响 graph 页面路径的时间复杂度和资源占用。仍保留的 graph 打开逻辑位于 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L359)。

## 3. 数据流或调用链

- 变更前：`/rlclient web open` -> `executeOpenWebApp` -> 本地网页桥首页。
- 变更后：`/rlclient web` 下仅保留 `graph` 分支，调用链为 `/rlclient web graph open` -> `executeOpenGraphPage` -> 本地网页桥 graph 页面；`/rlclient web graph/export` -> `executeExportGraphSnapshot` -> 服务端导出图快照请求。入口见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L310)；graph 页面打开见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L359)；graph 导出见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L377)。
- 生命周期钩子仍在客户端退出时关闭本地网页桥，说明网页桥生命周期不受本次命令删减影响。对应位置见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L335)。

## 4. 安全、性能、兼容性、扩展性风险分析与建议

- 安全风险低：没有放宽任何权限，也没有新增网络或文件访问能力；只是减少一个客户端本地命令入口。命令树影响范围见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L288)。
- 兼容性风险低：graph 页面、recording 页面以及本地网页桥本体仍在，因此既有有效功能入口不受影响。保留逻辑见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L335) 和 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L356)。
- 残余风险低：语言文件里的 `message.redstonelink.web.opened/open_failed` 仍保留，因为 graph 和 recording 仍复用这些反馈文案；这是有意保留，不构成死逻辑风险。

## 5. 并发冲突审查（共享状态、读改写覆盖、重入/线程边界、风险等级与建议）

- 本次只改客户端命令注册与命令处理函数，没有改共享状态结构、没有新增异步任务，也没有改动本地网页桥的线程模型，线程边界风险低。命令注册和生命周期钩子见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L288) 和 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L335)。
- 唯一相关共享组件仍是本地网页桥 runtime，但本次没有改其启动、停止、资源读取或 HTTP 处理路径，因此不存在新的读改写覆盖风险。当前仍经由 graph 页面打开路径使用，见 [RedstoneLinkClient.java](/d:/OpenProjects/RedstoneLink/rl-release-no-mixin/src/client/java/com/makomi/RedstoneLinkClient.java#L359)。
