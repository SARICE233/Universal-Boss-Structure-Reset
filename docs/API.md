# 公共 Java API（API）

两套 API：

1. **纯逻辑层 `com.structurereset.api`** —— 不依赖 Minecraft / Forge，可在普通 JVM 上单测
   （离线 `python tools/run_tests.py` 与 `package` 都靠这一点）：定义模型、解析器、选择器、冷却、占位符。
2. **核心运行时 `com.structurereset.core`** —— 真正改世界的那一层，需要 Forge classpath：
   `StructureResetApi` / `ItemAppearanceApi` / `ResetContext` / `ResetResult` / `ResetActionType`，
   以及事件 `core.event.ResetPreEvent` / `ResetPostEvent`。

## 1. 纯逻辑层：`com.structurereset.api`

### 1.1 `ResetDefinition`（不可变，用 builder 构造）

```java
ResetDefinition definition = ResetDefinition.builder()
        .id("mypack:my_dungeon")                   // 必须是 "namespace:path"
        .addStructure("minecraft:stronghold")      // 或 #minecraft:village（结构标签）
        .radius(96.0D)                             // > 0，默认 64
        .trigger(ResetTrigger.EITHER)              // IN_STRUCTURE / ANCHOR_BLOCK / EITHER
        .anchorBlock("minecraft:lodestone")
        .mode(ResetMode.PER_PLAYER)                // GLOBAL / PER_PLAYER
        .cooldownSeconds(1800)                     // >= 0
        .consumeItem(true)
        .requiredItem("minecraft:echo_shard")      // 可为 null
        .priority(10)
        .addAction(ResetActionSpec.of("structurereset:refill_loot"))
        .build();
```

访问器与定义字段一一对应：`id()`、`structures()`、`radius()`、`trigger()`、`anchorBlocks()`、
`mode()`、`cooldownSeconds()`、`consumeItem()`、`requiredItem()`、`priority()`、`actions()`、`extra()`。
`build()` 校验 id 形状、至少一个结构、`radius > 0`、`cooldownSeconds >= 0`、至少一条 action，
否则抛 `DefinitionException`。

### 1.2 解析与选择

```java
ResetDefinition parsed = DefinitionParser.parse("mypack:my_dungeon", jsonObject);
Optional<ResetDefinition> winner = DefinitionSelector.select(
        allDefinitions, "minecraft:stronghold", Set.of("minecraft:village"), true);
```

`StructureRef.parse("…")` 支持 `#` 标签（`tag()`），`matchesId(String)` 判断命中。
`DefinitionSelector` 的排序规则：**完全匹配 > 标签匹配 → `priority` 降序 → `id` 字典序**（结果稳定可复现）。
`DefinitionParser` 曝露 `FIELD_*` 常量与 `KNOWN_FIELDS`；解析失败抛带 `fieldPath()` 的
`DefinitionException`（例：`actions[0].type: must be a non-blank string`）。

### 1.3 `ResetActionSpec` 与其余纯逻辑类

```java
ResetActionSpec spec = new ResetActionSpec("structurereset:respawn_boss", paramsObject);
spec.type();      // "structurereset:respawn_boss"
spec.params();    // JsonObject，永不为 null
spec.hasParams(); // true
```

| 类 | 用途 |
| --- | --- |
| `ResetMode` / `ResetTrigger` | `GLOBAL`/`PER_PLAYER`；`IN_STRUCTURE`/`ANCHOR_BLOCK`/`EITHER` |
| `CooldownPolicy` | `ready(now, last, seconds)` / `remainingMillis(...)`；`seconds <= 0` 表示不冷却，时钟回拨视为仍需等待 |
| `ResetKey` | 冷却键 `(维度, 作用域, 定义 id)`，`of(...)` / `toStorageString()` / `fromStorageString(...)` |
| `Placeholders` | `resolve(text, vars)`；未识别键**原样保留**（键表见 [DATAPACK.md](DATAPACK.md)） |
| `ItemSettings` | 物品参数（最大次数 / 不可破坏 / 冷却 / 录入是否消耗）的纯数据载体 |
| `LearnedAnchor` / `LearnedContainer` / `LearnedAnchorSelector` | 结构记录及其选择规则（同维度 + 结构 id 命中 + 落在半径内，取最近者，距离相同再比录入时间） |
| `RespawnPoint` / `RespawnBranch` / `RespawnChoice` / `RespawnPlanner` | 准星不可用时的推荐复活点：三点采样、六级降级链、安全落点校验 |
| `SpawnHeights` / `SpawnVerdict` | 生成高度序列；区分「被打死」与「自我丢弃」（后者进「无法还原」名单） |

## 2. 核心运行时：`com.structurereset.core`

### 2.1 `StructureResetApi`

```java
Collection<ResetDefinition> all = StructureResetApi.definitions();
ResetDefinition one = StructureResetApi.definition("cataclysm:burning_arena"); // 可为 null
StructureResetApi.registerDefinition(definition);       // 同 id 覆盖
StructureResetApi.removeDefinition("mypack:my_dungeon");
StructureResetApi.addPreListener((ResetPreEvent event) -> event.setCanceled(true));
StructureResetApi.addPostListener((ResetPostEvent event) -> event.setMessage("已重置"));
```

| 方法 | 说明 |
| --- | --- |
| `definitions()` / `definition(id)` | 当前已加载的全部定义（数据包 + 脚本注册）/ 按 id 取一条 |
| `registerDefinition(...)` / `removeDefinition(id)` | 注册或覆盖 / 删除，均立即生效 |
| `registerActionType(ResourceLocation, ResetActionType)` / `registeredActionTypeIds()` | 自定义动作类型及其 id 集合 |
| `addPreListener(...)` / `addPostListener(...)` | 重置前（可 `setCanceled(true)`）/ 重置后回调 |
| `findDefinitionAt(...)` / `canReset(...)` / `triggerReset(...)` | 查询命中定义 / 只检查不执行 / 主动触发 |

### 2.2 `ItemAppearanceApi`

```java
ItemAppearanceApi.setModelOverride(item, new ResourceLocation("mypack", "item/reset_core_gold"));
Optional<ResourceLocation> current = ItemAppearanceApi.modelOverride(item);
ItemAppearanceApi.clearModelOverride(item);
```

覆盖的是**该物品的模型 id**（客户端显示用），`clearModelOverride` 恢复模组自带模型。

### 2.3 `ResetContext` / `ResetResult`

| `ResetContext` | 类型 | `ResetResult` | 类型 |
| --- | --- | --- | --- |
| `getLevel()` | `ServerLevel` | `success()` | `boolean` |
| `getOrigin()` / `getBox()` | `BlockPos` / `AABB` | `messageKey()` | `String`（永不为 null） |
| `getActor()` | `ServerPlayer`（可为 null） | `actionsRun()` | `List<String>`：已执行动作的**类型 id 列表**（不是数字） |
| `getDefinition()` / `getParams()` | `ResetDefinition` / `JsonObject` | `actionCount()` | `int` |
| `vars()` | `Map<String,String>` 占位符变量表 | `error()` | `String`（成功时为空串） |

结果一律用静态工厂构造：

```java
ResetResult good = ResetResult.ok("mypack.msg.done", List.of("structurereset:respawn_boss"));
ResetResult bad  = ResetResult.fail("respawn_boss: entity not found");
```

### 2.4 自定义动作类型

`ResetActionType` **不是**函数式接口（两个方法），不能写成 lambda：

```java
public interface ResetActionType {
    String id();
    ResetResult run(ResetContext context);
}

public final class AnnounceAction implements ResetActionType {
    @Override public String id() { return "mypack:announce"; }

    @Override public ResetResult run(ResetContext context) {
        String text = Placeholders.resolve(
                context.getParams().has("text") ? context.getParams().get("text").getAsString() : "",
                context.vars());
        return ResetResult.ok(text, List.of("mypack:announce"));
    }
}

StructureResetApi.registerActionType(new ResourceLocation("mypack", "announce"), new AnnounceAction());
```

注册后即可在任意定义的 `actions` 里写 `{ "type": "mypack:announce", "params": { "text": "结构已重置" } }`。

### 2.5 事件

`ResetPreEvent` / `ResetPostEvent` 都提供 `getLevel()`、`getOrigin()`、`getBox()`、`getActor()`、
`getDefinition()`、`getMessage()`、`setMessage(String)`；前者继承 Forge 的 `Event`，可
`setCanceled(true)` / `isCanceled()`。

## 3. KubeJS 桥接类

| Java 类 | 脚本里的名字 | 用途 |
| --- | --- | --- |
| `kubejs.StructureResetBindings` | `StructureReset` | 注册 / 删除 / 列出定义、注册动作、换模型、查看学习记录 |
| `kubejs.StructureResetEvents` | `StructureResetEvents` | `onPreReset` / `onPostReset` |
| `kubejs.ResetEventJS` / `kubejs.ResetContextJS` | 回调参数 | 单次重置事件视图（可 `cancel()`）/ 只读上下文视图 |

用法见 [KUBEJS.md](KUBEJS.md)。

## 4. 版本与兼容

* 目标：Minecraft 1.20.1 / Forge 47.x / Java 17；不使用 Mixin，API 都是普通 Java 调用与 Forge 事件。
* `com.structurereset.api` 下的类**不得**引入 Minecraft / Forge 类型，否则离线测试与打包无法编译
  （classpath 只有 JUnit 与 gson）；`pure-logic` 检查会把这条约束固化。
* KubeJS 为软依赖：未安装时 `com.structurereset.kubejs` 下的类不会被加载。
