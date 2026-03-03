# RedstoneLink API 文档（中文）

## 概览
RedstoneLink 对外提供 `v1` API，入口类为：

- `com.makomi.api.v1.RedstoneLinkApi`
- API 版本常量：`RedstoneLinkApi.API_VERSION`

核心能力分为四类：

1. `graph()`：链路图读写（配对、改链、退役）
2. `trigger()`：触发投递（按源序号触发目标）
3. `query()`：只读查询（审计与序号状态）
4. `adapters()`：第三方触发器/目标适配器注册

## 入口示例

```java
import com.makomi.api.v1.RedstoneLinkApi;

int apiVersion = RedstoneLinkApi.API_VERSION;
var graphApi = RedstoneLinkApi.graph();
var triggerApi = RedstoneLinkApi.trigger();
var queryApi = RedstoneLinkApi.query();
var adapterApi = RedstoneLinkApi.adapters();
```

## 数据模型

常用模型位于 `com.makomi.api.v1.model`：

- `ApiNodeType`：`CORE` / `BUTTON`
- `ApiActivationMode`：`TOGGLE` / `PULSE`
- `ActorContext`：操作发起方上下文（建议传玩家/系统标识）
- `TriggerRequest` / `TriggerResult`
- `LinkMutationResult`
- `NodeSnapshot` / `NodeRetireResult`
- `AuditSnapshot`
- `ApiDecision`（前置事件允许/拒绝）

## 1) LinkGraphApi（链路读写）

接口：`com.makomi.api.v1.service.LinkGraphApi`

主要方法：

- `findNode(level, nodeType, serial)`：查节点快照
- `getLinkedTargets(level, sourceType, sourceSerial)`：查当前目标集合
- `setLinks(...)`：替换源节点目标集合
- `toggleLink(...)`：切换单个链接
- `clearLinks(...)`：清空链接
- `retireNode(...)`：退役节点并清理链接

### 示例：批量替换链接

```java
import com.makomi.api.v1.RedstoneLinkApi;
import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiNodeType;
import java.util.Set;

var result = RedstoneLinkApi.graph().setLinks(
    serverLevel,
    ApiNodeType.BUTTON,
    1001L,
    Set.of(2001L, 2002L, 2003L),
    ActorContext.system("my_mod")
);

if (!result.success()) {
    // result.reasonKey() 可用于日志或本地化提示
}
```

## 2) TriggerApi（触发投递）

接口：`com.makomi.api.v1.service.TriggerApi`

主要方法：

- `emit(request)`：按源序号触发
- `emitFromExternal(...)`：从外部方块位置解析源并触发

### 示例：按源序号触发脉冲

```java
import com.makomi.api.v1.RedstoneLinkApi;
import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiActivationMode;
import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.TriggerRequest;

var request = new TriggerRequest(
    serverLevel,
    ApiNodeType.BUTTON,
    1001L,
    ApiActivationMode.PULSE,
    ActorContext.system("timer_mod")
);

var triggerResult = RedstoneLinkApi.trigger().emit(request);
```

## 3) QueryApi（只读查询）

接口：`com.makomi.api.v1.service.QueryApi`

主要方法：

- `getAuditSnapshot(level)`：链路审计概览
- `getActiveSerials(level, nodeType)`：活跃序号
- `getRetiredSerials(level, nodeType)`：退役序号

### 示例：读取审计快照

```java
var snapshot = RedstoneLinkApi.query().getAuditSnapshot(serverLevel);
int totalLinks = snapshot.totalLinks();
int brokenLinks = snapshot.linksWithMissingEndpoint();
```

## 4) AdapterRegistryApi（外部适配器）

接口：`com.makomi.api.v1.adapter.AdapterRegistryApi`

### 4.1 ExternalTargetAdapter（外部目标）

用途：让第三方方块作为“被触发目标”。

关键接口：

- `supports(...)`：判断是否由该适配器处理
- `trigger(...)`：执行触发逻辑

### 4.2 ExternalTriggerAdapter（外部触发器）

用途：从第三方方块解析 RedstoneLink 源序号并发起触发。

关键接口：

- `supports(...)`
- `resolveSourceSerial(...)`
- `sourceType()`（默认 `BUTTON`）

### 示例：注册目标适配器

```java
import com.makomi.api.v1.RedstoneLinkApi;
import com.makomi.api.v1.adapter.ExternalTargetAdapter;
import net.minecraft.resources.ResourceLocation;

RedstoneLinkApi.adapters().registerTargetAdapter(
    ResourceLocation.fromNamespaceAndPath("mymod", "energy_door_target"),
    new ExternalTargetAdapter() {
        @Override
        public boolean supports(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be) {
            return be instanceof MyEnergyDoorBlockEntity;
        }

        @Override
        public boolean trigger(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            BlockEntity be,
            long sourceSerial,
            ApiNodeType sourceType,
            ApiActivationMode mode
        ) {
            return ((MyEnergyDoorBlockEntity) be).toggleDoor();
        }
    }
);
```

## 5) 事件（LinkEvents）

事件类：`com.makomi.api.v1.event.LinkEvents`

- `BEFORE_SET_LINKS`：改链前（可拒绝）
- `AFTER_SET_LINKS`：改链后
- `BEFORE_TRIGGER`：触发前（可拒绝）
- `AFTER_TRIGGER`：触发后
- `NODE_RETIRED`：退役后

### 示例：拦截跨维度改链（示意）

```java
import com.makomi.api.v1.event.LinkEvents;
import com.makomi.api.v1.model.ApiDecision;

LinkEvents.BEFORE_SET_LINKS.register((level, sourceType, sourceSerial, targets, actor) -> {
    boolean allowed = myPermissionCheck(level, actor, sourceSerial, targets);
    return allowed ? ApiDecision.allow() : ApiDecision.deny("mymod.link.denied");
});
```

## 行为约束与建议

- 写操作必须在服务端调用（客户端仅用于展示，不应直接改链）。
- `setLinks` 受服务端配置限制：
  - `server.maxTargetsPerSetLinks`
  - `server.allowOfflineTargetBinding`
- 触发时默认只处理已加载目标；未加载/无效目标会计入 `TriggerResult` 跳过统计。
- 建议第三方 mod 保留 `reasonKey`，用于日志与本地化错误提示。

## 相关源码

- API 入口：`src/main/java/com/makomi/api/v1/RedstoneLinkApi.java`
- API 实现：`src/main/java/com/makomi/api/v1/internal/RedstoneLinkApiImpl.java`
- 事件：`src/main/java/com/makomi/api/v1/event/LinkEvents.java`
- 适配器：`src/main/java/com/makomi/api/v1/adapter/*`
- 模型：`src/main/java/com/makomi/api/v1/model/*`

