# 数据包：structure_resets 定义（DATAPACK）

本模组的一切都由数据驱动：只要在数据包里放一个 JSON，就多一条"结构重置定义"。

```text
数据包根/
└── data/
    └── <你的命名空间>/                     # 可以是你自己的命名空间，不一定是 structurereset
        └── structure_resets/
            └── <任意层级>/<任意名字>.json    # 文件名/子目录名只影响 id 的路径部分
```

**id 规则**：定义 id = `<命名空间>:<structure_resets 之后的路径去掉 .json>`。
例如 `data/mypack/structure_resets/dungeons/fire_temple.json` 的 id 是
`mypack:dungeons/fire_temple`。KubeJS 里 `StructureReset.remove('mypack:dungeons/fire_temple')`
用的就是这个 id。

---

## 1. 全部字段

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `structure` | 字符串 或 字符串数组 | **是** | — | 命中哪些结构。`#` 开头表示结构**标签**，如 `#minecraft:village` |
| `actions` | 数组 | **是** | — | 至少一条动作，按数组顺序执行；每项必须有 `type` |
| `radius` | 数字 | 否 | `64` | 作用半径（方块）。必须 `> 0` |
| `trigger` | 枚举字符串 | 否 | `either` | `in_structure`（玩家进入结构）/ `anchor_block`（交互锚点方块）/ `either` |
| `anchor_blocks` | 字符串数组 | 否 | `[]` | 触发用的锚点方块 id。`trigger` 含锚点时才有意义 |
| `mode` | 枚举字符串 | 否 | `global` | `global`（全服共用一次冷却）/ `per_player`（每人独立冷却） |
| `cooldown_seconds` | 整数 | 否 | `0` | 冷却秒数，`0` 表示不冷却。必须 `>= 0` |
| `consume_item` | 布尔 | 否 | `true` | 触发时是否消耗 `required_item` |
| `required_item` | 字符串 | 否 | `null` | 触发所需的物品 id；`null` 表示不需要物品 |
| `priority` | 整数 | 否 | `0` | 同一结构被多条定义命中时的优先级，**大者胜** |
| `extra` | 对象 | 否 | — | 自定义数据。未识别的顶层字段也会被原样收进这里，方便整合包留备注 |

> 除 `structure` / `actions` 外全部可选；`_note` 这类下划线开头的字段不是保留字段，
> 会被当成 `extra` 的一部分保留下来（本仓库的 14 条默认定义就用 `_note` 记录考证过程）。

### 字段校验（不满足会拒绝加载该定义并记录日志）

* `structure` 必须存在，且每个引用都得是 `namespace:path`（标签额外带 `#` 前缀）；
* `radius` 必须是数字且 `> 0`；
* `cooldown_seconds` 必须是整数且 `>= 0`；
* `actions` 必须是非空数组，每项是对象且 `type` 是非空字符串；
* `trigger` 只能是 `in_structure` / `anchor_block` / `either`；`mode` 只能是 `global` / `per_player`。

---

## 2. 6 个内置 action 的参数表

动作的 `type` 一律写全 id。`params` 里未列出的键会被忽略（但会保留在 `ResetActionSpec.params()` 里，
自定义动作可以自己读）。

| # | `type` | 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1 | `structurereset:respawn_boss` | `entity` | 字符串 | **必填** | 要重生的实体 id |
| | | `count` | 整数 | `1` | 生成数量 |
| | | `spread` | 数字 | `0.0` | 在原点周围的随机散布半径（0 = 就在原点） |
| 2 | `structurereset:refill_loot` | `mode` | 枚举 | `empty_only` | `empty_only` 只补空容器；`all` 重填全部容器（`all` 隐含重滚） |
| | | `loot_tables` | 字符串数组 | `null` | 只处理这些战利品表；`null` 表示不限 |
| | | `reroll_seed` | 布尔 | `true` | 是否重新随机战利品表种子 |
| 3 | `structurereset:reset_blocks` | `blocks` | 数组 | 与 `mapping` 二选一 | 要改写的方块列表，见下面的子字段 |
| | ⤷ | `pos` | `[x,y,z]` | 与 `absolute_pos` 二选一 | **相对 origin** 的偏移 |
| | ⤷ | `absolute_pos` | `[x,y,z]` | 与 `pos` 二选一 | 世界绝对坐标 |
| | ⤷ | `block` | 字符串 | **必填** | 目标方块 id，如 `minecraft:air` |
| | ⤷ | `keep_nbt` | 布尔 | `false` | 是否保留原来的方块实体 NBT |
| | | `mapping` | 对象 | 与 `blocks` 二选一 | 「解锁态 → 封闭态」的方块 id 映射；把 `radius` 内所有匹配的方块换回封闭态 |
| | | `radius` | 整数 | `32` | `mapping` 模式的扫描半径（上限 64）；只读取**已加载**区块，不触发加载 |
| 4 | `structurereset:clear_entities` | `entities` | 字符串数组 | `null` | 只清理这些实体；`null` 表示按半径清理全部生物 |
| | | `radius` | 数字 | `null` | 覆盖定义级 `radius` |
| | | `include_items` | 布尔 | `false` | 是否连掉落的物品实体一起清理 |
| 5 | `structurereset:run_command` | `command` | 字符串 | **必填** | 不含前导 `/` 的命令，支持占位符 |
| | | `as` | 枚举 | `server` | `server` 以服务端身份执行；`player` 以触发玩家身份执行 |
| | | `permission_level` | 整数 | `2` | `as: server` 时的权限等级 |
| 6 | `structurereset:message` | `text` | 字符串 | **必填** | 要发送的文本，支持占位符 |
| | | `target` | 枚举 | `actor` | `actor` 只发给触发者；`server` 广播给全服 |

### 2.0 `reset_blocks` 的两种模式

**模式一：定点改写**（`blocks`）—— 按坐标列表摆放固定方块，适合修补某个已知的房间缺口。

**模式二：映射改写**（`mapping` + `radius`）—— 把范围内所有「解锁态」方块换回「封闭态」，
用于**封闭房间型结构**：先把房间恢复封锁，再召唤 Boss（顺序很重要）。

```json
{
  "type": "structurereset:reset_blocks",
  "params": {
    "mapping": {
      "aether:carved_stone":        "aether:locked_carved_stone",
      "aether:angelic_stone":       "aether:locked_angelic_stone",
      "aether:light_angelic_stone": "aether:locked_light_angelic_stone",
      "aether:hellfire_stone":      "aether:locked_hellfire_stone",
      "aether:light_hellfire_stone": "aether:locked_light_hellfire_stone",
      "aether:sentry_stone":        "aether:locked_sentry_stone"
    },
    "radius": 32
  }
}
```

`mapping` 与 `blocks` **二选一**；`radius` 仅在 `mapping` 模式下生效，默认 32、上限 64，
且**只扫描已加载区块**，不会触发区块加载。

### 2.1 占位符

`text` / `command` 里可以写 `${键}`，替换规则见 `com.structurereset.api.Placeholders`：

| 键 | 含义 |
| --- | --- |
| `${x}` `${y}` `${z}` | 原点坐标 |
| `${player}` | 触发者名字（非玩家触发时为空） |
| `${structure}` | 命中的结构 id |
| `${definition}` | 定义 id |
| `${dimension}` | 维度 id |
| `${box_min_x}` `${box_min_y}` `${box_min_z}` | 作用范围最小角 |
| `${box_max_x}` `${box_max_y}` `${box_max_z}` | 作用范围最大角 |

未识别的键会**原样保留**（不会变成空串），方便排查拼写错误。

---

## 3. 可复制示例

本仓库的 3 个可直接复制的示例定义（`example_anchor_block.json`、`example_shrine.json`、
`example_tag_based.json`）位于 `examples/datapack/data/structurereset/structure_resets/`。
**它们不会被模组加载，也不会被打包进 jar**（`examples/` 不在 `src/main/resources` 下），
所以只把仓库里的文件放着是不会生效的；想用就把它们**复制进数据包**的
`data/<你的命名空间>/structure_resets/` 下（id 里的命名空间随目录改变）。
下面 3.1–3.3 是同样的内容，可以直接抄进你的数据包。

### 3.1 最小定义：只让 Boss 重生

```json
{
  "structure": "minecraft:stronghold",
  "actions": [
    { "type": "structurereset:respawn_boss", "params": { "entity": "minecraft:ender_dragon" } }
  ]
}
```

### 3.2 锚点触发 + 冷却 + 需要物品（推荐模板）

```json
{
  "structure": "cataclysm:burning_arena",
  "radius": 80,
  "trigger": "anchor_block",
  "anchor_blocks": ["cataclysm:boss_respawner"],
  "mode": "global",
  "cooldown_seconds": 60,
  "consume_item": true,
  "required_item": "structurereset:structure_reset_core",
  "priority": 0,
  "actions": [
    {
      "type": "structurereset:respawn_boss",
      "params": { "entity": "cataclysm:ignis", "count": 1, "spread": 3.0 }
    },
    {
      "type": "structurereset:refill_loot",
      "params": { "mode": "empty_only", "reroll_seed": true }
    }
  ],
  "_note": "整合包备注会被原样保留在 extra 里，不影响加载"
}
```

### 3.3 一人一次（`per_player`）+ 标签命中 + 六个动作各来一次

```json
{
  "structure": ["#minecraft:village", "#minecraft:shipwreck"],
  "radius": 64,
  "trigger": "in_structure",
  "mode": "per_player",
  "cooldown_seconds": 600,
  "priority": 10,
  "actions": [
    {
      "type": "structurereset:message",
      "params": { "text": "${player} 进入了 ${structure}，准备重置…", "target": "actor" }
    },
    {
      "type": "structurereset:clear_entities",
      "params": { "include_items": true, "radius": 48 }
    },
    {
      "type": "structurereset:reset_blocks",
      "params": {
        "blocks": [
          { "pos": [0, 1, 0], "block": "minecraft:air" },
          { "absolute_pos": [100, 64, -200], "block": "minecraft:chest", "keep_nbt": true }
        ]
      }
    },
    {
      "type": "structurereset:respawn_boss",
      "params": { "entity": "minecraft:zombie", "count": 3, "spread": 4.0 }
    },
    {
      "type": "structurereset:refill_loot",
      "params": { "mode": "all", "loot_tables": ["minecraft:chests/village/village_weaponsmith"], "reroll_seed": true }
    },
    {
      "type": "structurereset:run_command",
      "params": { "command": "say ${player} 重置了 ${structure}（${dimension}）", "as": "server", "permission_level": 2 }
    }
  ]
}
```

### 3.4 用 KubeJS 注册同一条定义（等价写法）

```javascript
StructureReset.register({
  id: 'mypack:burning_arena_again',
  structure: 'cataclysm:burning_arena',
  radius: 80,
  trigger: 'anchor_block',
  anchor_blocks: ['cataclysm:boss_respawner'],
  cooldown_seconds: 60,
  mode: 'global',
  actions: [
    { type: 'structurereset:respawn_boss', params: { entity: 'cataclysm:ignis', count: 1 } },
    { type: 'structurereset:refill_loot', params: { mode: 'empty_only', reroll_seed: true } }
  ]
});
```

---

## 4. 加载顺序与优先级

1. 模组自带的 `data/structurereset/structure_resets/**` 先加载（本仓库只有 14 条 Cataclysm
   定义；`examples/` 下的 3 个示例定义不属于自带数据，不随 jar 发布、也不会被加载）。
2. 其它数据包同路径的文件会**整条覆盖**（覆盖是按文件路径，不是按 id 合并）。
3. KubeJS 在服务器脚本阶段调用 `StructureReset.register(...)`，同 id 直接替换。
4. 同一个结构被多条定义命中时：完全匹配优先于标签匹配；同档按 `priority` 降序；
   再相同则按 `id` 的字典序，保证结果稳定可复现。

---

## 5. Cataclysm 14 条默认定义的 Boss 归属证据等级

本仓库自带 14 条针对 Cataclysm 结构的定义，每条都在 `_note` 字段里写明了考证来源。

**结论：全 14 个结构里只有 8 个真的存在 Boss**，而且这 8 个 Boss 与 8 个结构正好一一对应。
判据是三个互相独立的证据源，它们完全一致：

1. **Boss 血条**：实体类里注册了 `CMBossInfoServer` / `ServerBossEvent` 的才是 Boss。
   满足的恰好 8 个：`ignis`、`netherite_monstrosity`、`ender_guardian`、`the_harbinger`、
   `the_leviathan`、`ancient_remnant`、`maledictus`、`scylla`。
2. **结构的 mob 模板池**：`worldgen/template_pool/<structure>/mob/*` 的 `location` 指向哪个实体。
3. **灾变自己写的 `forge:bosses` 实体标签**（`data/forge/tags/entity_types/bosses.json`），
   内容恰是上面那 8 个 id。

### 5.1 有 Boss 的 8 个结构

| 结构 | Boss | 直接证据 |
| --- | --- | --- |
| `cataclysm:ancient_factory` | `cataclysm:the_harbinger` | mob 模板池 `ancient_factory/mob/the_harbinger` |
| `cataclysm:soul_black_smith` | `cataclysm:netherite_monstrosity` | mob 模板池 `soul_black_smith/mob/monstrosity` |
| `cataclysm:acropolis` | `cataclysm:scylla` | mob 模板池 `acropolis/mob/scylla` |
| `cataclysm:burning_arena` | `cataclysm:ignis` | 结构里的 `cataclysm:altar_of_fire`（方块实体 `create(IGNIS)`） |
| `cataclysm:ruined_citadel` | `cataclysm:ender_guardian` | `cataclysm:altar_of_void` |
| `cataclysm:sunken_city` | `cataclysm:the_leviathan` | `cataclysm:altar_of_abyss` |
| `cataclysm:frosted_prison` | `cataclysm:maledictus` | `cataclysm:cursed_tombstone` |
| `cataclysm:cursed_pyramid` | `cataclysm:ancient_remnant` | 结构 nbt 里的 `remnant` 标记 |

### 5.2 没有 Boss 的 6 个结构（本模组只给它们补箱）

| 结构 | 说明 |
| --- | --- |
| `cataclysm:abandoned_spire` | 附属小结构；自身没有 Boss，只有 `mob_connector` 与箱子 |
| `cataclysm:abandoned_temple` | 同上 |
| `cataclysm:abandoned_village` | 同上 |
| `cataclysm:amethyst_nest` | 标志性怪物是紫水晶巨蟹，但它**没有 Boss 血条**，属于小 Boss 一档 |
| `cataclysm:desert_site` | 只有骸龙斗士 / 科博莱顿一档的小怪 |
| `cataclysm:desert_occupied_village` | 同上 |

> **哪些是推断的**：这 6 条「无 Boss」的结论属于**推断**，依据是「该结构会生成的所有实体都没有
> Boss 血条」加上「其 mob 模板池里只出现小 Boss 一档的怪物」，并且灾变源码里没有任何一处
> 为它们创建 Boss。上面有 Boss 的 8 条则是直接证据（血条 / 模板池 / 官方 `forge:bosses` 标签三方一致）。
> 若灾变换了版本或结构布局，请按 `_note` 里的考证方法重新核对，或直接用数据包覆盖成你想要的实体。

这 6 条定义因此**没有 `respawn_boss` 动作、`anchor_blocks` 为空、`trigger` 为 `in_structure`**，
只保留 `refill_loot` —— 它们永远不可能召唤出错的 Boss。
`tools/validate_coverage.py` 把这条规则固化成了构建期校验：无 Boss 结构一旦声明 `respawn_boss`，
构建就会失败。

---

### 5.3 已被搜刮的箱子：战利品索引

`refill_loot` 重填一个容器时，先看它自己是否还带 `LootTable`：带就按原样重填；
**不带（说明已经被玩家开过）** 时，改从**战利品索引**取表——那是玩家手持核心
**左键点方块**时抓下来的记录（见 [docs/LEARNING.md](LEARNING.md) 第 13 节）。

因此想让某条定义的补箱完整，玩家应当在**开箱之前**先左键抓一次；
没抓过的箱子事后无法恢复（结构模板在运行时不可达）。

## 6. 校验

```bash
python tools/validate.py             # structure-resets / recipes / json-parse 等规则
python tools/validate_coverage.py    # 14 条 Cataclysm 定义的覆盖度与字段完整性
```