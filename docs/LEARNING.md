# 学习 / 录入（LEARNING）

Universal-Boss-Structure-Reset 的「学习」特性让整合包不必事先写好数据包定义：玩家在游戏里**教**一次，
模组就把「结构 ↔ Boss ↔ 箱子」的快照记下来；之后在同一个地方重置时，Boss 按记录复活、
箱子按记录重填。结构记录存在世界存档的 `SavedData` 里，重启服务器与 `/reload` 都不会丢。

本文只描述已经落地的代码行为，对应实现：

* `com.structurereset.impl.LearnedAnchorService`：录入入口与位置匹配
* `com.structurereset.api.LearnedAnchorSelector`：不含任何 Minecraft 类型的纯选择逻辑
* `com.structurereset.api.LearnedAnchor` / `LearnedContainer`：一条结构记录与一个箱子的数据形状
* `com.structurereset.impl.LearnedAnchorData`：`SavedData` 持久化
* `com.structurereset.item.StructureResetItem#interactLivingEntity`：潜行右键 Mob 的入口
* `com.structurereset.command.StructureResetCommand`：`/structurereset records ...`
* `com.structurereset.kubejs.StructureResetBindings`：`learned()` / `forget(id)`

## 1. 录入流程

### 1.1 怎么教

1. 玩家潜行（Shift）+ 手持 `structurereset:structure_reset_core`，右键要当作 Boss 的 Mob。
   不潜行的右键会原样交回原版交互（`InteractionResult.PASS`），不会误触录入。
2. 服务端按固定顺序校验（`LearnedAnchorService.teach`）：
   * `learningEnabled` 为 false → 回 `structurereset.message.learning.disabled`；
   * `learningRequireOp` 为 true 且玩家权限等级 < 2 → 回 `structurereset.message.learning.not_op`；
   * **口径：`target.getMaxHealth() >= learningMinMaxHealth` 即接受为目标**，这是判定「是不是 Boss」的
     唯一标准，代码不看任何其它 Boss 特征；不满足回 `structurereset.message.learning.too_weak`。
3. 通过校验后以 Mob 所在方块为锚点：Boss 取实体注册 id，`structure_ids` 取该位置
   `ResetEngine.structureIdsAt(...)` 报告的结构 id 集合，`dimension` 取当前维度 id。
4. 以锚点为中心、`learningRadius`（默认 64）为半径扫描容器：只遍历覆盖范围内**已加载区块**的
   block entity，只认 `RandomizableContainerBlockEntity`，读它的 NBT 并记录 `LootTable` 与
   `LootTableSeed`（seed 只在存在且非 0 时才算「真 seed」，即 `has_seed`）。
5. 覆盖更新规则：**同维度、同 Boss、且与已有记录的直线距离 < 8 格**（`DUPLICATE_RADIUS = 8`）
   时**覆盖更新**那条旧记录（沿用旧 id）；否则分配新 id（`learned:1`、`learned:2`……）。

一条记录落盘后的形状（`LearnedAnchor#toJson`，`containers` 这里只列一项）：

```json
{
  "id": "learned:1",
  "dimension": "minecraft:overworld",
  "structure_ids": ["minecraft:stronghold"],
  "boss_entity": "minecraft:warden",
  "x": 120, "y": 40, "z": -33,
  "radius": 64.0,
  "containers": [
    { "x": 118, "y": 40, "z": -30, "loot_table": "minecraft:chests/stronghold_library",
      "loot_table_seed": 12345, "has_seed": true }
  ],
  "learned_at_game_time": 4200,
  "learned_by": "Dev"
}
```

### 1.2 录入时机：必须在箱子被搜刮之前做

**录入必须在箱子被搜刮之前做**。这不是一条"建议"，而是实现事实：只有**仍然带 `LootTable`** 的
容器才会被写进记录（`LearnedAnchorService.scanContainers` → `readContainer`）。玩家开过箱子之后，
原版已经把战利品展开进物品栏并把容器的 `LootTable` 清空，此时再录入，扫描只会把它计入
「无表容器数」，`containers` 里根本没有它 —— 重置时自然也就无从补回，这条记录里只剩下 Boss。

`learning.recorded` 的第 4 个参数就是这个数量（中文 `%4$s 个容器没有战利品表或已被搜刮`，
英文 `%4$s container(s) had no loot table left`）。这个数字偏大通常意味着两种情形：

* 你**教晚了**：箱子已经被开过，它再也进不了这条记录；
* 那片容器**本来就不是战利品箱**（玩家手放的箱子没有战利品表）。

想连箱子一起记录，请在开箱之前潜行右键 Mob 录入；已经被搜刮的箱子无法事后补记录，
模板回溯同样未实现（见第 10 节）。

```text
开箱之前录入 -> containers 里带上 {loot_table, loot_table_seed, has_seed}
开箱之后再教 -> 该箱子只计入「无表容器数」，containers 里没有它 -> 重置补不回来
```

## 2. 适用与不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 一次性 Boss 结构；Boss 与战利品箱距离不超过 `learningRadius`；箱子**还没被开过**；写不出或懒得写数据包定义时。 |
| ✅ 适用 | 用 `/structurereset records teach-here <entity_id>` 由管理员显式登记（见 4.1），适合手工场地与自动化/测试。 |
| ❌ 不适用 | 箱子已经被搜刮过（`LootTable` 已被清空，录入补不回箱子；见 1.2 与第 7 节）。 |
| ❌ 不适用 | 目标被门禁拒绝：被剔除的命名空间、拒绝名单、脚本过滤器否决，或血量低于 `learningMinMaxHealth`（默认 300）。**打了 `forge:bosses` 标签的实体直接通过，不看血量。** 顺序见第 12 节。 |
| ❌ 不适用 | 两个不同的 Boss 相距不到 8 格：会被判定成同一条记录而互相覆盖。 |
| ❌ 不适用 | 需要给不同玩家不同进度：结构记录一律按 `GLOBAL` + `EITHER` 回放，没有 per-player 版本。 |
| ❌ 不适用 | 想把结构记录随数据包/整合包分发：它存在存档里，不在数据包里。 |

## 3. 触发重置

1. 在原位置（或记录半径内）手持 `structurereset:structure_reset_core` 右键。
2. `ResetEngine.findDefinition` 先按**注册定义**（数据包 `data/<ns>/structure_resets/` 或 KubeJS
   `register`）匹配；**一个都匹配不上时**才去查学习库 `LearnedAnchorService.learnedDefinition`。
3. 学习库的选择顺序（`LearnedAnchorSelector`，顺序即优先级）：
   * ① 只看**同维度**的记录；
   * ② 若当前位置报告的结构 id 与某条记录的结构 id **有交集**，取其中 `learned_at_game_time`
        **最新**的一条（时间相同则 id 更小的胜出）；
   * ③ 否则取**半径覆盖该位置且距离最近**的记录（距离相同再按 recency/id 决胜，结果与遍历顺序无关）。
4. 命中的记录经 `LearnedAnchorService.definitionFor` 变成一条普通定义：
   `trigger = EITHER`、`mode = GLOBAL`、`consume_item = true`、冷却取 `defaultCooldownSeconds`，
   只带一个动作 `structurereset:restore_learned_anchor`（参数 `anchor_id`）。
   因此冷却、`onPreReset` / `onPostReset` 事件、物品消耗等既有机制全都照常生效。
5. 回放时（`RestoreLearnedAnchorAction`）会先核对记录维度：维度不符直接失败，结构记录不会串到别的
   维度；然后按记录复活 Boss，并对每个箱子执行 `setLootTable(table, seed)` + `unpackLootTable(null)`。

## 4. 命令（权限等级 2）

```text
/structurereset records list              # 列出全部结构记录
/structurereset records remove <id>       # 删除一条记录
/structurereset records clear             # 只提示确认，什么都不删
/structurereset records clear confirm     # 真正清空并报告条数
/structurereset records teach-here        # 教半径内最近的生物（等价于潜行右键）
```

* `list`：空库回 `structurereset.command.learned.list.empty`；非空先发 `list.header`，再每条
  `list.entry`，参数顺序固定为 **id、维度、结构 id 列表、Boss、x、y、z、半径、箱子数**；
  没有结构 id 的记录显示 `-`。
* `remove <id>`：删掉返回 1；id 不存在返回 0 并回 `remove.missing`。
* `clear`：只回 `clear.confirm`；`clear confirm` 才清空并回 `clear.ok`（带被清空的条数）。
* `teach-here`：只把**活着的 `LivingEntity`**、既不是命令执行者也不是其它玩家、离执行者最近、
  且在 `learningRadius` 内的那个当作目标；找不到回 `learning.no_target`。

## 5. KubeJS 用法

绑定只提供**查看**与**删除**，没有任何「写入」入口——录入只能由玩家潜行右键 Mob，或由
`/structurereset records teach-here` 完成。

```javascript
// learned() 返回一条记录一个 JsonObject 的新数组；是副本，脚本改它不会写回存档
const anchors = StructureReset.learned();

for (let i = 0; i < anchors.length; i++) {
  const anchor = anchors[i];
  console.log(anchor.get('id').getAsString()
      + ' | ' + anchor.get('dimension').getAsString()
      + ' | boss=' + anchor.get('boss_entity').getAsString()
      + ' | containers=' + anchor.get('containers').size());
}

// forget(id)：真的删掉了才返回 true，id 不存在返回 false
const removed = StructureReset.forget('learned:1');
console.log('forget -> ' + removed);
```

字段名与 `LearnedAnchor#toJson` 完全一致：`id`、`dimension`、`structure_ids`、`boss_entity`、
`x`、`y`、`z`、`radius`、`containers`、`learned_at_game_time`、`learned_by`；`containers` 的每一项是
`x`、`y`、`z`、`loot_table`、`loot_table_seed`、`has_seed`。
可直接复制的脚本见 `kubejs-examples/server_scripts/06_learned_anchors.js`。

## 6. 配置项

配置文件 `config/structurereset-common.toml`，节 `[learning]`：

| 配置项 | 默认值 | 取值范围 | 说明 |
| --- | --- | --- | --- |
| `learningEnabled` | `true` | 布尔 | 录入总开关；false 时潜行右键 Mob 只会看到「已禁用」 |
| `learningRadius` | `64` | 1..512 | 录入扫描箱子的半径，也是 `teach-here` 找目标的半径 |
| `learningMinMaxHealth` | `60` | 0..100000 | **口径：`maxHealth >= 阈值` 即接受**，是「是否算 Boss」的唯一标准 |
| `learningRequireOp` | `false` | 布尔 | true 时只有权限等级 >= 2 的玩家才能录入 |

## 7. 已知限制

* **快照只能抓到还没被搜刮的箱子**：只有仍然带 `LootTable` 的容器才会被记录；开过的箱子
  `lootTable` 已被清空，教不出来、也就补不回来（录入时机见 1.2，模板回溯见第 10 节）。
* **容器方块被换掉 / 坐标失效时该箱子跳过**：回放时先 `level.isLoaded(pos)`，未加载的跳过；方块不再是
  `RandomizableContainerBlockEntity` 的也跳过；战利品表 id 解析失败或抛异常的同样只跳过并写入日志，
  不会中断整次重置。
* **玩家手工塞满的容器不会重复生成战利品**：回放只做 `setLootTable` + `unpackLootTable(null)`，
  **从不清空容器**；原版对非空容器不会再次展开战利品表，所以玩家自己放进去的东西原样保留。
* **学习库存 overworld 的 `SavedData`，不进数据包**：数据名 `structurereset_learned_anchors`，挂在主世界
  （overworld）的 `DataStorage` 上；它不随数据包/整合包分发，换个存档就没了，想批量分发只能重新教。

## 8. 结构成员资格判定：以 StructureStart 包围盒为准

「这个位置算不算在某个结构里」不再以 piece 包围盒为主判据。当前口径
（`ResetEngine.isInsideStructure` → `ResetEngine.structureIdsAt` / `ResetEngine.structureBoxAt`）：

1. `StructureManager#getAllStructuresAt(pos)` 拿到可能覆盖该位置的候选结构；候选按**注册 id 稳定排序**
   遍历，结果与哈希顺序无关。
2. 对每个候选取 `StructureManager#getStructureAt(pos, structure)`，得到该结构的 `StructureStart`。
3. **只有 `start.getBoundingBox().isInside(pos)` 为真，才算这个结构的成员**：它的注册 id 才进入
   `structure_ids` 并参与选择，它的盒才参与 `structureBoxAt` 的合并（多个结构重叠时取能包住它们的
   最小盒）。

为什么不再把 `StructureManager#getStructureWithPieceAt(pos, structure)`（piece 包围盒）当作主判据：
jigsaw 结构的 piece 盒只覆盖真正被 piece 填满的那部分方块。结构内部的**庭院、空地与 piece 之间的
缝隙**不在任何 piece 的盒里，站在那里用 piece 判定会得到"不在任何结构里" —— `structure_ids` 变成
空集合。空集合会顺着第 3 节的选择顺序退化成按**注册定义 id 的字典序**乱挑（没有结构 id 交集时只能
靠半径 / recency / id 决胜），于是出现「人明明在结构 A 里，却召唤了结构 B 的 Boss」。

包围盒覆盖整个结构（含庭院与缝隙），所以由它做主判据；`getStructureWithPieceAt` 只在包围盒判定
失败之后作为最后兜底 —— 它只能**扩大**一次命中，永远不会单独决定一个结构 id。

```text
对每个候选结构（按注册 id 稳定排序）:
  getStructureAt(pos, structure) -> StructureStart
    start.getBoundingBox().isInside(pos) == true  -> 成员（主判据）
    否则 getStructureWithPieceAt(pos, structure) != null -> 成员（兜底，只扩大命中）
    否则 -> 不是成员，id 不进 structure_ids
```

## 9. 战利品扫描范围：结构包围盒优先，未知时才回落 learningRadius

录入与重置共用同一份范围口径：

* **录入扫描**（`LearnedAnchorService.teach` → `scanContainers`）先用
  `ResetEngine.structureBoxAt(level, pos)` 求「真正包含该位置的 `StructureStart` 包围盒的并集」。
  有这个盒就以它为准 —— 结构自己定义它有多大，多大多小的结构都能整片覆盖；只有盒子为空
  （玩家自建场地、非生成结构、或该位置不属于任何结构）时，才回落到以 Boss 所在位置为中心、
  边长为 `learningRadius`（默认 64）的立方体。
* **重置回放**（`RestoreLearnedAnchorAction`）不再重新扫描，而是直接回放录入时按上述范围记录下来的
  容器坐标；因此它的有效范围**就是那次录入的结构包围盒**，未知结构时就是 `learningRadius` 立方体。
  记录里存了哪几个箱子，重置就补哪几个箱子。

实现上只遍历范围覆盖到的**已加载区块**的 block entity，再按坐标落回范围内，不做逐方块查找：
64 半径的立方体是 129³ 个方块，逐方块查不现实，而结构包围盒还可能远大于它。

```text
structureBoxAt(pos) 有值 -> 扫描范围 = 结构包围盒（各 StructureStart#getBoundingBox 的并集）
        空            -> 扫描范围 = Boss 位置 ± learningRadius（默认 64）的立方体
```

## 10. 未实现的未来增强：模板回溯（被搜刮后恢复战利品）

**模板回溯本轮明确不做。** 它指的是：从 jigsaw 结构模板（`SinglePoolElement#template`）反推每个
箱子原始的战利品表，从而让"已经被搜刮过的箱子"在录入之后也能补回战利品。不做的原因如下：

| 障碍 | 说明 |
| --- | --- |
| `SinglePoolElement#template` 是 `protected` | 本模组在另一个包里，拿不到这个字段；要读它只能靠 access transformer 或反射 |
| `StructureTemplateManager` 需要先有模板 id | 这条链的入口是"模板 id → 模板"，而 piece 本身不直接暴露可用的模板 id，必须先拿到 piece 再反查 |
| `StructureTemplate#filterBlocks` 同样不可见 | 从模板里枚举方块位置的那条链也是受保护 / 需要 AT 的，普通模组代码无法直接调用 |

要落地只有两条路：**access transformer**（改 `META-INF/accesstransformer.cfg`，改变模组的加载期契约）
或**反射**（1.20.1 上脆弱，混淆环境下还要额外处理）。两者都超出本轮范围，所以学习特性当前只记录
"仍带 `LootTable` 的容器"，不做模板回溯。这是**已知的能力缺口，不是 bug**。

## 11. 重置安全口径：宁可拒绝，也不召唤错的 Boss

结构记录的重放全程遵循"解析不了就拒绝、绝不猜"：

1. `params.anchor_id` 缺失或为空 → 动作失败（`structurereset.message.action_failed`），不生成任何实体。
2. 存档里查不到这条 id（已被 `remove` / `clear` 删除，或换了存档）→ 失败；不猜、不回落到别的记录。
3. 记录维度与当前维度不符 → 失败；一条结构记录永远不会串到别的维度去复活 Boss。
4. `boss_entity` 解析不出实体 id，或该 id 在注册表里不存在 / `create` 失败 → 失败，**不生成替代品**。
5. 箱子侧是"尽力而为"而不是"必须成功"：未加载、方块已被换掉、战利品表 id 解析失败或
   `setLootTable` 抛异常，都只跳过该箱子并写日志，不中断整次重置，也绝不猜一个战利品表填进去。

只要有任何一环可能"召唤或填充错的东西"，实现就选择拒绝并提示，而不是继续执行。

```text
维度不符 / 找不到 id / 实体 id 解析失败 -> ResetResult.fail(...)   （拒绝并提示）
箱子未加载或已失效                     -> 跳过该箱子 + 写日志       （不猜表、不中断）
```

## 12. 录入门禁的完整顺序（含 Boss 标签识别）

录入不是「右键谁就记谁」。命中依次判定，**脚本过滤器拥有最终裁量权**：

硬性条件（`teach-here` 命令会绕过这两条）：

| # | 判定 | 默认 |
| --- | --- | --- |
| 1 | 目标是存活的 Mob | — |
| 2 | 玩家身处结构包围盒内（由 `learningRequireStructure` 控制） | 开 |
| 3 | 玩家正看着目标（夹角 ≤ `learningMaxAngleDegrees`） | 45° |

实体分类（被脚本接管时 5-9 全部跳过）：

| # | 判定 | 默认 |
| --- | --- | --- |
| 4 | KubeJS `StructureReset.setBossFilter(fn)` 存在 → **它说了算** | 无 |
| 5 | 命中 `learningEntityAllowlist`（精确 id）→ 接受 | 空 |
| 6 | 命名空间命中 `learningExcludedNamespaces` → 拒绝 | `["minecraft"]` |
| 7 | 命中 `learningEntityDenylist` → 拒绝 | 空 |
| 8 | 命中 `learningBossTags` 中任一实体标签 → 接受 | `["forge:bosses", "structurereset:known_bosses"]` |
| 9 | `learningMinMaxHealth > 0` 时要求 `getMaxHealth()` ≥ 阈值 | 300（`0` = 不检查） |

**为什么默认剔除 `minecraft:`**：原版生物（牛羊、僵尸）都在这个命名空间，它们不可能是我模组要复活的 Boss。
原版 Boss 通常还被结构条件挡住（终界中央岛不是结构；监守者虽在远古城市，但同样被命名空间剔除）。

**推荐的适配方式**：把 Boss 加进 `forge:bosses` 实体标签——这是 Forge 的跨模组约定，
Forge 自己用它列出末影龙与凋灵，**Cataclysm 也用它列出自己那 8 个 Boss**：

```json
{ "replace": false, "values": [ "youmod:your_boss" ] }
```

`structurereset:known_bosses` 是本模组随包提供的兜底标签（Cataclysm 的 8 个 Boss），
即使某个分支不再维护 `forge:bosses` 也仍然识别。

## 13. 左键抓取战利品与战利品索引

第 1.2 节说过：箱子一旦被开过，它的 `LootTable` 就被游戏清掉了，事后再教也补不回来。
**左键抓取就是为这件事设计的**，它把「抓战利品」与「教 Boss」拆成两个独立动作：

* **左键点方块**（手持核心）→ 扫描当前结构范围内的容器，把**当时仍带 `LootTable`** 的位置、
  战利品表与种子记进存档的**战利品索引**（`structurereset_loot_index`）。不消耗耐久、不加冷却、
  可重复执行且幂等（只补还没有的坐标，不会被后来的空记录覆盖）。
* **右键**（录入 / 瞄准—锁定—读条的重置流程）→ 完全不碰战利品索引。

索引有两个使用者：

1. `refill_loot`：容器自己还有 `LootTable` 时按原样重填；**已经没有表**（被搜刮过）时改从索引取表。
   这也让随包定义的补箱第一次真正生效——数据包本来并不知道战利品表。
2. `restore_learned_anchor`：当一条结构记录**没有任何容器快照**（典型的「打到最后才教」）时，
   改为在记录点半径内用索引重填。

因此正确顺序是：**进结构 → 先左键抓一下 → 该开箱开箱、该打 Boss 打 Boss → 之后照常重置。**
抓过的结构，哪怕你晚几十分钟才教，战利品也补得回来。

边界与第 10 节一致：只对**抓取那一刻还没被开过**的箱子有效；没抓过、录入时也没见过的箱子，
事后无法恢复（模板回溯仍未实现）。

## 14. 配置项补充

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `learningRequireStructure` | `true` | 录入必须身处结构内；关掉后允许在结构外录入 |
| `learningMaxAngleDegrees` | `45` | 允许的视线夹角 |
| `learningExcludedNamespaces` | `["minecraft"]` | 按命名空间整批剔除 |
| `learningEntityAllowlist` | `[]` | 精确放行（优先于命名空间剔除） |
| `learningEntityDenylist` | `[]` | 精确拒绝 |
| `learningBossTags` | `["forge:bosses", "structurereset:known_bosses"]` | Boss 实体标签，优先于血量阈值 |
| `learningMinMaxHealth` | `300` | 血量兜底阈值，`0` = 不检查 |
| `learningBossNbtKeys` | `[]` | 可选的 NBT 键加分项（非主判据） |
| `resetRequiresDefeated` | `true` | 结构内**还有活着的 Boss** 时拒绝重置（世界判定，见第 18 节）；任何让 Boss 消失的方式都会放行 |
| `aimRequireInsideStructure` | `true` | 准星指定的位置必须在结构包围盒内 |
| `aimWindowTicks` | `600` | 瞄准阶段时长（tick） |
| `aimCastTicks` | `100` | 锁定后的读条时长（tick，默认 5 秒）；召唤特效与之同长 |
| `aimReach` | `20` | 准星射线距离（格） |

`teach-here <entity_id>` 会**绕过第 2、3 条与 4-9 全部实体分类**：管理员点名一个实体就是最终决定。
这是手工搭建、不属于任何生成结构的 Boss 场地唯一的登记方式。

## 15. 结构扫描（S2）：不瞄准也能登记

**潜行 + 手持核心右键任意方块** → 扫描你所在的结构，登记其中的 Boss。

两路候选，按证据强弱取用：

1. **锚点证据（最强）**：结构内任何方块实体，只要它的 NBT 里存了一个**能解析成已注册实体类型**的字符串，
   就视为锚点候选。这正是「重生方块 / 祭坛 / 墓碑」那一类模式，**Boss 还没出现也能记**。
   （战利品表 id 会被自动排除——它们解析不出实体类型。）
2. **存活实体**：结构包围盒内通过门禁的存活实体，按最大血量降序（本体压过自己的部件）。

最强的候选被记录，**所有候选都会写进日志**，方便管理员核对。想记指定的一只，仍然可以瞄准它潜行右键；
管理员仍然可以用 `teach-here` 覆盖一切。

**为什么必须有这条路**：大型或多部件 Boss（九头蛇、必须打头的娜迦）在客户端就选不中碰撞箱，
**服务端连交互包都收不到**，所以瞄准那一路对它们永远无效。扫描不看碰撞箱、不看距离、不怕移动。

输入分工（互不重叠）：

| 输入 | 行为 |
| --- | --- |
| 右键（不潜行） | 重置（两次确认） |
| **潜行** + 右键**实体** | 录入：只记录这一只 |
| **潜行** + 右键**方块** | 结构扫描：记录本结构的 Boss |
| 左键点方块 | 抓取战利品索引（见第 13 节） |

## 16. 为什么没有「事件自学习」

早期版本提供过一个选项（`autoLearnFromEvents`）：打开后模组自行观察（Boss 进入世界、或死在结构中）并写入结构记录。
**该功能已移除，且不会恢复。** 原因很直接：

* **跑图即误绑**：任何通过 Boss 门禁的实体，只要出现在结构内就会被记为「本结构的 Boss」——
  一只路过的云魔就足以把黄金地牢的 Boss 记错；
* **先写者胜**：已有记录会持续压制新的观察，错误一旦写入便长期存在；
* 一旦记错，玩家右键时会**召唤出错误的 Boss**，这比「没有记录」糟糕得多。

因此现在**每一份结构记录都必须由人主动产生**：

| 方式 | 由谁执行 | 说明 |
| --- | --- | --- |
| 潜行 + 右键**实体** | 玩家 | 只登记这一只 |
| 潜行 + 右键**方块**（结构扫描，第 15 节） | 玩家 | 按锚点/存活实体证据登记本结构的 Boss |
| `/structurereset records teach-here <实体>` | 管理员 | 点名登记，绕过全部门禁 |
| 数据包定义 | 整合包作者 | 显式写死结构 ↔ Boss |

> **Boss 生成点追踪（Boss 注册表）仍然自动进行**：它只记录「这只 Boss 在哪里生成、是否已被击败」，
> 用于复活点与「已击败」门禁，**不产生任何「结构 → Boss」映射**，因此不会绑错。
> 战利品同理：只能靠第 13 节的左键抓取，或在开箱前用结构扫描登记。

## 17. 准星指定复活点：为什么不让算法说了算

算法只能猜。Boss 可能被玩家引到结构外击杀、可能因地形 bug 卡在怪位置、可能被别的模组强制传送——
这些异常数据都会污染「Boss 该在哪里重生」的推断。而**站在结构里的玩家亲眼看着 Boss 倒下，他知道它原本在哪里**。

所以主路径是**玩家直接表达意图**，算法退居为兜底：

**两阶段**（都用玩家自己的持久数据保存状态，没有静态字段）：

1. **瞄准**（第一次右键）：服务端每 tick 用 `Entity#pick(aimReach)` 读准星，命中方块后取其**相邻空气位**
   作为目标点；3×3 粒子轮廓实时跟随，颜色语义：

   | 颜色 | 含义 |
   | --- | --- |
   | 绿 | 合法：在结构内、能站人、且不远离 Boss 原始生成点 |
   | 黄 | 合法但偏离原始生成点超过 32 格（会警告，不禁止） |
   | 红 | 非法：在结构外，或那里站不住 |

   这一阶段**不生成、不消耗、不进冷却**；窗口到期自动清空并提示。

2. **锁定 + 读条**（第二次右键）：位置被锁进玩家持久数据，读条 `aimCastTicks`（**默认 100 tick = 5 秒**）后
   才真正触发重置，`respawn_boss` 从上下文里的玩家取这个位置作为生成点。

   读条期间界面上是**一条会收缩的老板条（boss bar）**——它由客户端逐帧插值绘制，所以是**平滑**的，
   而不是文字格子的 20 步；颜色随维度（主世界蓝 / 下界红 / 其它紫），剩余秒数在 action bar 上；
   同时按**维度**播放召唤特效（时长与读条严格同长，都由 `aimCastTicks` 驱动）：

   | 维度 | 特效 |
   | --- | --- |
   | **主世界** | 白色 + 淡蓝粒子在复活点**螺旋上升**，越接近完成升得越高，结束时长出一束粒子 |
   | **下界** | 读条期间火星上升；**读完的瞬间一道闪电击中该位置**（`setVisualOnly(true)`，不点火、不伤人） |
   | **其它维度**（含末地） | 从**结构里取最多 3 种不同方块**，绕着复活点旋转，配**旋风**（云 + 营火烟），结尾炸开 |

   全部由**服务端播发**：不需要网络包，也不需要任何客户端渲染代码。取方块只用**已加载区块** +
   步进采样（最多约 12³ 次读取），**绝不触发区块加载**。

**准星没有可用位置时**（指到天空/结构外/站不住的地方）不再直接拒绝，而是走下面的推荐算法，
用粒子标出推荐点并提示「已为你选好一处」，由玩家**再右键确认**。

> **为什么不用客户端上报准星**：服务端的 `Entity#pick` 每 tick 都能读到同一份视线方向，
> 于是整条链路不需要任何网络包、不需要客户端渲染代码，还天然满足服务端权威。
> 代价只是准星精度受 20Hz 视线更新限制，对「指定 Boss 位置」这个需求完全够。

### 17.1 推荐位置的降级链

三个参考点：

| 点 | 来源 | 含义 |
| --- | --- | --- |
| **A** | Boss 注册表里的 `spawnPos` | Boss **原始生成位置**，最可靠 |
| **B** | 结构内所有容器的平均坐标（无容器则取结构包围盒中心） | 战利品所在区域 |
| **C** | 触发重置的玩家站位 | 玩家当前所在 |

三点**必须同时在结构包围盒内**才做三角形采样；否则逐级降级：

| 情况 | 处理 |
| --- | --- |
| 三点全内 + 三角形可用（最长边 ≤ 32 且面积 ≥ 4） | 三角形内**均匀采样**（barycentric 取点，可配 `bias` 偏向 A） |
| 三点全内 + 三角形退化 | 加权重心 A×0.5 + B×0.3 + C×0.2 |
| 只有两点在内 | 在内两点加权（A 权重优先：A+B → 0.6/0.4；A+C → 0.7/0.3；B+C → 0.6/0.4） |
| 只剩 A 或 B 在内 | 直接使用该点 |
| 只剩 C 在内，或三点全在外 | 结构包围盒几何中心 |

采样出的点还要过 **`findSafeSpawnPos`**：以该点为中心，先试原地，再向上最多 5 格、向下最多 5 格，
要求「下方是实心、本体与上方可站立」。失败处理：

```text
三角形区域内重采样（最多 3 次）
   -> 仍失败：回退加权重心
   -> 仍失败：回退结构包围盒中心
   -> 仍失败：取消本次生成并提示玩家
```

这套算术全部在**纯逻辑层** `com.structurereset.api.RespawnPlanner`（零 Minecraft import，
`RespawnPlannerTest` 有 15 个单测覆盖有效/过长边/过薄/退化/采样不越界/bias 精确回到 A/六个分支/同种子可复现）。

## 18. Boss 注册表与「已击败」门槛

**原则：生成时预存，死亡时确认。**

* **生成时**（`EntityJoinLevelEvent`，跳过 `loadedFromDisk()` 的区块载入）：
  实体通过 Boss 门禁后，在结构内的就写入 `BossRegistryData`——
  `bossUuid` / `bossEntityType` / `structureIds` / `dimension` / **`spawnPos` + 朝向** / 生成时间；
* **死亡时**（`LivingDeathEvent`）：按 **UUID** 找回同一条记录，标记 `defeated`，
  补上死亡时间、死亡位置与击杀者。

注册表存在 `world/data/structurereset_boss_registry.dat`（SavedData，跟着主世界，全玩家共享）。

**门槛语义（重要）**：`resetRequiresDefeated = true` 时，判据**取自世界本身，而不是存档里的标志**：

> 结构包围盒**外扩 16 格**内，只要有一只**活着且通过 Boss 门禁**的实体 → 拒绝，并**点名**是哪只
> （`该结构仍有存活的 Boss：xxx（击败后可再次重置）`）；否则 → **允许重置**。

这样做的原因是踩过坑：早期版本用注册表的 `defeated` 标志判定，一旦某次死亡没被记上
（例如重置生成的新 Boss 没被绑定），结构就**永久锁死**。改成世界判定后：

* **打死、`/kill`、被别的模组传送走、命令清场** —— 任何让 Boss 消失的方式都会立刻放行；
* 注册表**不再单独拦人**，只用于找复活点、提供「已击败」信息与名字。

配套的两处修正：

* **重置生成的新 Boss 会在生成点直接绑定**（`respawn_boss` / `restore_learned_anchor` 在
  `addFreshEntity` 成功后调用 `BossBindingService.bind`）——定义已经说了它是 Boss，不需要再过门禁；
* **`/kill` 不触发 `LivingDeathEvent`**（vanilla 的 `kill()` 走 `remove(KILLED)`，不经过 `die()`），
  所以另外监听了 `EntityLeaveLevelEvent`（`getRemovalReason() == KILLED`）；
  即使这样仍漏掉，死亡确认还有**位置兜底**：UUID 查不到时按「同实体类型 + 同维度 + 距记录生成点 96 格内」匹配最近一条未击败记录。

> **血条 UUID 反查没有实现**：`BossEvent#getId()` 确实是 UUID，但服务端**没有枚举实体血条的公开途径**
> （只有 `/bossbar` 命令自建的那些在 `getCustomBossEvents()` 里），除非上 Mixin，而本项目承诺不使用 Mixin。
> 因此识别链改为：**数据包定义 > 手动录入 > 注册表 `spawnPos` > `forge:bosses` / `c:bosses` 标签 > 血量兜底**。

## 19. 生成点策略与「无法还原」的 Boss

重置执行时，Boss 并不是简单地落在标记点，而是经过一套固定的尝试顺序（`impl/action/BossSpawn`）：

| # | 步骤 | 作用 |
| --- | --- | --- |
| 1 | `setPersistenceRequired()` | 防止原版「远离玩家即消失」把刚召出的 Boss 清掉 |
| 2 | `finalizeSpawn(..., MobSpawnType.COMMAND)` | **模组 Boss 做初始化的标准位置**（Boss 条、属性、战斗状态、随从）。`EntityType#create` **不会**调用它 —— 跳过初始化会让 Boss 变成空壳，某些模组会因此把它丢弃 |
| 3 | 房间 API（反射 `closeRoom` / `resealRoom` / `resetRoom`） | 部分 Boss（如 Aether 太阳之灵）隶属自家「Boss 房间」，需先让房间回到战前状态；只调用对方**公开**方法，方法不存在就静默跳过 |
| 4 | 抬升放置（标记点 +2 起，逐层向上，另加 ±2 的水平环） | 用**实体自身的碰撞盒**验证是否放得下；低矮房间自动退回地面规则 |

### 19.1 「无法还原」名单（诚实记录）

有一类 Boss **无法**被重建：它的存在条件由**对方模组内部**维护。
例：Aether 的太阳之灵挂载于 Nitrogen 的 `BossRoomTracker`，被单独召唤后会在 **1 tick 内自我移除**
（满血、无掉落、`RemovalReason` 为 `null`）。这类状态通用模组碰不到，不用 Mixin 无法复位。

处理方式（**不假装成功**）：

* 生成后 100 tick 内被移除**且仍存活** → 判定为「自我丢弃」，写入
  `saves/<世界>/data/structurereset_unrestorable.dat`；
* 之后该 Boss 的重置**只重填战利品**，不再播放误导性的召唤特效，并在聊天栏说明原因；
* 管理员删除该 `.dat` 即可重新尝试（若对方模组之后修复了该行为）。

### 19.2 召唤后的观测日志

每次召唤后 5 秒会写一行 `spawn check:`，报告它是否存活、移动了多远，或被移除及其原因：

```text
spawn check: aether:sun_spirit survived and moved 3 block(s) from ... to ... (marked ...)
spawn check: aether:sun_spirit was removed at ... after 1 tick(s), reason=null, alive=true, health=8500.0
```

**排障时先看这一行**：`survived` = 正常；`was removed` + `alive=true` = 对方模组主动丢弃（第 19.1 节）。



