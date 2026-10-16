# Universal-Boss-Structure-Reset（UBSR）

> 面向 Forge 1.20.1 的独立附加模组：让原本只能攻略一次的 Boss 结构可以重复攻略——Boss 重生、战利品重填。

![Universal-Boss-Structure-Reset 展示图](docs/images/project-logo.png)

## 模组信息

| 项 | 值 |
| --- | --- |
| 加载器 | Forge（`loaderVersion="[47,)"`；开发环境 47.3.22） |
| 支持版本 | Minecraft 1.20.1 / Java 17 |
| modid | `structurereset` |
| 前置依赖 | 无。KubeJS 与 L_Ender's Cataclysm 均为可选软依赖 |
| 许可 | MIT |
| 联机 | 客户端与服务端都需安装；所有判定在服务端完成 |

## 特性

* **数据驱动**：一条重置定义就是一份普通数据包 JSON，不写 Java 也能扩充内容。
* **游戏内录入**：潜行手持结构重置核心右键生物，即可把「结构 ↔ Boss ↔ 容器」记成一条结构记录，无需手写 JSON。
* **准星指定复活点**：先瞄准（粒子标示合法 / 偏离 / 非法），再锁定读条，Boss 生成在指定位置。
* **战利品保全**：原版会在容器被开启后丢弃其战利品表；开启前用核心左键抓取即可持久保存。
* **联机语义可选**：`global`（全服共用冷却）或 `per_player`（每人独立）。
* **可覆盖**：结构定义、配方、贴图、模型与物品参数都能被数据包、资源包或 KubeJS 覆盖。
* **非侵入**：不新增结构或生物，只操作已生成的结构、实体与战利品表。

## 快速上手（安装与依赖）

1. 把 `structurereset-<版本>.jar` 放进客户端与服务端的 `mods/`，启动一次游戏生成配置文件与内置定义；需要 Java 17。
2. 用 4 钻石 + 4 远古残骸 + 1 下界之星合成 `structurereset:structure_reset_core`，或直接使用整合包提供的物品。
3. 进入 Boss 结构后，先用核心左键点容器抓取战利品表，再潜行手持核心右键 Boss 完成录入。
4. 击败 Boss，回到原地右键两次（第一次瞄准、第二次锁定读条）：Boss 重生、战利品重填，可再次攻略。

内置 14 条 L_Ender's Cataclysm 结构定义，装上该模组即可直接使用；其他结构按第 3 步录入，或写数据包定义。

## 配置

主配置为 `config/structurereset-common.toml`；`consumeItem`、`notifyPlayer`、`confirmWindowSeconds` 属于每存档的 SERVER 配置。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关；关闭后除强制重置外一律拒绝 |
| `defaultRadius` | `64.0` | 定义未写 `radius` 时的作用半径（方块） |
| `defaultCooldownSeconds` | `60` | 定义未写 `cooldown_seconds` 时的冷却秒数 |
| `resetRequiresDefeated` | `true` | 结构内仍有存活 Boss 时拒绝重置 |
| `aimCastTicks` | `100` | 锁定后的读条时长（tick，100 = 5 秒） |
| `learningMinMaxHealth` | `300` | 血量兜底阈值；被 Boss 标签 / 白名单 / NBT 键命中时不看血量 |

其余键与完整说明见 [docs/LEARNING.md](docs/LEARNING.md)。

## 命令

权限等级 2（OP）。`learned` 是 `records` 的旧别名，两者等价。

```text
/structurereset records list
/structurereset records remove <id>
/structurereset records clear
/structurereset records clear confirm
/structurereset records teach-here <entity>
```

`clear` 只提示确认、不删除数据；`clear confirm` 才会清空。`teach-here` 由 OP 指定实体 id。

## 兼容性与已知限制

* **封闭房间型 Boss 无法还原**：少数模组 Boss（如 Aether 的太阳之灵）依赖自带房间机制，单独召唤后会在 1 tick 内自我移除。本模组首次观测后记下该 Boss，之后重置不再播放召唤特效、战利品照常重填，并在聊天栏说明原因；名单存于 `saves/<存档>/data/structurereset_unrestorable.dat`。
* **不使用 Mixin**：只做结构定位与方块 / 实体读写，无法改写结构自身的生成逻辑。
* **不生成结构**：只重置已生成的结构；区块卸载或重新生成后的结果取决于服务端加载状态。
* **锚点依赖**：默认定义以 `cataclysm:boss_respawner` 为锚点；没有可交互锚点的结构退化为「进入结构即触发」。
* **KubeJS 为软依赖**：未安装时插件发现文件被忽略，模组功能不受影响。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/DATAPACK.md](docs/DATAPACK.md) | `structure_resets` 全部字段、7 个内置动作与示例 |
| [docs/LEARNING.md](docs/LEARNING.md) | 录入流程、Boss 识别顺序、全部配置项与限制 |
| [docs/KUBEJS.md](docs/KUBEJS.md) | KubeJS 绑定、事件与示例脚本 |
| [docs/RESOURCEPACK.md](docs/RESOURCEPACK.md) | 换贴图 / 模型 / 名字 |
| [docs/API.md](docs/API.md) | Java API、事件与自定义动作 |
| [docs/BUILD.md](docs/BUILD.md) | 环境要求、构建与校验命令 |
| [docs/README.md](docs/README.md) | 全部文档的索引 |

## 许可与授权

MIT，见 [LICENSE](LICENSE)。允许整合包收录、修改与再分发，保留版权与许可声明即可。
本模组不包含任何第三方模组的代码或资源：结构、Boss 与战利品表均以标识符字符串引用，物品贴图为本仓库原创的程序化生成 PNG。
问题反馈与建议请提交到 [GitHub Issues](https://github.com/SARICE233/Universal-Boss-Structure-Reset/issues)。
