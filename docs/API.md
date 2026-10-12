# 公共 Java API（API）

本模组对外开放两套 Java API：

1. **纯逻辑层 `com.structurereset.api`** —— 不依赖 Minecraft/Forge，可在普通 JVM 上单测
   （`python tools/run_tests.py` 就是靠这一点离线跑起来的）：数据包解析、定义模型、选择器、冷却、占位符。
2. **核心运行时 `com.structurereset.core`** —— 真正改世界的那一层，需要 Forge/Minecraft classpath：
   `StructureResetApi` / `ItemAppearanceApi` / `ResetContext` / `ResetResult` / `ResetActionType`
   与事件 `com.structurereset.core.event.ResetPreEvent` / `ResetPostEvent`。

---

## 1. 纯逻辑层：`com.structurereset.api`

### 1.1 `ResetDefinition`

一条结构重置定义（不可变）。用 `ResetDefinition.builder()` 构造。

```java
ResetDefinition definition = ResetDefinition.builder()
        .id("mypack:my_dungeon")                   // 必须 "namespace:path"
        .addStructure("minecraft:stronghold")      // 或 #minecraft:village（结构标签）
        .radius(96.0D)                             // 必须 > 0，默认 64
        .trigger(ResetTrigger.EITHER)              // IN_STRUCTURE / ANCHOR_BLOCK / EITHER
        .anchorBlock("minecraft:lodestone")
        .mode(ResetMode.PER_PLAYER)                // GLOBAL / PER_PLAYER，默认 GLOBAL
        .cooldownSeconds(1800)                     // 必须 >= 0，默认 0
        .consumeItem(true)
        .requiredItem("minecraft:echo_shard")      // 可为 null
        .priority(10)
        .addAction(ResetActionSpec.of("structurereset:refill_loot"))
        .build();
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `id()` | `String` | `namespace:path` |
| `structures()` | `List<StructureRef>` | 命中哪些结构 |
| `radius()` | `double` | 作用半径（默认 `DEFAULT_RADIUS` = 64） |
| `trigger()` | `ResetTrigger` | 默认 `EITHER` |
| `anchorBlocks()` | `List<String>` | 锚点方块 id |
| `mode()` | `ResetMode` | 默认 `GLOBAL` |
| `cooldownSeconds()` | `int` | 默认 0 |
| `consumeItem()` | `boolean` | 默认 `true` |
| `requiredItem()` | `String` | 可为 `null` |
| `priority()` | `int` | 默认 0，越大越优先 |
| `actions()` | `List<ResetActionSpec>` | 至少一条 |
| `extra()` | `JsonObject` | 未识别的字段原样保留，可为 `null` |

`build()` 会校验：id 必须是合法 `namespace:path`、至少一个结构、`radius > 0`、
`cooldownSeconds >= 0`、至少一条 action，否则抛 `DefinitionException`。

### 1.2 `DefinitionParser`

```java
ResetDefinition parsed = DefinitionParser.parse("mypack:my_dungeon", jsonObject);
```

字段常量：`FIELD_STRUCTURE`、`FIELD_RADIUS`、`FIELD_TRIGGER`、`FIELD_ANCHOR_BLOCKS`、
`FIELD_MODE`、`FIELD_COOLDOWN_SECONDS`、`FIELD_CONSUME_ITEM`、`FIELD_REQUIRED_ITEM`、
`FIELD_PRIORITY`、`FIELD_ACTIONS`、`FIELD_EXTRA`，集合 `KNOWN_FIELDS`。

解析失败抛 `DefinitionException`，消息形如 `actions[0].type: must be a non-blank string`。

### 1.3 `StructureRef`

```java
StructureRef exact = StructureRef.parse("minecraft:stronghold");  // tag() == false
StructureRef tag   = StructureRef.parse("#minecraft:village");    // tag() == true
exact.matchesId("minecraft:stronghold");                          // true
exact.raw();                                                      // "minecraft:stronghold"
```

### 1.4 `ResetActionSpec`

```java
ResetActionSpec spec = new ResetActionSpec("structurereset:respawn_boss", paramsObject);
ResetActionSpec bare = ResetActionSpec.of("structurereset:refill_loot");
spec.type();      // "structurereset:respawn_boss"
spec.params();    // JsonObject（永不为 null）
spec.hasParams(); // true
```

### 1.5 `DefinitionSelector`

```java
Optional<ResetDefinition> winner = DefinitionSelector.select(
        allDefinitions,               // Collection<ResetDefinition>
        "minecraft:stronghold",       // 命中的结构 id（可空）
        Set.of("minecraft:village"),  // 结构标签（可空）
        true);                        // 是否由锚点方块触发
```

排序规则：完全匹配 > 标签匹配；同档按 `priority` 降序；再同则按 `id` 字典序，保证结果稳定。

### 1.6 `CooldownPolicy`

```java
boolean ready = CooldownPolicy.ready(System.currentTimeMillis(), lastResetMillis, 3600);
long remaining = CooldownPolicy.remainingMillis(now, last, 3600);
```

`cooldownSeconds <= 0` 表示不冷却；`now < last`（时钟回拨）视为仍需等待。

### 1.7 `ResetKey`

冷却状态按 `(维度, 作用域, 定义 id)` 记录：

```java
ResetKey key = ResetKey.of("minecraft:overworld", ResetMode.PER_PLAYER, playerUuid, "mypack:my_dungeon");
String stored = key.toStorageString();          // "minecraft:overworld|<uuid>|mypack:my_dungeon"
ResetKey back = ResetKey.fromStorageString(stored);
```

`GLOBAL` 模式下作用域是常量 `ResetKey.GLOBAL_SCOPE`（`global`）。

### 1.8 `Placeholders`

```java
String text = Placeholders.resolve("已重置 ${structure}（${player}）", Map.of(
        "structure", "cataclysm:burning_arena",
        "player", "Steve"));
```

未知键原样保留。内置可用键见 [DATAPACK.md 的占位符表](DATAPACK.md#21-占位符)。

### 1.9 `DefinitionException`

`RuntimeException` 子类，带 `fieldPath()`：

```java
try {
    DefinitionParser.parse("mypack:broken", json);
} catch (DefinitionException error) {
    error.fieldPath();  // "actions[0].type"
    error.getMessage(); // "actions[0].type: must be a non-blank string"
}
```

### 1.10 其余纯逻辑类（速查）

以下类同样位于 `com.structurereset.api`，可直接使用，也可被单元测试放心引用（零 Minecraft 依赖）：

| 类 | 用途 |
| --- | --- |
| `ResetMode` / `ResetTrigger` | 定义的两种枚举：`GLOBAL`/`PER_PLAYER`；`IN_STRUCTURE`/`ANCHOR_BLOCK`/`EITHER` |
| `ItemSettings` | 物品参数（最大次数、不可破坏、冷却、录入是否消耗）的纯数据载体 |
| `LearnedContainer` / `LearnedAnchor` | 结构记录的容器条目与整条记录（含 id、维度、结构 id、Boss、记录点与半径、证据） |
| `LearnedAnchorSelector` | 记录选择规则：**同一维度 + 结构 id 命中且落在该记录半径内**；多条命中时**取离玩家最近者**，距离相同再比录入时间 |
| `RespawnPoint` / `RespawnBranch` / `RespawnChoice` / `RespawnPlanner` | 准星不可用时的推荐复活点：三点采样、六级降级链与安全落点校验 |
| `SpawnHeights` | 生成高度序列：飞行类从标记点 `+2` 起逐层向上（`FLYER_LIFT` / `FLYER_SEARCH`） |
| `SpawnVerdict` | 区分「被打死」与「自我丢弃」：后者被记入「无法还原」名单（见 [LEARNING.md 第 19 节](LEARNING.md)） |

---

## 2. 核心运行时：`com.structurereset.core`

### 2.1 `StructureResetApi`

```java
// 读取
Collection<ResetDefinition> all = StructureResetApi.definitions();
ResetDefinition one = StructureResetApi.definition("cataclysm:burning_arena"); // 可为 null
Set<ResourceLocation> ids = StructureResetApi.registeredActionTypeIds();

// 增删
StructureResetApi.registerDefinition(definition);      // 同 id 覆盖
StructureResetApi.removeDefinition("mypack:my_dungeon");

// 自定义动作类型（见 2.4）
StructureResetApi.registerActionType(
        new ResourceLocation("mypack", "announce"),
        new MyAnnounceAction());

// 监听器：pre 可取消，post 只能观察
StructureResetApi.addPreListener((ResetPreEvent event) -> {
    if (event.getActor() == null) {
        event.setCanceled(true);      // `Cancelable
    }
});
StructureResetApi.addPostListener((ResetPostEvent event) -> {
    event.setMessage("已重置 " + event.getDefinition().id());
});
```

| 方法 | 说明 |
| --- | --- |
| `definitions()` | 当前已加载的全部定义（数据包 + 脚本注册） |
| `definition(String id)` | 按 id 取一条，不存在返回 `null` |
| `removeDefinition(String id)` | 删除一条，返回是否删掉过 |
| `registerDefinition(ResetDefinition)` | 注册/覆盖，立即生效 |
| `registerActionType(ResourceLocation, ResetActionType)` | 注册自定义 action 类型 |
| `registeredActionTypeIds()` | 已注册的动作类型 id 集合 |
| `addPreListener(Consumer<ResetPreEvent>)` | 重置**前**回调；可 `setCanceled(true)` |
| `addPostListener(Consumer<ResetPostEvent>)` | 重置**后**回调 |
| `findDefinitionAt(...)` | 查询某个位置/结构命中的定义 |
| `canReset(...)` | 只做检查，不真正执行（权限/冷却/物品） |
| `triggerReset(...)` | 主动触发一次重置 |

### 2.2 `ItemAppearanceApi`

```java
ItemAppearanceApi.setModelOverride(item, new ResourceLocation("mypack", "item/reset_core_gold"));
Optional<ResourceLocation> current = ItemAppearanceApi.modelOverride(item);
ItemAppearanceApi.clearModelOverride(item);
```

| 方法 | 说明 |
| --- | --- |
| `setModelOverride(Item, ResourceLocation)` | 覆盖该物品的模型 id（客户端显示用） |
| `modelOverride(Item)` | 读取当前覆盖，没有则 `Optional.empty()` |
| `clearModelOverride(Item)` | 撤销覆盖，恢复模组自带模型 |

### 2.3 `ResetContext` / `ResetResult`

| `ResetContext` 访问器 | 类型 | 说明 |
| --- | --- | --- |
| `getLevel()` | `ServerLevel` | 发生重置的维度 |
| `getOrigin()` | `BlockPos` | 触发原点（锚点或玩家位置） |
| `getBox()` | `AABB` | 本次重置的作用范围 |
| `getActor()` | `ServerPlayer` | 触发者，可能为 `null` |
| `getDefinition()` | `ResetDefinition` | 命中的定义 |
| `getParams()` | `JsonObject` | 当前动作的 `params` |
| `vars()` | `Map<String,String>` | 占位符变量表（x/y/z/player/structure/…） |

| `ResetResult` 访问器 | 类型 | 说明 |
| --- | --- | --- |
| `success()` | `boolean` | 该动作是否成功 |
| `messageKey()` | `String` | 结果消息（语言键或文本），永不为 `null` |
| `actionsRun()` | `List<String>` | **已执行动作的类型 id 列表**（注意：不是数字） |
| `actionCount()` | `int` | 已执行动作数量（= `actionsRun().size()`） |
| `error()` | `String` | 失败原因，成功时为空串 |

构造结果一律用静态工厂，不要用位置构造：

```java
ResetResult good = ResetResult.ok("mypack.msg.done", List.of("structurereset:respawn_boss"));
ResetResult bare = ResetResult.ok(List.of("structurereset:refill_loot"));
ResetResult bad  = ResetResult.fail("respawn_boss: entity not found");
```

### 2.4 `ResetActionType`

**注意它不是函数式接口**（有两个方法），所以不能写成 lambda：

```java
public interface ResetActionType {
    String id();
    ResetResult run(ResetContext context);
}
```

实现示例：

```java
public final class AnnounceAction implements ResetActionType {
    `Override
    public String id() {
        return "mypack:announce";
    }

    `Override
    public ResetResult run(ResetContext context) {
        String text = Placeholders.resolve(
                context.getParams().has("text") ? context.getParams().get("text").getAsString() : "",
                context.vars());
        return ResetResult.ok(text, List.of("mypack:announce"));
    }
}

StructureResetApi.registerActionType(new ResourceLocation("mypack", "announce"), new AnnounceAction());
```

注册后即可在任意定义的 `actions` 里写
`{ "type": "mypack:announce", "params": { "text": "结构已重置" } }`。

### 2.5 事件 `ResetPreEvent` / `ResetPostEvent`

两者都提供：`getLevel()`、`getOrigin()`、`getBox()`、`getActor()`、`getDefinition()`、
`getMessage()`、`setMessage(String)`。`ResetPreEvent` 带 `javax.annotation.Nullable` 风格的
`@Cancelable` 语义，即继承 Forge 的 `Event`，可以 `setCanceled(true)` / `isCanceled()`。

---

## 3. KubeJS 桥接类

装了 KubeJS 之后，插件 `com.structurereset.kubejs.StructureResetKubeJSPlugin` 会把下面这些对象
注入脚本作用域：

| Java 类 | 脚本里的名字 | 用途 |
| --- | --- | --- |
| `com.structurereset.kubejs.StructureResetBindings` | `StructureReset` | 注册/删除/列出定义、注册动作、换模型 |
| `com.structurereset.kubejs.StructureResetEvents` | `StructureResetEvents` | `onPreReset` / `onPostReset` |
| `com.structurereset.kubejs.ResetEventJS` | 回调参数 | 单次重置事件视图，可 `cancel()` |
| `com.structurereset.kubejs.ResetContextJS` | 回调参数 / 动作参数 | 只读上下文视图 |

用法见 [KUBEJS.md](KUBEJS.md)。

---

## 4. 版本与兼容

* 目标：Minecraft 1.20.1 / Forge 47.x / Java 17。
* 不使用 Mixin，因此 API 都是普通 Java 调用与 Forge 事件。
* `com.structurereset.api` 下的类**不得**引入 Minecraft/Forge 类型，
  否则离线 `tools/run_tests.py` 无法编译（它的 classpath 只有 JUnit 和 gson）。
* KubeJS 是软依赖：未安装时 `com.structurereset.kubejs` 下的类不会被加载。