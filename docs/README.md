# 文档索引（docs/）

一页一个主题，**每个事实只有一个家**：README 只做玩家向摘要，本目录放细节。
新增内容前先看下面这张表，不要复制已有的表。

| 文档 | 内容 | 谁看 |
| --- | --- | --- |
| [DATAPACK.md](DATAPACK.md) | `structure_resets` 全部字段、7 个内置动作与参数、可复制示例、自带 14 条 Cataclysm 定义的依据 | 整合包作者 |
| [LEARNING.md](LEARNING.md) | 游戏内录入流程、触发与选择顺序、门禁顺序、全部配置项、已知限制与设计取舍 | 玩家 / 管理员 |
| [KUBEJS.md](KUBEJS.md) | 插件发现机制、`StructureReset` / `StructureResetEvents` 全部绑定、示例脚本、排错 | 脚本作者 |
| [RESOURCEPACK.md](RESOURCEPACK.md) | 换贴图 / 模型 / 名字的两种做法、`pack.mcmeta` 与常见坑 | 整合包美术 |
| [API.md](API.md) | 纯逻辑层与核心运行时的公共 Java API、事件与自定义动作 | Java 开发者 |
| [BUILD.md](BUILD.md) | 环境要求、一条命令跑完全部校验、Gradle 发布构建、设计约束 | 贡献者 |

约定：

* 语言：简体中文为主，专业名词保留英文原词（`structure_resets`、`cooldown_seconds`、`SavedData` 等）。
* 校验：`python tools/check_all.py --only docs` 会检查本文档与其他文档是否存在、章节是否齐备、
  代码块是否闭合，以及**每个 `docs/*.md` 是否被 README 或本文件引用**。
* 工具与证据：离线校验入口是 `python tools/check_all.py`（`--list` 只列出检查项）；本仓库不维护重复的
  校验清单，命令只在 [BUILD.md](BUILD.md) 里写一次。
