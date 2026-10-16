# KubeJS 集成（KUBEJS）

KubeJS 是**软依赖**：没装时 `src/main/resources/kubejs.plugins.txt` 不会有人读，模组功能完全不受影响；
装了之后本模组把自己注册成 KubeJS 插件，并注入 `StructureReset` / `StructureResetEvents` 两个脚本对象。

## 1. 插件发现机制：`kubejs.plugins.txt`

KubeJS 2001.x 只认 **jar 根目录的 `kubejs.plugins.txt`**：全 jar 里只有
`dev/latvian/mods/kubejs/util/KubeJSPlugins.class` 的常量池含该文件名，且 KubeJS 自己就用它注册内置插件；
`META-INF/services/dev.latvian.mods.kubejs.KubeJSPlugin`（ServiceLoader）这条路径**没有任何 .class 引用**，
写了也没人读。本模组因此只写前者：

```text
com.structurereset.kubejs.StructureResetKubeJSPlugin
```

每行格式是 `<插件类全限定名> [需要的模组 id]`，第二个可选 token 表示「只有装了该模组才加载」，
例如 KubeJS 自带的 `...BuiltinKubeJSForgeClientPlugin client`。

`KubeJSPlugin` 在 2001.x 里是**类**而不是接口，所以插件用 `extends`；本模组只实现三个钩子：
`init()`（初始化事件组）、`registerEvents()`、`registerBindings(BindingsEvent)`，
用 `event.add("StructureReset", StructureResetBindings.class)` 注入绑定。`BindingsEvent` 只有一个添加方法 `add(String, Object)`。

插件缓存与实测复现：`tools/lib/kubejs-forge-<kubejs_version>.jar` 用
`python tools/validate_checks.py kubejs-fetch` 取回并扫描（该文件与 `gradle.properties` 的版本必须一致）。

## 2. `StructureReset` 绑定

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `register(definition)` | JS 对象 | 定义 id | 注册 / 覆盖一条定义，字段与数据包 JSON 完全一致 |
| `remove(id)` / `get(id)` | 字符串 | 布尔 / 定义对象 | 删除 / 按 id 取一条 |
| `list()` / `actionTypes()` | — | 字符串数组 | 当前全部定义 id / 已注册动作类型 id |
| `reload()` | — | 布尔 | 对当前服务端跑一次原版 `/reload` |
| `registerAction(type, handler)` | 类型 id + 函数 | — | 注册自定义动作 |
| `setItemModel(itemId, targetItemId)` | 两个**已注册物品** id | 布尔 | 借用另一个物品已烘焙的物品模型（不是模型文件路径） |
| `clearItemModel(itemId)` / `itemModel(itemId)` | id | 布尔 / 字符串 | 撤销 / 读取当前模型覆盖 |
| `learned()` / `forget(id)` | — / 字符串 | JsonObject 数组 / 布尔 | 查看 / 删除学习（录入）记录 |

### 2.1 注册一条定义

```javascript
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
```

### 2.2 自定义动作

```javascript
StructureReset.registerAction('mypack:announce', context => {
  const params = context.params;                    // JsonObject
  const text = params.has('text') ? params.get('text').getAsString() : '结构已重置';
  if (context.player) context.player.tell('§e' + text);
  // context.cancel() 会把这次动作判定为失败
});
```

`context` 是 `ResetContextJS`，可读 `level` / `player` / `structure` / `definition` / `origin` / `box` /
`success` / `actionsRun` / `params` / `vars`，可用 `setMessage(String)` / `cancel()`。

### 2.3 学习（录入）记录

录入只能在游戏里做：玩家潜行 + 手持 `structurereset:structure_reset_core` 右键 Mob，
或 `/structurereset records teach-here`（权限等级 2）。KubeJS 侧只有只读的 `learned()` 与删除的 `forget(id)`：

```javascript
const anchors = StructureReset.learned();     // JsonObject 副本，改它不会写回存档
anchors.forEach(anchor => console.log(anchor.get('id').getAsString()
    + ' | boss=' + anchor.get('boss_entity').getAsString()));
StructureReset.forget('learned:1');            // 真删掉了才返回 true
```

字段名与 `LearnedAnchor#toJson` 一致；完整流程、命令与配置项见 [LEARNING.md](LEARNING.md)。

## 3. `StructureResetEvents` 事件

| 事件 | 时机 | 能否取消 |
| --- | --- | --- |
| `StructureResetEvents.onPreReset(handler)` | 重置**之前** | ✅ `event.cancel()` |
| `StructureResetEvents.onPostReset(handler)` | 重置**之后** | ❌ 只能观察 |

```javascript
StructureResetEvents.onPreReset(event => {
  if (event.definition.requiredItem && !(event.player && event.player.hasPermissions(2))) {
    event.setMessage('§c需要管理员权限');
    event.cancel();                     // 中止本次重置（内部复用 KubeJS 的 EventExit 控制流）
  }
});
StructureResetEvents.onPostReset(event => {
  console.log(event.structure + ' success=' + event.success + ' actionsRun=' + event.actionsRun);
});
```

事件字段与 Java 访问器一一对应（Rhino 的 JavaBean 规则）：`level` / `player` / `structure` /
`definition` / `origin` / `box` / `success` / `actionsRun` / `message`，另有 `cancel()` / `setMessage(String)`。

## 4. 覆盖本体配方：`ServerEvents.recipes`

```javascript
ServerEvents.recipes(event => {
  event.remove({ id: 'structurereset:structure_reset_core' });       // 按配方 id 删
  event.remove({ output: 'structurereset:structure_reset_core' });   // 按产物删
  event.shaped('structurereset:structure_reset_core', ['ABA', 'BCB', 'ABA'],
    { A: 'minecraft:amethyst_shard', B: 'minecraft:echo_shard', C: 'minecraft:nether_star' })
    .id('mypack:structure_reset_core_from_nether_star');
});
```

## 5. 覆盖贴图 / 模型：`kubejs/assets`

`kubejs/assets/` 会被 KubeJS 当作**内建资源包**加载（因此**不需要** `pack.mcmeta`）：

```javascript
// 借用另一个已注册物品烘焙好的模型；两个参数都要解析到已注册物品，否则立刻返回 false
StructureReset.setItemModel('structurereset:structure_reset_core', 'minecraft:clock');

// 想用自己的模型 / 贴图 / 名字，就按资源包路径覆盖：
//   kubejs/assets/structurereset/models/item/structure_reset_core.json
//   kubejs/assets/structurereset/textures/item/structure_reset_core.png
//   kubejs/assets/structurereset/lang/zh_cn.json
```

完整文件树与逐步做法见 [RESOURCEPACK.md](RESOURCEPACK.md)。

## 6. 可复制示例与排错

`kubejs-examples/` 下有 6 个脚本：`01_custom_definition.js`（register/list/reload）、
`02_recipe_overrides.js`（ServerEvents.recipes）、`03_events_and_actions.js`（registerAction + onPreReset/onPostReset）、
`04_item_appearance.js`（ItemEvents.modification + setItemModel）、`05_bindings_probe.js`（绑定自检）、
`06_learned_anchors.js`（learned/forget）。

| 现象 | 原因 |
| --- | --- |
| 脚本里 `StructureReset` 未定义 | 模组没装，或 `kubejs.plugins.txt` 没进 jar（`kubejs` 检查会验证） |
| `onPreReset` 不触发 | 插件类的 `registerEvents()` 没调用 `StructureResetEvents.register()` |
| 改了数据包没生效 | 用 `StructureReset.reload()` 或服务器 `/reload` |
| `setItemModel` 返回 false | 参数写成了模型文件路径，或不是已注册物品 id |
| 模型不显示 | 模型 / 贴图不在 `kubejs/assets/<ns>/...` 下 |

校验：`python tools/check_all.py --only kubejs`（实测发现机制、插件类、绑定与示例脚本）。
