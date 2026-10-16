# 录入 / 学习（LEARNING）

「学习」让整合包不必先写数据包定义：玩家在游戏里**教**一次，模组就把「结构 ↔ Boss ↔ 容器」记录进世界存档；
之后在同一位置重置时，按记录复活 Boss、重填容器。记录存在主世界的 `SavedData` 里，重启与 `/reload` 都不丢，
但**不随数据包分发**，换存档即消失。

实现入口：`impl.LearnedAnchorService`（录入与匹配）、`api.LearnedAnchorSelector`（无 Minecraft 类型的选择逻辑）、
`impl.LearnedAnchorData`（持久化）、`item.StructureResetItem`（潜行右键 Mob）、`command.StructureResetCommand`。

## 1. 怎么教

1. **潜行**手持 `structurereset:structure_reset_core` 右键目标 Mob（默认 `learningRequiresSneak = true`；
   把它设为 false 后普通右键也走录入分支，见第 7 节）。
2. 服务端依次校验：`learningEnabled` → `learningRequireOp`（权限 < 2 拒绝）→ 目标存活 → 位置门禁
   （第 3 节第 2-3 步）→ 实体分类（第 3 节第 4-10 步）。分类结果只决定回执文案：**显式指定优先**，
   没被任何标签认出的 Mob 照样记录；同一维度、同 Boss 且相距 < 8 格的重复录入会覆盖旧记录并沿用旧 id（第 5 步）。
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
3. 学习库选择顺序（`LearnedAnchorSelector`）：先只看同维度的记录；其中结构 id 与当前位置报告的结构 id
   有交集、**且该记录自身半径覆盖该位置**者，取距离最近的一条（同距按 `learned_at_game_time` 新者胜、
   再按 id 字典序，结果与遍历顺序无关）；一条都没有时才退回到「半径覆盖该位置」的全部记录，同样取距离最近的一条。
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

实体分类（被 KubeJS 脚本过滤器接管时第 5-11 步全部跳过；第 8-10 步决定「自动认出」）：

| # | 判定 | 默认 | 判定给出的消息键 |
| --- | --- | --- | --- |
| 4 | KubeJS `StructureReset.setBossFilter(fn)` 存在 → **它说了算** | 无 | 接受 → `recorded`；不接受（含返回 null 或抛错）→ 未识别 |
| 5 | 命中 `learningEntityAllowlist`（精确 id）→ 接受 | 空 | `recorded` |
| 6 | 命名空间命中 `learningExcludedNamespaces` → 不算 Boss | `["minecraft"]` | `excluded_namespace`（只写日志） |
| 7 | 命中 `learningEntityDenylist` → 不算 Boss | 空 | `denied`（只写日志） |
| 8 | 命中 `learningBossTags` 任一实体标签 → 接受 | `structurereset:known_bosses`、`forge:bosses`、`c:bosses` | `recorded` |
| 9 | 命中 `learningBossNbtKeys` 任一顶层 NBT 键 → 接受 | 空（不启用） | `recorded` |
| 10 | 被可选的 Boss Checklist（`boss_checklist`）id 清单点名 → 接受（**最后一步**，仅装了该模组时才可能命中） | 取决于是否安装 | `recorded` |
| 11 | 以上全部未命中 → 未识别：自动路径一律不算 Boss，只有玩家显式右键才记录 | — | `too_weak`（只写日志；物品路径的实际回执是 `unrecognised`） |

消息键的完整拼写、常量名与触发时机见第 10 节。第 4-11 步只回答「这一只算不算 Boss」，**不构成拒绝理由**：
物品路径与 `teach-here` 都会把未识别的目标照样记录，回执换成第 10 节的 `unrecognised` 警告。

**适配建议**：把 Boss 加进 `forge:bosses` 实体标签（Forge 跨模组约定）：

```json
{ "replace": false, "values": [ "youmod:your_boss" ] }
```

默认剔除 `minecraft:` 命名空间，因为原版生物不可能是要复活的 Boss；`structurereset:known_bosses`
是本模组自带的精选标签，Cataclysm 的 8 个 Boss 就在其中（逐个列出，不依赖其他模组），并放在最前面优先查询。
**在默认配置下实体标签是默认的自动识别依据**：脚本过滤器、白名单、NBT 键与可选的 Boss Checklist
都是额外入口，除此之外本模组不按任何数值兜底猜测；认不出的 Boss 由玩家显式录入（第 4 节）。
刻意不进默认列表的还有「家族标签」——某些 Boss 模组的族群标签或 `minecraft:skeletons` 会同时包含普通小怪，
让结构记错 Boss；要覆盖面的整合包可以自行加入并承担误判风险。

### 3.1 Boss Checklist（可选软依赖，第 10 步）

[Boss Checklist](https://www.curseforge.com/minecraft/mc-mods/boss-checklist)（modId `boss_checklist`）
只提供**实体 id 清单**，没有任何实体标签：服务端数据包文件
`data/boss_checklist/server_bosses_ids.json` 是一个 JSON 数组，每项形如 `{"id": "minecraft:wither"}`。
本模组把它当作**最后一步加分项**，而不是门禁：

* 在 `META-INF/mods.toml` 里声明为 `mandatory=false` 的软依赖（`versionRange="[0,)"`，接受任意已安装版本）；
* **不写进 `build.gradle`、不引用对方的任何类**，所以没装该模组也能正常编译、加载与运行；
* 只有 `ModList.get().isLoaded("boss_checklist")` 为真时才通过**服务端资源管理器**读取该文件，
  读取的是数据包资源 `boss_checklist:server_bosses_ids.json`（不是 classpath 资源）；
* 解析结果缓存在内存里，只在数据包重载（`AddReloadListenerEvent`）与玩家加入同步（`OnDatapackSyncEvent`）
  时刷新，**绝不在每 tick 或每次判定里读盘**，判定只是一次集合查找；
* 成功载入写一行 debug 日志说明载入了多少条 id；文件缺失或格式错误只写**一条** warning（不按实体刷屏），
  然后当作「没有提示」继续走第 11 步——缺文件、坏文件、未安装三者的答案完全一样，都是「不命中」；
* **无需任何配置**：本模组没有为它新增任何配置项或开关，也不需要玩家、整合包或服务端做任何设置——
  装上即生效、不装即跳过，是否安装与清单内容都由对方模组决定；
* **清单为空时**：文件读得动、但数组里没有任何可用条目（空数组，或所有条目都解析不出有效 id）时同样算
  「没有提示」，就识别结果而言与未安装、文件缺失一模一样，照常走第 11 步（debug 日志写明载入了 0 条 id，
  或跳过了几条无效条目）。

清单本身由对方模组决定，版本升级时可能整体重写，因此不要把它的内容当成本模组的稳定配置。

## 4. 输入分工（互不重叠）

| 输入 | 行为 |
| --- | --- |
| 右键（不潜行） | 重置：第一次瞄准、第二次锁定并读条（`learningRequiresSneak = false` 时，右键生物改为录入） |
| **潜行** + 右键**实体** | 录入：只记录这一只；**显式指定优先**，没被任何标签认出也照记，回执换成「`%1$s` 未匹配任何 Boss 标签」的警告（`structurereset.message.learning.unrecognised`），认出的回 `structurereset.message.learning.recorded` |
| **潜行** + 右键**方块** | 结构扫描：按「锚点方块 NBT 里的实体 id」或「结构内通过门禁的存活实体」登记本结构的 Boss，成功回 `structurereset.message.learning.scanned` |
| **潜行** + 右键**方块**（扫描不到候选） | 范围内存活且不算 Boss 的生物 ≥ `NO_BOSS_CROWD`（固定 5 只）时回 `structurereset.message.learning.scan_no_boss`（「这个结构似乎不存在 Boss」）并追加 `structurereset.message.learning.scan_no_boss_hint` 提示显式录入；不足 5 只时回 `structurereset.message.learning.scan_none`（「本结构内没有可记录的 Boss 候选」） |
| 左键点方块 | 抓取战利品索引（第 6 节） |

结构扫描（`LearnedAnchorService.scanStructure`，即上面的潜行右键方块）先过 `learningEnabled` 与 `learningRequireOp`，
再要求玩家确实站在结构里（`structure_ids` 与包围盒都非空），否则回 `structurereset.message.learning.no_structure`。
它的候选只有两种，且都不看玩家瞄向哪里：

1. **方块实体里存着的实体 id**：结构包围盒内方块实体的 NBT 中用实体键（`EntityType` / `entity_type` /
   `Entity` / `entity`，或 `SpawnData.id` 这类路径）保存、且该类型过得了第 3 节分类门禁的 id——「Boss 重生方块 /
   祭坛 / 墓碑」这一类，也是「认不出的 Boss 由玩家显式录入」之外唯一能以类型识别的方式；
2. **结构内存活的实体**：包围盒内活着、过了门禁、又不是扫描者本人的实体，按最大生命值降序登记（身体优先于它的部件）。

选择规则：候选里只要有命中 `learningBossTags` 的一员，它直接胜出；一个都没有时取最先登记的那一个
（方块实体候选先于存活实体登记），并在日志里列出全部候选。命中后回 `structurereset.message.learning.scanned`；
没有任何候选时才回到上面两条「没有候选」的回执。数值只用来给存活候选排序，**不作为 Boss 的判定依据**。

## 5. 命令（权限等级 2）

```text
/structurereset records list                 # 列出全部结构记录
/structurereset records remove <id>          # 删除一条记录
/structurereset records clear                # 只提示确认
/structurereset records clear confirm        # 真正清空并报告条数
/structurereset records teach-here <entity>  # 点名登记，锚点＝执行者站位
```

`teach-here` 只绕过第 3 节的位置门禁（不必身处结构、不必瞄准）；实体分类照常执行，但和物品路径一样**不构成
拒绝理由**——点名即显式指定，没被标签认出时照记并回 `structurereset.message.learning.unrecognised`，
认出时的成功回执是 `structurereset.message.learning.admin_recorded`（记录点读不到结构 id 时内嵌
`structurereset.message.learning.bound_by_radius`）。仍然拒绝的只有：配置关闭（`structurereset.message.learning.disabled`）、
`learningRequireOp` 下权限不足（`structurereset.message.learning.not_op`）、实体 id 解析失败
（`structurereset.message.learning.unknown_entity`）、以及目标根本不是活体生物（物品 / 弹射物 / marker，回
`structurereset.message.learning.too_weak`）。旧命令名 `learned` 保留为别名。

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
| `learningRequiresSneak` | `true` | true 时必须潜行才能录入；false 时普通右键生物也走录入分支 |
| `learningMaxAngleDegrees` | `45` | 允许的视线夹角 |
| `learningExcludedNamespaces` | `["minecraft"]` | 按命名空间整批剔除 |
| `learningEntityAllowlist` / `learningEntityDenylist` | `[]` | 精确放行 / 拒绝 |
| `learningBossTags` | 见第 3 节 | Boss 实体标签：默认配置下的主要自动识别依据（Boss Checklist 是可选的最后一步，见 3.1 节） |
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

## 10. 回执文案与消息键

自动识别的判定本身几乎不产生文案：分类结论只写日志，真正显示给玩家的是下面这些键。译文字符串都在
`src/main/resources/assets/structurereset/lang/zh_cn.json` 与 `en_us.json` 里，常量名在 `impl.LearnedAnchorService`
（扫描自身的异常兜底另有 `item.StructureResetItem.SCAN_FAILED`）。物品路径把文案显示在动作栏（物品栏上方）：
`TeachResult#success() == true` 的键不染红，`false` 的键染红并播放失败音效。

| 消息键 | 常量 | 何时出现 |
| --- | --- | --- |
| `structurereset.message.learning.recorded` | `MESSAGE_RECORDED` | 潜行右键生物且分类认出对方（成功） |
| `structurereset.message.learning.unrecognised` | `MESSAGE_UNRECOGNISED` | **显式指定但分类没认出**：物品路径与 `teach-here` 都回这一条，记录成功但带警告（成功） |
| `structurereset.message.learning.disabled` | `MESSAGE_DISABLED` | `learningEnabled = false` |
| `structurereset.message.learning.not_op` | `MESSAGE_NOT_OP` | `learningRequireOp = true` 且权限 < 2 |
| `structurereset.message.learning.no_structure` | `MESSAGE_NO_STRUCTURE` | 物品路径下玩家不在结构内；扫描时玩家不在结构内也回这一条 |
| `structurereset.message.learning.not_looking` | `MESSAGE_NOT_LOOKING` | 物品路径下没有正对目标 |
| `structurereset.message.learning.failed` | `MESSAGE_FAILED` | 其余无法记录的兜底（目标不存活、不在服务端世界等） |
| `structurereset.message.learning.excluded_namespace` | `MESSAGE_EXCLUDED_NAMESPACE` | 分类判定，**只写日志**，当前没有显示路径 |
| `structurereset.message.learning.denied` | `MESSAGE_DENIED` | 分类判定，**只写日志**，当前没有显示路径 |
| `structurereset.message.learning.too_weak` | `MESSAGE_TOO_WEAK` | 分类判定只写日志；`teach-here` 点名非活体生物时才显示 |
| `structurereset.message.learning.admin_recorded` | `MESSAGE_ADMIN_RECORDED` | `teach-here` 成功且分类认出（成功） |
| `structurereset.message.learning.bound_by_radius` | `MESSAGE_BOUND_BY_RADIUS` | 内嵌在 `admin_recorded` 的「结构」位：记录点读不到结构 id，改按半径绑定 |
| `structurereset.message.learning.unknown_entity` | `MESSAGE_UNKNOWN_ENTITY` | `teach-here` 的实体 id 解析失败 |
| `structurereset.message.learning.scanned` | `MESSAGE_SCANNED` | 结构扫描登记成功（成功） |
| `structurereset.message.learning.scan_none` | `MESSAGE_SCAN_NONE` | 扫描没有候选，且范围内不算 Boss 的活体少于 `NO_BOSS_CROWD`（5）只 |
| `structurereset.message.learning.scan_no_boss` | `MESSAGE_SCAN_NO_BOSS` | 扫描没有候选，但范围内不算 Boss 的活体 ≥ `NO_BOSS_CROWD`（5）只 |
| `structurereset.message.learning.scan_no_boss_hint` | `MESSAGE_SCAN_NO_BOSS_HINT` | 追加在 `scan_no_boss` 后面：走到那只生物旁潜行右键它 |
| `item.structurereset.structure_reset_core.scan.failed` | `StructureResetItem.SCAN_FAILED` | 扫描自身抛异常时的兜底 |

`scan_no_boss` 与 `scan_none` 都是失败的扫描结果（`TeachResult#success() == false`），因此按红色显示并播放失败音效；
`scan_no_boss` 的完整文案是 `scan_no_boss` + 一个空格 + `scan_no_boss_hint`，由 `LearnedAnchorService.scanStructure`
拼好后放进 `TeachResult#message`，所以物品路径显示的是已经带提示的整句。
