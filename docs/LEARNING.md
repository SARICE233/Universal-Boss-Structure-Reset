# 录入 / 学习（LEARNING）

「学习」让整合包不必先写数据包定义：玩家在游戏里**教**一次，模组就把「结构 ↔ Boss ↔ 容器」记录进世界存档；
之后在同一位置重置时，按记录复活 Boss、重填容器。记录存在主世界的 `SavedData` 里，重启与 `/reload` 都不丢，
但**不随数据包分发**，换存档即消失。

实现入口：`impl.LearnedAnchorService`（录入与匹配）、`api.LearnedAnchorSelector`（无 Minecraft 类型的选择逻辑）、
`impl.LearnedAnchorData`（持久化）、`item.StructureResetItem`（潜行右键 Mob）、`command.StructureResetCommand`。

## 1. 怎么教

1. **潜行**手持 `structurereset:structure_reset_core` 右键目标 Mob（不潜行的右键原样交回原版交互）。
2. 服务端依次校验 `learningEnabled` → `learningRequireOp`（权限 < 2 拒绝）→ 实体分类（第 3 节的固定顺序）。
3. 锚点取 Mob 所在方块：`structure_ids` 取该位置报告的结构 id 集合，`dimension` 取当前维度。
4. 扫描范围：优先用 `StructureStart` 包围盒（结构自己定义多大），盒子为空时才回落到以锚点为中心、
   边长 `learningRadius`（默认 64）的立方体；只遍历范围内**已加载区块**的容器，只记录**仍带 `LootTable`** 的容器。
5. 同维度、同 Boss、与已有记录直线距离 < 8 格（`DUPLICATE_RADIUS`）→ **覆盖更新**那条记录（沿用旧 id）；
   否则新建 `learned:1`、`learned:2`……

一条记录的形状（`LearnedAnchor#toJson`，`containers` 只列一项）：

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

**必须在箱子被搜刮之前录入。** 原版开箱后即清空容器的 `LootTable`，此后再教只会计入
「无表容器数」（`learning.recorded` 的第 4 个参数），容器补不回来。想连战利品一起保住，
用第 6 节的左键抓取，或在开箱前先录入。

## 2. 触发重置

1. 在原位置附近手持核心右键。
2. `ResetEngine.findDefinition` 先匹配**注册定义**（数据包 `data/<ns>/structure_resets/` 或 KubeJS `register`）；
   一个都匹配不上时才查学习库。
3. 学习库选择顺序（`LearnedAnchorSelector`）：同维度 → 结构 id 有交集者取 `learned_at_game_time` 最新 →
   否则取半径覆盖该位置且距离最近的记录（同距按 recency / id 决胜，结果与遍历顺序无关）。
4. 命中的记录变成一条普通定义：`trigger=EITHER`、`mode=GLOBAL`、`consume_item=true`、
   冷却取 `defaultCooldownSeconds`，只带动作 `structurereset:restore_learned_anchor`；
   冷却、`onPreReset`/`onPostReset` 事件与物品消耗等既有机制照常生效。
5. 回放先核对维度（不符即失败），再按记录复活 Boss，并对每个容器执行 `setLootTable` + `unpackLootTable(null)`；
   未加载或方块已被换掉的容器跳过并写日志，不会中断整次重置。

## 3. 录入门禁的完整顺序

位置门禁（`/structurereset records teach-here` 会绕过这两条）：

| # | 判定 | 默认 |
| --- | --- | --- |
| 1 | 目标是存活的 Mob | — |
| 2 | 玩家身处结构包围盒内（`learningRequireStructure`） | 开 |
| 3 | 玩家正看着目标（夹角 ≤ `learningMaxAngleDegrees`） | 45° |

实体分类（被 KubeJS 脚本过滤器接管时第 4-7 步全部跳过）：

| # | 判定 | 默认 |
| --- | --- | --- |
| 4 | KubeJS `StructureReset.setBossFilter(fn)` 存在 → **它说了算** | 无 |
| 5 | 命中 `learningEntityAllowlist`（精确 id）→ 接受 | 空 |
| 6 | 命名空间命中 `learningExcludedNamespaces` → 拒绝 | `["minecraft"]` |
| 7 | 命中 `learningEntityDenylist` → 拒绝 | 空 |
| 8 | 命中 `learningBossTags` 任一实体标签 → 接受 | `forge:bosses`、`c:bosses`、`structurereset:known_bosses` |
| 9 | `learningMinMaxHealth > 0` 时要求 `getMaxHealth()` ≥ 阈值 | 300（`0` = 不检查） |

**适配建议**：把 Boss 加进 `forge:bosses` 实体标签（Forge 跨模组约定，Cataclysm 的 8 个 Boss 就在里面）：

```json
{ "replace": false, "values": [ "youmod:your_boss" ] }
```

默认剔除 `minecraft:` 命名空间，因为原版生物不可能是要复活的 Boss；`structurereset:known_bosses`
是随包兜底标签。血量阈值只在前面全部没命中时才比较。

## 4. 输入分工（互不重叠）

| 输入 | 行为 |
| --- | --- |
| 右键（不潜行） | 重置：第一次瞄准、第二次锁定并读条 |
| **潜行** + 右键**实体** | 录入：只记录这一只 |
| **潜行** + 右键**方块** | 结构扫描：按「锚点方块 NBT 里的实体 id」或「结构内通过门禁的存活实体」登记本结构的 Boss |
| 左键点方块 | 抓取战利品索引（第 6 节） |

## 5. 命令（权限等级 2）

```text
/structurereset records list                 # 列出全部结构记录
/structurereset records remove <id>          # 删除一条记录
/structurereset records clear                # 只提示确认
/structurereset records clear confirm        # 真正清空并报告条数
/structurereset records teach-here <entity>  # 点名登记，锚点＝执行者站位
```

`teach-here` 只绕过第 3 节的位置门禁（不必身处结构、不必瞄准），实体分类照常执行；
实体 id 解析失败回 `structurereset.message.learning.unknown_entity`。旧命令名 `learned` 保留为别名。

## 6. 战利品索引（左键抓取）

左键点方块会扫描当前结构范围内**当时仍带 `LootTable`** 的容器，把坐标、战利品表与种子记进存档的
战利品索引（`structurereset_loot_index`）；不消耗耐久、不加冷却、可重复执行且幂等。两个使用者：

* `refill_loot`：容器自己还有表就按原样重填，没有表（已被搜刮）时改从索引取表；
* `restore_learned_anchor`：记录里没有任何容器快照时，改用记录点半径内的索引重填。

因此推荐顺序是：**进结构 → 先左键抓一下 → 开箱 / 打 Boss → 之后照常重置**。

## 7. 配置项（`config/structurereset-common.toml`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `learningEnabled` | `true` | 录入总开关 |
| `learningRadius` | `64` | 无结构包围盒时的容器扫描半径（1..512） |
| `learningRequireOp` | `false` | true 时只有权限等级 ≥ 2 的玩家能录入 |
| `learningRequireStructure` | `true` | 录入必须身处结构内 |
| `learningMaxAngleDegrees` | `45` | 允许的视线夹角 |
| `learningExcludedNamespaces` | `["minecraft"]` | 按命名空间整批剔除 |
| `learningEntityAllowlist` / `learningEntityDenylist` | `[]` | 精确放行 / 拒绝 |
| `learningBossTags` | 见第 3 节 | Boss 实体标签，优先于血量阈值 |
| `learningMinMaxHealth` | `300` | 血量兜底阈值，`0` = 不检查 |
| `learningBossNbtKeys` | `[]` | 可选的 NBT 键加分项 |
| `resetRequiresDefeated` | `true` | 结构包围盒（外扩 16 格）内还有**存活且过门禁**的 Boss 时拒绝重置，并点名是哪只 |
| `aimRequireInsideStructure` | `true` | 准星指定点必须在结构包围盒内 |
| `aimWindowTicks` / `aimCastTicks` | `600` / `100` | 瞄准窗口 / 锁定读条（tick，100 tick = 5 秒） |
| `aimReach` | `20` | 准星射线距离（格） |

## 8. 已知限制与设计取舍

* **箱子只能抓到还没被搜刮的**：只有仍带 `LootTable` 的容器会被记录。**模板回溯（从 jigsaw 模板反推箱子
  原始战利品表）本轮不做**，因为 `SinglePoolElement#template` 与 `StructureTemplate#filterBlocks` 都是
  `protected`，要读只能上 access transformer 或反射，都改变加载期契约。这是已知能力缺口，不是 bug。
* **手工塞满的容器不会重复生成战利品**：回放只做 `setLootTable` + `unpackLootTable(null)`，从不清空容器。
* **两个 Boss 相距不到 8 格会互相覆盖**；结构记录一律按 `GLOBAL` + `EITHER` 回放，没有 per-player 版本。
* **没有「事件自学习」**：早期 `autoLearnFromEvents` 已移除且不会恢复——跑图时任何通过门禁的实体都会被误记为
  本结构的 Boss，先写者胜，错了就会召唤出错误的 Boss。**每份结构记录都必须由人产生**：潜行右键实体 /
  潜行右键方块 / `teach-here` / 数据包定义。Boss 注册表（生成点与「已击败」信息）仍然自动记录，但它不产生映射。
* **准星指定复活点**：玩家亲眼看着 Boss 倒下，比算法推断可靠。两阶段——瞄准（绿=合法、黄=偏离原始生成点
  超过 32 格、红=结构外或站不住；不生成、不消耗、不进冷却）→ 锁定 + 读条 `aimCastTicks`（服务端 boss bar，
  按维度播放召唤特效）。准星无可用位置时回落到推荐算法：以 Boss 原生成点 A、容器均值 B、玩家站位 C
  三点做三角形采样，逐级降级到加权重心、结构包围盒中心，再经 `findSafeSpawnPos` 找可站立点，仍失败则取消并提示。
  这套算术在纯逻辑层 `api.RespawnPlanner`（有 15 个单测）。
* **「无法还原」名单**：少数 Boss 的存在条件由对方模组内部维护（例：Aether 太阳之灵挂载于 Nitrogen 的
  `BossRoomTracker`，被单独召唤后 1 tick 内自我移除）。模组**不假装成功**：把这类 Boss 记进
  `saves/<世界>/data/structurereset_unrestorable.dat`，之后只重填战利品并说明原因，管理员删除该文件即可重试。
  每次召唤后 100 tick 会写一行 `spawn check:` 日志，`was removed` + `alive=true` 即属此类。

## 9. KubeJS

学习库只对外提供**查看**与**删除**（`StructureReset.learned()` / `forget(id)`），没有写入入口；
完整绑定见 [KUBEJS.md](KUBEJS.md)。
