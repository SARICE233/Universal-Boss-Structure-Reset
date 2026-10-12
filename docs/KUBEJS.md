# KubeJS 集成（KUBEJS）

Universal-Boss-Structure-Reset 对 **KubeJS** 是**软依赖**：没装 KubeJS 时，`src/main/resources/kubejs.plugins.txt`
不会有人读，模组功能完全不受影响；装了 KubeJS 之后，本模组会把自己注册成 KubeJS 插件，
并把 `StructureReset` / `StructureResetEvents` 两个对象注入脚本作用域。

---

## 1. 结论：插件发现机制是 `kubejs.plugins.txt`

**实测结论（不是猜的，见第 2 节的命令与输出）：**

| 机制 | 是否被 KubeJS 2001.6.5-build.26 使用 | 证据 |
| --- | --- | --- |
| Jar 根目录的 **`kubejs.plugins.txt`** | ✅ **是**（唯一机制） | 全 jar 955 个条目里，只有 `dev/latvian/mods/kubejs/util/KubeJSPlugins.class` 的常量池含该文件名字符串；而且 **KubeJS 自己就在用这个文件**注册它的内置插件 |
| `META-INF/services/dev.latvian.mods.kubejs.KubeJSPlugin`（ServiceLoader） | ❌ 不是 | 没有任何 .class 引用该路径；jar 里的 `META-INF/services/` 只放了 4 个 platform helper 接口，**没有** KubeJSPlugin 这一项 |

所以本模组在 `src/main/resources/` 下只写 **`kubejs.plugins.txt`**，不写 `META-INF/services/` 版本
（写了也没人读，只会让后来的人误判机制）。

### 1.1 我们 ship 的产物

文件：`src/main/resources/kubejs.plugins.txt`

````text
com.structurereset.kubejs.StructureResetKubeJSPlugin
````

**每行一条，格式是 `<插件类全限定名> [需要的模组 id]`**：第二个可选 token 表示"只有装了该模组
才加载这个插件"。KubeJS 自带的文件就是这种写法：

````text
dev.latvian.mods.kubejs.forge.BuiltinKubeJSForgePlugin
dev.latvian.mods.kubejs.forge.BuiltinKubeJSForgeClientPlugin client
dev.latvian.mods.kubejs.integration.forge.gamestages.GameStagesIntegration gamestages
````

（第 2 行只有客户端加载，第 3 行只有装了 gamestages 才加载。）

---

## 2. 实测过程与证据

### 2.1 取到真实 jar

````bash
python tools/fetch_kubejs.py
````

它用浏览器 User-Agent 从
`https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-forge/2001.6.5-build.26/kubejs-forge-2001.6.5-build.26.jar`
下载并缓存到 `tools/lib/kubejs-forge-2001.6.5-build.26.jar`（1,658,792 字节，955 个条目），
然后用 `zipfile` 扫描每个 `.class` 的常量池。真实输出：

````text
fetch-kubejs: cached jar is valid (kubejs-forge-2001.6.5-build.26.jar)
fetch-kubejs: jar = ...\tools\lib\kubejs-forge-2001.6.5-build.26.jar
fetch-kubejs: discovery file name <- referencing .class entries
  kubejs.plugins.txt
      dev/latvian/mods/kubejs/util/KubeJSPlugins.class
fetch-kubejs: plugin-list files shipped inside the jar: kubejs.plugins.txt
fetch-kubejs: kubejs.plugins.txt -> likely scanner: dev/latvian/mods/kubejs/util/KubeJSPlugins.class
````

### 2.2 谁在读它

````bash
javap -p -cp tools/lib/kubejs-forge-2001.6.5-build.26.jar dev.latvian.mods.kubejs.util.KubeJSPlugins
````

````text
public class dev.latvian.mods.kubejs.util.KubeJSPlugins {
  public static void load(java.util.List<dev.architectury.platform.Mod>, boolean);
  private static void load(dev.architectury.platform.Mod, boolean) throws java.io.IOException;
  private static void loadFromFile(java.util.stream.Stream<java.lang.String>, java.lang.String, boolean);
  ...
}
````

`load(Mod, boolean)` + `loadFromFile(Stream<String>, ...)` 正好对应"从每个模组自己的文件里逐行读插件类名"。

### 2.3 插件的生命周期钩子（javap 自真实 jar）

````text
public class dev.latvian.mods.kubejs.KubeJSPlugin {
  public void init();
  public void initStartup();
  public void registerEvents();
  public void registerBindings(dev.latvian.mods.kubejs.script.BindingsEvent);
  public void registerClasses(dev.latvian.mods.kubejs.script.ScriptType, dev.latvian.mods.kubejs.util.ClassFilter);
  ...
}
````

**注意：`KubeJSPlugin` 在 2001.x 里是一个「类」，不是接口**，所以插件要写成 `extends`；
`BindingsEvent` 只有一个添加方法 `public void add(String, Object)`。

本模组只用到三个钩子（见 `StructureResetKubeJSPlugin.java`）：

````java
public class StructureResetKubeJSPlugin extends KubeJSPlugin {
    `Override public void init() { StructureResetEvents.init(); }
    `Override public void registerEvents() { StructureResetEvents.register(); }
    `Override public void registerBindings(BindingsEvent event) {
        event.add("StructureReset", StructureResetBindings.class);
    }
}
````

### 2.4 校验

````bash
python tools/validate_kubejs.py     # 会重新实测一次发现机制，并要求本模组 ship 了对应文件
python tools/validate_kubejs.py --selftest
````

---

## 3. `StructureReset` 绑定

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `register(definition)` | JS 对象 | 定义 id | 注册/覆盖一条定义，字段与数据包 JSON 完全一致 |
| `remove(id)` | 字符串 | 布尔 | 删除一条定义 |
| `list()` | — | 字符串数组 | 当前全部定义 id（已排序） |
| `get(id)` | 字符串 | 定义对象/null | 按 id 取一条 |
| `reload()` | — | 布尔 | 对当前服务端跑一次原版 `/reload` |
| `registerAction(type, handler)` | 类型 id + 函数 | — | 注册自定义动作 |
| `actionTypes()` | — | 字符串数组 | 已注册的动作类型 id |
| `setItemModel(itemId, targetItemId)` | 两个 id | 布尔 | 借用**另一个已注册物品**的物品模型（不是模型文件路径） |
| `clearItemModel(itemId)` | id | 布尔 | 撤销模型覆盖 |
| `itemModel(itemId)` | id | 字符串/null | 读取当前覆盖 |
| `learned()` | — | JsonObject 数组 | 全部学习（录入）记录的 JSON 副本 |
| `forget(id)` | 字符串 | 布尔 | 删除一条结构记录 |

### 3.1 注册一条定义

````javascript
StructureReset.register({
  id: 'mypack:my_dungeon',
  structure: ['minecraft:stronghold', '#minecraft:village'],
  radius: 96,
  trigger: 'either',
  mode: 'per_player',
  anchor_blocks: ['minecraft:lodestone'],
  cooldown_seconds: 1800,
  consume_item: true,
  required_item: 'minecraft:echo_shard',
  priority: 10,
  actions: [
    { type: 'structurereset:respawn_boss', params: { entity: 'minecraft:warden', count: 1 } },
    { type: 'structurereset:refill_loot', params: { mode: 'empty_only', reroll_seed: true } }
  ]
});
````

### 3.2 自定义动作

````javascript
StructureReset.registerAction('mypack:announce', context => {
  const params = context.params;                        // JsonObject
  const text = params.has('text') ? params.get('text').getAsString() : '结构已重置';
  if (context.player) context.player.tell('§e' + text);
  console.log(context.definition.id + ' ` ' + context.origin);
  // context.cancel() 会把这次动作判定为失败
});
````

动作的 `context` 是 `ResetContextJS`，可读：`level`、`player`、`structure`、`definition`、`origin`、
`box`、`success`、`actionsRun`、`params`、`vars`，并可用 `setMessage(String)` / `cancel()`。

### 3.3 查看/删除学习（录入）记录

录入本身**只能**在游戏里做：玩家潜行 + 手持 `structurereset:structure_reset_core` 右键 Mob，
或者用 `/structurereset records teach-here`（权限等级 2）。KubeJS 侧只提供只读的 `learned()` 与
删除的 `forget(id)`，没有任何写入接口。

````javascript
// learned() 返回一个新数组：每项是一个 com.google.gson.JsonObject 副本，改它不会写回存档
const anchors = StructureReset.learned();
anchors.forEach(anchor => {
  console.log(anchor.get('id').getAsString()
      + ' | ' + anchor.get('dimension').getAsString()
      + ' | boss=' + anchor.get('boss_entity').getAsString()
      + ' | containers=' + anchor.get('containers').size());
});

// forget(id)：真的删掉了才返回 true，id 不存在返回 false
const removed = StructureReset.forget('learned:1');
````

字段名与 `LearnedAnchor#toJson` 一致（`id` / `dimension` / `structure_ids` / `boss_entity` / `x` /
`y` / `z` / `radius` / `containers` / `learned_at_game_time` / `learned_by`）。完整流程、命令与配置项见
[LEARNING.md](LEARNING.md)。

---

## 4. `StructureResetEvents` 事件

| 事件 | 时机 | 能否取消 |
| --- | --- | --- |
| `StructureResetEvents.onPreReset(handler)` | 重置**之前** | ✅ `event.cancel()` |
| `StructureResetEvents.onPostReset(handler)` | 重置**之后** | ❌ 只能观察 |

````javascript
// 前置：权限检查
StructureResetEvents.onPreReset(event => {
  if (event.definition.requiredItem && !(event.player && event.player.hasPermissions(2))) {
    event.setMessage('§c需要管理员权限');
    event.cancel();          // 中止本次重置
  }
});

// 后置：播报/统计
StructureResetEvents.onPostReset(event => {
  console.log(event.structure + ' success=' + event.success + ' actionsRun=' + event.actionsRun);
});
````

事件的字段与 Java 访问器一一对应（Rhino 的 JavaBean 规则）：
`event.level`、`event.player`、`event.structure`、`event.definition`、`event.origin`、`event.box`、
`event.success`、`event.actionsRun`、`event.message`；控制方法 `event.cancel()` / `event.setMessage(String)`。

> `cancel()` 复用了 KubeJS 自己的 `EventJS.cancel()` 控制流：它会把对应的
> `ResetPreEvent` 标记为 cancelled，然后抛 KubeJS 的 `EventExit` 结束当前脚本回调。

---

## 5. 用 `ServerEvents.recipes` 覆盖/删除本体配方

````javascript
ServerEvents.recipes(event => {
  // 按配方 id 删（id = data/structurereset/recipes/ 下的路径）
  event.remove({ id: 'structurereset:structure_reset_core' });

  // 按产物删（不管配方 id）
  event.remove({ output: 'structurereset:structure_reset_core' });

  // 重写成整合包自己的配方
  event.shaped('structurereset:structure_reset_core', [
    'ABA', 'BCB', 'ABA'
  ], {
    A: 'minecraft:amethyst_shard',
    B: 'minecraft:echo_shard',
    C: 'minecraft:nether_star'
  }).id('mypack:structure_reset_core_from_nether_star');

  // 无序配方
  event.shapeless('2x structurereset:structure_reset_core', [
    'minecraft:echo_shard', 'minecraft:diamond_block'
  ]).id('mypack:structure_reset_core_shapeless');
});
````

---

## 6. 用 `kubejs/assets` 覆盖贴图/模型

KubeJS 会把 `kubejs/assets/` 当成一个**内建资源包**加载（所以**不需要** `pack.mcmeta`）：

````text
kubejs/
├── assets/
│   └── mypack/                                     # 你自己的命名空间
│       ├── models/item/reset_core_gold.json
│       └── textures/item/reset_core_gold.png
└── startup_scripts/
    └── 04_item_appearance.js
````

````javascript
// 指向自己的模型；解析规则 assets/<ns>/models/<path>.json
// 第二个参数是「另一个物品的 id」（借用它的物品模型），不是模型文件路径
StructureReset.setItemModel('structurereset:structure_reset_core', 'minecraft:clock');

// 想直接覆盖模组自带的贴图/名字，就用同样的路径：
//   kubejs/assets/structurereset/textures/item/structure_reset_core.png
//   kubejs/assets/structurereset/lang/zh_cn.json
//     { "item.structurereset.structure_reset_core": "结构重置核心·改" }
````

> 关于 `ItemEvents.modification`：实测签名是
> `modify(net.minecraft.world.item.crafting.Ingredient, java.util.function.Consumer<net.minecraft.world.item.Item>)`，
> 回调拿到的是**真实 Item 实例**，所以 1.20.1 的显示名/描述应当走上面的语言文件覆盖。
> 完整的资源替换步骤与文件树见 [RESOURCEPACK.md](RESOURCEPACK.md)。

---

## 7. 可复制示例

本仓库的 `kubejs-examples/` 下有 6 个可直接复制的脚本：

| 文件 | 演示内容 |
| --- | --- |
| `server_scripts/01_custom_definition.js` | `StructureReset.register` / `list` / `reload` |
| `server_scripts/02_recipe_overrides.js` | `ServerEvents.recipes` 覆盖与删除配方 |
| `server_scripts/03_events_and_actions.js` | `registerAction` + `onPreReset`/`onPostReset` + 权限检查 |
| `startup_scripts/04_item_appearance.js` | `ItemEvents.modification` + `setItemModel` + `kubejs/assets` 说明 |
| `startup_scripts/05_bindings_probe.js` | 最小自检：绑定是否加载 |
| `server_scripts/06_learned_anchors.js` | `StructureReset.learned()` / `StructureReset.forget(id)` 查看与删除结构记录 |

---

## 8. 排错

| 现象 | 原因 |
| --- | --- |
| 脚本里 `StructureReset` 未定义 | 模组没装，或 `kubejs.plugins.txt` 没进 jar（`python tools/validate_kubejs.py` 会检查） |
| `onPreReset` 不触发 | KubeJS 事件组没注册；确认插件类 `registerEvents()` 调用了 `StructureResetEvents.register()` |
| 改了数据包没生效 | 用 `StructureReset.reload()` 或服务器 `/reload` |
| `setItemModel` 返回 false | 第一个参数不是已注册物品；第二个参数不是**已注册物品的 id**（写成模型文件路径就属于这种），或服务端没有该物品 |
| 日志出现 `which is not a registered item with an inventory model` | 目标不是物品 id。换贴图请改贴图文件或给物品模型加 `custom_model_data` 覆盖，见 [docs/RESOURCEPACK.md](RESOURCEPACK.md) |
| 模型不显示 | 模型/贴图路径不在 `kubejs/assets/<ns>/...` 下 |
