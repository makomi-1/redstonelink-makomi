# RedstoneLink API Documentation (English)

## Overview
RedstoneLink exposes a `v1` public API with the entry class:

- `com.makomi.api.v1.RedstoneLinkApi`
- Version constant: `RedstoneLinkApi.API_VERSION`

API capabilities are grouped into four areas:

1. `graph()` - link graph read/write (pairing, relinking, retire)
2. `trigger()` - trigger dispatch (emit to linked targets)
3. `query()` - read-only inspection (audit and serial status)
4. `adapters()` - third-party trigger/target adapter registry

## Entry Example

```java
import com.makomi.api.v1.RedstoneLinkApi;

int apiVersion = RedstoneLinkApi.API_VERSION;
var graphApi = RedstoneLinkApi.graph();
var triggerApi = RedstoneLinkApi.trigger();
var queryApi = RedstoneLinkApi.query();
var adapterApi = RedstoneLinkApi.adapters();
```

## Data Models

Common models are under `com.makomi.api.v1.model`:

- `ApiNodeType`: `CORE` / `BUTTON`
- `ApiActivationMode`: `TOGGLE` / `PULSE`
- `ActorContext`: caller context (player/system identity)
- `TriggerRequest` / `TriggerResult`
- `LinkMutationResult`
- `NodeSnapshot` / `NodeRetireResult`
- `AuditSnapshot`
- `ApiDecision` (allow/deny for pre-events)

## 1) LinkGraphApi

Interface: `com.makomi.api.v1.service.LinkGraphApi`

Main methods:

- `findNode(level, nodeType, serial)`
- `getLinkedTargets(level, sourceType, sourceSerial)`
- `setLinks(...)` (replace target set)
- `toggleLink(...)` (add/remove one link)
- `clearLinks(...)`
- `retireNode(...)`

### Example: Replace targets in batch

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
    // result.reasonKey() can be used for logging/i18n
}
```

## 2) TriggerApi

Interface: `com.makomi.api.v1.service.TriggerApi`

Main methods:

- `emit(request)` - trigger by source serial
- `emitFromExternal(...)` - resolve source from external block and trigger

### Example: Emit a pulse

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

## 3) QueryApi

Interface: `com.makomi.api.v1.service.QueryApi`

Main methods:

- `getAuditSnapshot(level)`
- `getActiveSerials(level, nodeType)`
- `getRetiredSerials(level, nodeType)`

### Example: Read audit metrics

```java
var snapshot = RedstoneLinkApi.query().getAuditSnapshot(serverLevel);
int totalLinks = snapshot.totalLinks();
int brokenLinks = snapshot.linksWithMissingEndpoint();
```

## 4) AdapterRegistryApi

Interface: `com.makomi.api.v1.adapter.AdapterRegistryApi`

### 4.1 ExternalTargetAdapter

Use case: make third-party blocks act as RedstoneLink trigger targets.

Core methods:

- `supports(...)`
- `trigger(...)`

### 4.2 ExternalTriggerAdapter

Use case: resolve a RedstoneLink source serial from third-party trigger blocks.

Core methods:

- `supports(...)`
- `resolveSourceSerial(...)`
- `sourceType()` (default is `BUTTON`)

### Example: Register a target adapter

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

## 5) Events (LinkEvents)

Event class: `com.makomi.api.v1.event.LinkEvents`

- `BEFORE_SET_LINKS` (cancelable)
- `AFTER_SET_LINKS`
- `BEFORE_TRIGGER` (cancelable)
- `AFTER_TRIGGER`
- `NODE_RETIRED`

### Example: deny link changes by policy

```java
import com.makomi.api.v1.event.LinkEvents;
import com.makomi.api.v1.model.ApiDecision;

LinkEvents.BEFORE_SET_LINKS.register((level, sourceType, sourceSerial, targets, actor) -> {
    boolean allowed = myPermissionCheck(level, actor, sourceSerial, targets);
    return allowed ? ApiDecision.allow() : ApiDecision.deny("mymod.link.denied");
});
```

## Runtime Notes

- Write operations must be called on the server side.
- `setLinks` is constrained by server config:
  - `server.maxTargetsPerSetLinks`
  - `server.allowOfflineTargetBinding`
- Trigger dispatch only applies to currently reachable targets.
- Keep `reasonKey` for logs and localization.

## Source References

- API entry: `src/main/java/com/makomi/api/v1/RedstoneLinkApi.java`
- API impl: `src/main/java/com/makomi/api/v1/internal/RedstoneLinkApiImpl.java`
- Events: `src/main/java/com/makomi/api/v1/event/LinkEvents.java`
- Adapters: `src/main/java/com/makomi/api/v1/adapter/*`
- Models: `src/main/java/com/makomi/api/v1/model/*`

