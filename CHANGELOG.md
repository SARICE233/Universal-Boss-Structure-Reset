# Changelog

本项目遵循「发布日期 + 语义化版本」的简单约定。alpha 之间可能包含破坏性改动。

## 0.1.0-alpha.3 — Boss 识别重做

Boss 识别重做：自动识别只认实体标签，认不出的 Boss 由玩家显式指定。

### 变更

* **移除血量兜底阈值**：脚本过滤器、白名单、实体标签与 NBT 键全部没有命中时，不再按最大生命值猜测 Boss；
  这类目标在自动路径上一律不算 Boss，配置表里原先的阈值项随之删除。
* **扫描会说「这个结构似乎不存在 Boss」**：潜行右键方块扫描结构时，若范围内有 ≥ 5 只普通生物却没有任何候选 Boss，
  回执为「这个结构似乎不存在 Boss」＋「如果你仍想添加一个，请走到那只生物旁并潜行右键它」
  （英文默认文案 This structure appears to contain no boss / if you still want to add one, walk up to that creature
  and sneak + right-click it）；这两句分别是 `structurereset.message.learning.scan_no_boss` 与
  `structurereset.message.learning.scan_no_boss_hint`，触发门槛是 `NO_BOSS_CROWD = 5`；
  结构里确实没有生物时仍回原来的「本结构内没有可记录的 Boss 候选」。
* **显式指定即录入**：潜行右键生物，或 OP 执行 `/structurereset records teach-here <entity>`，都是显式指定；
  目标即使不匹配任何 Boss 标签也会被记为本结构的 Boss，回执换成警告
  「未匹配任何 Boss 标签，仍已将其记录为本结构的 Boss」（`structurereset.message.learning.unrecognised`；
  英文 Warning: … matched no boss tag …）。
* **可选的 Boss Checklist 集成**：装上 `boss_checklist` 时，本模组把它服务端数据包里的 Boss id 清单
  （`data/boss_checklist/server_bosses_ids.json`）当作识别的**最后一步**加分项——在 `mods.toml` 里声明为
  `mandatory=false` 的软依赖（`versionRange="[0,)"`）、不写进 `build.gradle`、不引用对方的类、无需任何配置；
  未安装、文件缺失或清单为空时答案完全一样，识别照常只按实体标签与 NBT 键判定，对方清单里点名的实体照样被接受。

### 文档

* README 配置表删除血量阈值一行，改为列出 `learningBossTags`；快速上手仍为「潜行 + 右键结构内那只生物」。
* `docs/LEARNING.md` 的识别梯子、扫描回执与显式指定路径逐条对齐代码，并列出全部消息键。
* `docs/LEARNING.md` 新增 3.1 节说明可选的 Boss Checklist 集成（读取路径、刷新时机、缺失 / 空清单的退化行为）；
  README 的依赖行把 Boss Checklist 标为可选软依赖。

## 0.1.0-alpha.2 — 修复与文档整理

第二次公开测试。无新增玩法，重点是修正 0.1.0-alpha 的缺陷并收敛文档与工具链。

### 修复

* 42 处审计缺陷（其中 14 处逻辑错误）：战利品表保全、Boss 生成点选取、记录与实体的幽灵绑定、
  锚点查询开销、封闭房间 `reset_blocks` 映射对未知识别符的兜底（原先可能误填空气）等。

### 文档

* README 重写为 mcmod 发布向页面，长表格与 JSON 示例移入 `docs/`；新增展示图 `docs/images/project-logo.png`。
* 文档去重：`docs/` 从 1393 行降到 624 行，每篇只保留自己的主题。

### 工具链

* 18 个离线校验脚本合并为 `python tools/check_all.py`（`--list` 为只读模式），规则未减少。
* 校验脚本改用每次调用独立的临时目录，可在受限沙箱下重复执行；单元测试 165 项全通过。

## 0.1.0-alpha — 首个公开测试版

**Universal-Boss-Structure-Reset Mod（UBSR）** 的第一个 alpha。核心链路已经可用，欢迎反馈。

### 新增

* **数据驱动重置定义**：`data/<命名空间>/structure_resets/<id>.json`，内置 14 条 Cataclysm 默认定义
  （8 条含 Boss、6 条纯战利品）。
* **结构记录（手动）**：潜行 + 右键实体录入、潜行 + 右键方块结构化扫描、`/structurereset records teach-here` 管理员点名、数据包定义。
* **准星指定复活点**：两阶段（瞄准 → 锁定读条，默认 5 秒），无可用位置时给出推荐点。
* **分维度召唤特效**：主世界螺旋粒子 / 下界闪电 / 其它维度「结构自取三种方块环绕 + 旋风」。
* **战利品保全**：左键抓取战利品表（须在开箱前），重置时按索引回放。
* **生成点策略**：`setPersistenceRequired` → `finalizeSpawn` → 房间 API（反射）→ 抬升 + 碰撞盒校验。
* **封闭房间支持**：`reset_blocks` 的 `mapping` 模式（先恢复封锁，再召唤 Boss）。
* **KubeJS 集成**（软依赖）：定义/动作/事件、物品参数覆盖。

### 已知限制

* **封闭房间型 Boss 无法还原**：例如 Aether 的太阳之灵挂载于 Nitrogen 的 `BossRoomTracker`，
  单独召唤后 1 tick 内自我移除。首次观测后会被记入「无法还原」名单，此后只重填战利品并在聊天栏说明。
* **不使用 Mixin**，对第三方结构的介入仅限结构定位与方块/实体读写。
* **结构记录只能由人写入**：模组不会在跑图时自动绑定 Boss（避免把路人 Boss 记成结构 Boss）。

### 环境

Minecraft 1.20.1 · Forge 47.x · Java 17 · 客户端与服务端均需安装。
