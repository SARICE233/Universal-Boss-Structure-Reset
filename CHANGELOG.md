# Changelog

本项目遵循「发布日期 + 语义化版本」的简单约定。alpha 之间可能包含破坏性改动。

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
