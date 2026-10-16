# 资源包：替换物品贴图与模型（RESOURCEPACK）

不改一行 Java、不改数据包，就能换掉物品 `structurereset:structure_reset_core` 的外观：

| 做法 | 生效范围 | 重启 | 适用场景 |
| --- | --- | --- | --- |
| A. 资源包覆盖（推荐） | 仅客户端显示 | 否（F3+T 重载） | 整合包自带美术素材 |
| B. KubeJS `setItemModel` | 仅客户端显示 | 否 | 借用**另一个物品**已烘焙的模型（填模型文件路径无效） |

## 1. 默认长什么样

默认资源在 `src/main/resources/assets/structurereset/`：

```text
assets/structurereset/
├── lang/en_us.json / zh_cn.json   # "item.structurereset.structure_reset_core"
├── models/item/structure_reset_core.json
└── textures/item/structure_reset_core.png      # 16x16
```

三条规则：物品模型必须放在 `assets/<命名空间>/models/item/<名字>.json`；模型里 `textures.layer0` 的
`<命名空间>:item/<名字>` 对应 `assets/<命名空间>/textures/item/<名字>.png`；语言键固定是
`item.<modid>.<物品 id>`。

## 2. 做法 A：资源包覆盖

在整合包的 `resourcepacks/` 下新建一个文件夹（如 `MyPack`）：

```text
resourcepacks/MyPack/
├── pack.mcmeta
└── assets/structurereset/            # 必须与模组同名才能覆盖
    ├── lang/zh_cn.json               # 可选
    ├── models/item/structure_reset_core.json
    └── textures/item/structure_reset_core.png
```

1.20.1 的 `pack_format` 是 **15**：

```json
{ "pack": { "pack_format": 15, "description": "MyPack：替换结构重置核心的外观" } }
```

只换贴图时**不必写模型文件**：路径与文件名（大小写敏感）一致即可。要换形状就覆盖模型：

```json
{ "parent": "minecraft:item/generated", "textures": { "layer0": "mypack:item/reset_core_gold" } }
```

此时贴图要放在 `assets/mypack/textures/item/reset_core_gold.png`。改名字只写要改的键
（`lang` 按键合并，未列出的继续用模组自带值）：

```json
{ "item.structurereset.structure_reset_core": "结构重置核心（整合包版）" }
```

把 `MyPack` 放进 `resourcepacks/`，在**选项 → 资源包**启用，或按 `F3 + T` 重载。

## 3. 做法 B：KubeJS 换模型

```javascript
// kubejs/startup_scripts/appearance.js
// 第二个参数是「另一个已注册物品的 id」——借用它已烘焙的模型；
// 填模型文件路径（mypack:item/reset_core_gold）会直接返回 false。
StructureReset.setItemModel('structurereset:structure_reset_core', 'minecraft:clock');
// StructureReset.clearItemModel('structurereset:structure_reset_core');
```

想用自己的模型 / 贴图，就把它们放进 `kubejs/assets/structurereset/`（KubeJS 把它当内建资源包，
**不需要** `pack.mcmeta`）：

```text
kubejs/assets/structurereset/models/item/structure_reset_core.json
kubejs/assets/structurereset/textures/item/structure_reset_core.png
```

详见 [KUBEJS.md](KUBEJS.md) 第 5 节。

## 4. 常见坑与校验

| 现象 | 原因 |
| --- | --- |
| 贴图还是原来的 | 目录不是 `assets/structurereset/...`，或文件名大小写不一致 |
| 紫黑格子 | `layer0` 指向的 PNG 不存在（尺寸不符只会被拉伸） |
| 改了名字没生效 | 语言键必须是 `item.structurereset.structure_reset_core` |
| 整个资源包不生效 | `pack.mcmeta` 的 `pack_format` 不是 15 |
| 多人服上别人看不到 | 资源包是**客户端**资源，要分发给玩家 |

```text
python tools/check_all.py --only resources,appearance   # 模型引用的贴图是否存在、贴图是否为纯色占位
```
