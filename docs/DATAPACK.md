# 数据包：structure_resets 定义（DATAPACK）

本模组的一切都由数据驱动：在数据包里放一个 JSON，就多一条「结构重置定义」。

```text
数据包根/
└── data/
    └── <你的命名空间>/
        └── structure_resets/
            └── <任意层级>/<任意名字>.json
```

**id 规则**：定义 id = `<命名空间>:<structure_resets 之后的路径去掉 .json>`。例如
`data/mypack/structure_resets/dungeons/fire_temple.json` 的 id 是 `mypack:dungeons/fire_temple`。

## 1. 全部字段

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `structure` | 字符串 / 字符串数组 | **是** | — | 命中哪些结构；`#` 开头表示结构**标签**，如 `#minecraft:village` |
| `actions` | 数组 | **是** | — | 至少一条动作，按数组顺序执行；每项必须有 `type` |
| `radius` | 数字 | 否 | `64` | 作用半径（方块），必须 `> 0` |
| `trigger` | 枚举 | 否 | `either` | `in_structure` / `anchor_block` / `either` |
| `anchor_blocks` | 字符串数组 | 否 | `[]` | 触发用的锚点方块 id |
| `mode` | 枚举 | 否 | `global` | `global`（全服共用冷却）/ `per_player`（每人独立） |
| `cooldown_seconds` | 整数 | 否 | 配置 `defaultCooldownSeconds`（60） | `>= 0`；不写时数据包加载器补配置默认值 |
| `consume_item` | 布尔 | 否 | `true` | 触发时是否消耗 `required_item` |
| `required_item` | 字符串 | 否 | `null` | 触发所需物品；`null` 表示不需要 |
| `priority` | 整数 | 否 | `0` | 多条定义命中同一结构时**大者胜** |
| `extra` | 对象 | 否 | — | 自定义数据；未识别的顶层字段（如 `_note`）原样收进这里 |

字段校验不通过会**拒绝加载该定义并记录日志**：`structure` 必须存在且每个引用都是 `namespace:path`；
`radius > 0`；`cooldown_seconds` 为非负整数；`actions` 非空且每项含非空字符串 `type`；
`trigger` / `mode` 只能取上述枚举值。

## 2. 7 个内置 action

动作的 `type` 一律写全 id。`params` 里未列出的键会被忽略（但保留在 `ResetActionSpec.params()` 里）。

| `type` | 参数 | 默认 | 说明 |
| --- | --- | --- | --- |
| `respawn_boss` | `entity` | **必填** | 要重生的实体 id |
| | `count` / `spread` | `1` / `0.0` | 生成数量 / 原点附近随机散布半径 |
| `refill_loot` | `mode` | `empty_only` | `empty_only` 只补空容器；`all` 重填全部（隐含重滚） |
| | `loot_tables` / `reroll_seed` | `null` / `true` | 只处理这些战利品表 / 是否重随机种子 |
| `reset_blocks` | `blocks` 或 `mapping` | 二选一 | 定点改写或「解锁态 → 封闭态」映射（见 2.1） |
| | ⤷ `pos` / `absolute_pos` | 二选一 | 相对 origin 的偏移 / 世界绝对坐标 |
| | ⤷ `block` / `keep_nbt` | **必填** / `false` | 目标方块 id / 是否保留方块实体 NBT |
| | `radius` | `32` | 仅 `mapping` 模式，上限 32，只读已加载区块 |
| `clear_entities` | `entities` / `radius` / `include_items` | `null` / `null` / `false` | 只清理这些实体 / 覆盖定义级半径 / 连掉落物一起清理 |
| `run_command` | `command` | **必填** | 不含前导 `/`，支持占位符 |
| | `as` / `permission_level` | `server` / `2` | 以服务端或触发玩家身份执行 / 服务端身份时的权限等级 |
| `message` | `text` | **必填** | 文本，支持占位符 |
| | `target` | `actor` | `actor` 只发给触发者；`server` 广播 |
| `restore_learned_anchor` | `anchor_id` | **必填** | 要回放的结构记录 id（维度不符 / id 不存在 / 实体解析失败都会失败） |

### 2.1 `reset_blocks` 的两种模式

**定点改写**（`blocks`）：按坐标列表摆放固定方块，适合修补已知的房间缺口。

**映射改写**（`mapping` + `radius`）：把范围内所有「解锁态」方块换回「封闭态」，
用于**封闭房间型结构**——先恢复封锁，再召唤 Boss（顺序很重要）。半径 32 意味着最多
65×65×65 ≈ 27 万次读取，所以上限就是 32，且只扫描已加载区块。

```json
{ "type": "structurereset:reset_blocks",
  "params": { "mapping": { "aether:carved_stone": "aether:locked_carved_stone" }, "radius": 32 } }
```

### 2.2 占位符

`text` / `command` 里可以写 `${键}`（`com.structurereset.api.Placeholders`）：`${x}` `${y}` `${z}` 原点坐标、
`${player}` 触发者、`${structure}` 命中的结构 id、`${definition}` 定义 id、`${dimension}` 维度 id、
`${box_min_x}`…`${box_max_z}` 作用范围的角。未识别的键**原样保留**，便于排查拼写错误。

## 3. 可复制示例

仓库里的 3 个示例（`examples/datapack/data/structurereset/structure_resets/`）**不会被加载、也不进 jar**
（`examples/` 不在 `src/main/resources` 下）；要用就复制进你的数据包。下面两个可直接抄。

最小定义（只让 Boss 重生）：

```json
{
  "structure": "minecraft:stronghold",
  "actions": [
    { "type": "structurereset:respawn_boss", "params": { "entity": "minecraft:ender_dragon" } }
  ]
}
```

推荐模板（锚点触发 + 冷却 + 需要物品）：

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
  "actions": [
    { "type": "structurereset:respawn_boss", "params": { "entity": "cataclysm:ignis", "count": 1, "spread": 3.0 } },
    { "type": "structurereset:refill_loot", "params": { "mode": "empty_only", "reroll_seed": true } }
  ],
  "_note": "整合包备注会被原样保留在 extra 里，不影响加载"
}
```

用 KubeJS 注册同一条定义是等价写法，见 [KUBEJS.md](KUBEJS.md) 第 3.1 节。

## 4. 加载顺序与优先级

1. 模组自带的 `data/structurereset/structure_resets/**`（14 条 Cataclysm 定义）先加载；
2. 其它数据包同路径文件**整条覆盖**（按文件路径覆盖，不按 id 合并）；
3. KubeJS 在服务器脚本阶段 `StructureReset.register(...)`，同 id 直接替换；
4. 多条定义命中同一结构时：完全匹配优先于标签匹配 → `priority` 降序 → `id` 字典序（结果稳定可复现）。

## 5. 自带的 14 条 Cataclysm 定义

每条都在 `_note` 里写明考证来源。结论：**14 个结构里只有 8 个真的存在 Boss**，
三个互相独立的证据源（Boss 血条 / 结构的 mob 模板池 / 灾变自己的 `forge:bosses` 标签）完全一致。

| 结构 | Boss |
| --- | --- |
| `cataclysm:ancient_factory` | `cataclysm:the_harbinger` |
| `cataclysm:soul_black_smith` | `cataclysm:netherite_monstrosity` |
| `cataclysm:acropolis` | `cataclysm:scylla` |
| `cataclysm:burning_arena` | `cataclysm:ignis` |
| `cataclysm:ruined_citadel` | `cataclysm:ender_guardian` |
| `cataclysm:sunken_city` | `cataclysm:the_leviathan` |
| `cataclysm:frosted_prison` | `cataclysm:maledictus` |
| `cataclysm:cursed_pyramid` | `cataclysm:ancient_remnant` |
| `abandoned_spire` / `abandoned_temple` / `abandoned_village` / `amethyst_nest` / `desert_site` / `desert_occupied_village` | 无 Boss（**推断**）：只补箱，不召唤 |

后 6 条因此没有 `respawn_boss` 动作、`anchor_blocks` 为空、`trigger` 为 `in_structure`，只保留 `refill_loot`，
永远不可能召唤出错的 Boss；这条规则由 `coverage` 检查固化。灾变换版本后请按 `_note` 的方法重新核对。

已被搜刮的箱子由**战利品索引**兜底：`refill_loot` 先看容器是否还带 `LootTable`，不带（玩家开过）
就从索引取表——那是手持核心**左键点方块**时抓下来的记录（见 [LEARNING.md](LEARNING.md) 第 6 节）。

## 6. 校验

```text
python tools/check_all.py --only resources,coverage   # json-parse / structure_resets / 14 条定义的覆盖度
python tools/check_all.py                             # 全部 14 项检查
```
