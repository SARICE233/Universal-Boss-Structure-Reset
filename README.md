# Universal-Boss-Structure-Reset (UBSR)

> 面向 **Forge 1.20.1** 的独立附加模组：使原本一次性的 Boss 结构可重复攻略——重生 Boss、重填战利品。
> A standalone Forge 1.20.1 addon that makes one-shot boss structures repeatable.
>
> 全称 **Universal-Boss-Structure-Reset Mod**，缩写 **UBSR**；模组 id 仍为 `structurereset`（数据包、物品 id 与存档数据均以此为准，未随显示名改变）。

**缘起**：本模组源于本人与朋友游玩整合包时的一个实际困扰——不少 Boss 结构只能攻略一次，此后便再无价值。
它是在 **DeepSeek** 协助下完成的一次尝试，出发点仅是解决自己遇到的问题，能力与精力都有限，
设计与实现未必周全，权当抛砖引玉。
**若有作者愿意做出更完善、更通用的同类模组，那自然是更好的结果，也是玩家之福。**

| 项 | 值 |
| --- | --- |
| 加载器 | Forge 1.20.1（47.x） |
| modid | `structurereset` |
| 前置 | **无**（KubeJS、L_Ender's Cataclysm 均为可选软依赖） |
| 许可 | MIT |
| Mixin | **不使用**（全部通过 Forge 事件与数据驱动实现） |

## 特性

* **数据驱动**：一条重置定义即一份普通数据包 JSON，无需编写 Java 即可扩展内容。
* **游戏内录入**：无需编写 JSON。潜行并手持重置核心右键生物，即可将「结构 ↔ Boss ↔ 容器」记录为一条结构记录。
* **准星指定复活点**：两阶段流程——先瞄准（粒子标示合法 / 偏离 / 非法），再锁定并读条，Boss 生成于指定位置。
* **战利品表保全**：原版在容器被开启后即丢弃其战利品表，届时无法恢复；在开启前以左键抓取即可持久保存。
* **多人服务器语义可选**：`global`（全服共享一次）或 `per_player`（每人独立）。
* **可覆盖性**：结构定义、配方、贴图、模型与物品参数（耐久 / 冷却）均可由数据包、资源包或 KubeJS 覆盖。
* **非侵入式**：不新增结构或生物，仅对已生成的结构、实体与战利品表进行操作。

## 环境要求

* Minecraft **1.20.1**
* Forge **47.x**
* Java **17**
* 客户端与服务端**均需安装**（模组包含物品与贴图资源）

可选依赖：**KubeJS**（脚本注册定义 / 动作 / 事件）、**L_Ender's Cataclysm** 或其他结构模组（仅作为数据包中的标识符引用）。

## 安装

1. 将 `structurereset-<version>.jar` 放入客户端与服务端的 `mods/` 目录。
2. 启动一次游戏，生成配置文件与内置数据包定义。
3. 单人存档及服务器可直接启用；整合包作者建议先阅读下文「使用」。

## 快速上手

1. 将 `structurereset-<version>.jar` 放入客户端与服务端的 `mods/`，启动一次游戏。
2. 进入任意 Boss 结构，**潜行并手持重置核心右键生物**（或右键方块执行结构扫描），完成一次「录入」。
3. 在开启容器前**左键点击**以抓取战利品表，随后进行战斗；战斗结束后于原位置**右键两次**（瞄准 → 读条），
   Boss 重生、容器重填，即可再次攻略。

若希望直接使用现成内容：内置 14 条 Cataclysm 定义无需额外配置，手持核心右键其重生方块即可。

## 使用

### 方式一：数据包定义（整合包作者）

编写 `data/<命名空间>/structure_resets/<id>.json`：

```json
{
  "structure": "cataclysm:burning_arena",
  "radius": 80,
  "trigger": "either",
  "anchor_blocks": ["cataclysm:boss_respawner"],
  "mode": "global",
  "cooldown_seconds": 60,
  "consume_item": true,
  "required_item": "structurereset:structure_reset_core",
  "actions": [
    { "type": "structurereset:respawn_boss", "params": { "entity": "cataclysm:ignis", "count": 1 } },
    { "type": "structurereset:refill_loot", "params": { "mode": "empty_only", "reroll_seed": true } }
  ]
}
```

内置 **14 条 Cataclysm 默认定义**：其中 8 条含 Boss（焰魔、下界合金巨兽、先驱者、利维坦、末影守卫、咒翼灵骸、斯库拉、远古遗魂），
6 条为纯战利品结构。两类均可整条覆盖或按需删除。

**封闭房间型结构**应使用 `reset_blocks` 的映射模式，先恢复封闭状态再召唤 Boss：

```json
{ "type": "structurereset:reset_blocks",
  "mapping": { "aether:carved_stone": "aether:locked_carved_stone" },
  "radius": 32 }
```

完整字段说明见 [docs/DATAPACK.md](docs/DATAPACK.md)。

### 方式二：游戏内录入（玩家）

潜行并手持 `structurereset:structure_reset_core` 右键生物（或右键任意方块执行结构扫描），
模组会将「结构 ↔ Boss ↔ 容器」记录为一条**结构记录**；此后于原位置右键即可按该记录重生 Boss 并重填容器。

### 交互分工（互不重叠）

| 输入 | 行为 |
| --- | --- |
| **右键** | 重置：第一次瞄准，第二次锁定并读条（默认 5 秒） |
| **潜行** + 右键**实体** | 录入：将该生物记录为本结构的 Boss |
| **潜行** + 右键**方块** | 结构扫描：不依赖碰撞箱，适用于九头蛇等难以选中的大型 Boss |
| **左键**点击方块 | 抓取战利品表（须在开启容器前执行，之后无法恢复） |

### Boss 识别方式（按优先级）

1. 将 Boss 加入 **`forge:bosses`** 实体标签（单行数据文件，跨模组通用）；
2. KubeJS `StructureReset.setBossFilter(fn)`（脚本具有最终裁量权）；
3. 配置项 `learningEntityAllowlist` 显式放行；
4. 以上均未配置时，退回血量阈值 `learningMinMaxHealth`（默认 300）。

## 配置

`config/structurereset-common.toml`（每个实例独立，以下为常用项）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `resetRequiresDefeated` | `true` | 结构内存在存活 Boss 时拒绝重置（基于世界状态判定，任何使 Boss 消失的方式均会放行） |
| `aimCastTicks` | `100` | 读条时长（tick，默认 5 秒）；召唤特效时长与之同步 |
| `learningMinMaxHealth` | `300` | 血量回退阈值 |
| `learningRequireOp` | `false` | 公共服务器建议开启：录入属于服务器级操作 |

完整配置说明见 [docs/LEARNING.md](docs/LEARNING.md)。

> **结构记录只由人写入。** 模组不会在跑图时自动把 Boss 绑定到结构上——路人 Boss 一旦被记错，
> 之后就会召唤出错误的 Boss，这比没有记录更糟。因此记录只能来自：潜行右键录入、结构扫描、
> `records teach-here` 指令，或数据包定义。Boss 的生成点追踪仍自动进行，但它不产生任何
> 「结构 → Boss」映射。

## 命令

`/structurereset records list | remove <id> | clear confirm | teach-here <entity>`（权限等级 2）
旧命令名 `learned` 保留为别名。

## KubeJS（可选）

```js
StructureReset.setBossFilter(entity => entity.type.startsWith('mypack:'))
StructureReset.itemSettings('structurereset:structure_reset_core', {
  maxUses: 10, unbreakable: true, cooldownSeconds: 30, teachConsumes: false
})
```

完整绑定见 [docs/KUBEJS.md](docs/KUBEJS.md)。

## 兼容性与已知限制

* **封闭房间型 Boss 无法还原（已知限制，已处理）**：部分模组的 Boss 隶属于其自带的「Boss 房间」机制
  （例：Aether 的太阳之灵挂载于 Nitrogen 的 `BossRoomTracker`）。此类实体被单独召唤后会在 1 tick 内
  自我移除（满血、无掉落）。该状态由对应模组内部维护，通用模组无法访问。
  **本模组行为**：首次观测后记录该 Boss，后续重置不再播放召唤特效，战利品照常重填，
  并在聊天栏给出原因说明。管理员可通过 `saves/<世界>/data/structurereset_unrestorable.dat`
  查看或清除该名单。
* **不使用 Mixin**：对第三方结构的介入仅限结构定位与方块/实体读写层面，无法改写结构自身的生成逻辑。
* **不生成结构**：仅重置已生成的结构；区块卸载或重生成后的结果取决于服务端加载状态。
* **锚点依赖**：默认定义以 `cataclysm:boss_respawner` 为锚点；无可交互锚点的结构将退化为「进入结构即触发」。
* **KubeJS 为软依赖**：未安装时插件发现文件被忽略，模组功能不受影响。
* **服务端权威**：所有判定均在服务端完成，客户端仅负责显示。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/DATAPACK.md](docs/DATAPACK.md) | `structure_resets` 全部字段、内置 action 参数表、可复制示例 |
| [docs/LEARNING.md](docs/LEARNING.md) | 录入流程、选择顺序、配置项与已知限制 |
| [docs/KUBEJS.md](docs/KUBEJS.md) | 插件发现机制、全部绑定与事件 |
| [docs/RESOURCEPACK.md](docs/RESOURCEPACK.md) | 替换贴图、模型与物品外观的步骤 |
| [docs/API.md](docs/API.md) | 公共 Java API 签名与示例 |
| [docs/BUILD.md](docs/BUILD.md) | 构建与离线校验工具链 |

## 许可

MIT，见 [LICENSE](LICENSE)。本模组**不包含**任何第三方模组的代码或资源：
结构、Boss 与战利品表仅以**标识符字符串**引用；物品贴图由本仓库的 `tools/gen_textures.py` 程序化生成。
