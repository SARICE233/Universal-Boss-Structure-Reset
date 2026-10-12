# 资源包：替换物品贴图与模型（RESOURCEPACK）

本文件说明如何**不改一行 Java、不改数据包**就换掉本模组物品的外观：
把 `structurereset:structure_reset_core` 换成你自己的贴图、模型和名字。

两种做法都可以，也可以叠加：

| 做法 | 生效范围 | 需要重启游戏 | 适用场景 |
| --- | --- | --- | --- |
| A. 资源包覆盖（推荐） | 仅客户端显示 | 否（F3+T 重载） | 整合包自带美术素材 |
| B. KubeJS `setItemModel` | 仅客户端显示 | 否 | 借用**另一个物品**的模型（按条件切换外观；填模型文件路径无效） |

---

## 1. 先看清本模组默认长什么样

默认资源在 jar 内（开发树里是 `src/main/resources`）：

```text
src/main/resources/assets/structurereset/
├── lang/
│   ├── en_us.json          # { "item.structurereset.structure_reset_core": "Structure Reset Core" }
│   └── zh_cn.json          # { "item.structurereset.structure_reset_core": "结构重置核心" }
├── models/
│   └── item/
│       └── structure_reset_core.json      # { "parent": "minecraft:item/generated", "textures": { "layer0": "structurereset:item/structure_reset_core" } }
└── textures/
    └── item/
        └── structure_reset_core.png       # 16x16 贴图
```

**规则（记住这三条就够）：**

* 物品模型必须放在 `assets/<命名空间>/models/item/<名字>.json`；
* 模型里 `textures.layer0` 指向的 `<命名空间>:item/<名字>` 对应文件
  `assets/<命名空间>/textures/item/<名字>.png`；
* 语言键固定是 `item.<modid>.<物品 id>`，即 `item.structurereset.structure_reset_core`。

---

## 2. 做法 A：资源包覆盖

### 2.1 建目录

在整合包的 `resourcepacks/` 下新建一个文件夹（名字随意，例如 `MyPack`）：

```text
resourcepacks/MyPack/
├── pack.mcmeta
└── assets/
    └── structurereset/                 # 必须与模组同名，才能覆盖它的资源
        ├── lang/
        │   └── zh_cn.json              # 可选：只改中文名
        ├── models/
        │   └── item/
        │       └── structure_reset_core.json
        └── textures/
            └── item/
                └── structure_reset_core.png    # 你自己的 16x16 PNG
```

### 2.2 `pack.mcmeta`

1.20.1 的 `pack_format` 是 **15**：

```json
{
  "pack": {
    "pack_format": 15,
    "description": "MyPack：替换 Universal-Boss-Structure-Reset 核心的外观"
  }
}
```

### 2.3 覆盖贴图

把 `structure_reset_core.png` 换成你的图即可 —— 文件名、路径必须与上面完全一致
（大小写敏感）。模型文件可以完全不写：只要贴图路径一样，原模型继续用它。

### 2.4 覆盖模型

如果你想把物品换成别的形状（例如 3D 模型、或引用原版模型）：

```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "mypack:item/reset_core_gold"
  }
}
```

那么贴图要同时放在 `assets/mypack/textures/item/reset_core_gold.png`。

### 2.5 覆盖名字

```json
{
  "item.structurereset.structure_reset_core": "结构重置核心（整合包版）"
}
```

只写要改的键即可：`lang` 是按键合并的，未列出的键继续用模组自带的。

### 2.6 生效

把 `MyPack` 放进 `resourcepacks/`，在 **选项 → 资源包** 里启用，或直接按 `F3 + T` 重载。

---

## 3. 做法 B：KubeJS 换模型

装了 KubeJS 时，可以在 `kubejs/startup_scripts/` 里直接改：

```javascript
// kubejs/startup_scripts/appearance.js
StructureReset.setItemModel('structurereset:structure_reset_core', 'mypack:item/reset_core_gold');

// 想换回去：
// StructureReset.clearItemModel('structurereset:structure_reset_core');
```

模型与贴图仍然要放在 `kubejs/assets/` 下：

```text
kubejs/assets/mypack/
├── models/item/reset_core_gold.json
└── textures/item/reset_core_gold.png
```

KubeJS 会把 `kubejs/assets/` 当成一个内建资源包加载，因此**不需要** `pack.mcmeta`。
详见 [KUBEJS.md](KUBEJS.md)。

---

## 4. 常见坑

| 现象 | 原因 |
| --- | --- |
| 贴图还是原来的 | 目录不是 `assets/structurereset/...`，或文件名大小写不一致 |
| 紫黑格子 | 模型里的 `layer0` 指向的 PNG 不存在，或 PNG 不是 16x16（也支持其它尺寸，但会被拉伸） |
| 改了名字没生效 | 语言键写错了；必须是 `item.structurereset.structure_reset_core` |
| 整个资源包不生效 | `pack.mcmeta` 的 `pack_format` 不是 15（1.20.1） |
| 多人服上别人看不到 | 资源包是**客户端**资源，服务端装了没用；需要分发给玩家 |

---

## 5. 校验

本仓库自带静态校验，会检查模型引用的贴图是否真的存在：

```bash
python tools/validate.py          # model-refs / mod-items / lang-consistency 规则
```