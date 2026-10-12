// kubejs-examples/startup_scripts/04_item_appearance.js
// 用法：复制到整合包的 kubejs/startup_scripts/。
// 作用：改物品显示名/描述与模型。
//
// 关于 KubeJS 2001.6.5-build.26 的实测签名（javap 自真实 jar，见 docs/KUBEJS.md）：
//
//   dev.latvian.mods.kubejs.item.ItemModificationEventJS
//     public void modify(net.minecraft.world.item.crafting.Ingredient,
//                        java.util.function.Consumer<net.minecraft.world.item.Item>);
//
// 回调拿到的是**真实的 Item 实例**，不是可写的"物品构建器"，所以 1.20.1 里
// 显示名/描述应通过语言文件覆盖，贴图/模型用 StructureReset.setItemModel 切换。

ItemEvents.modification(event => {
  // 在启动阶段回调一次，日志里能确认这个物品确实存在
  event.modify('structurereset:structure_reset_core', item => {
    console.info('[appearance] item modification hook: ' + item);
  });
});

// 换展示模型：第二个参数必须是**另一个已注册物品的 id**——模组是借用那个物品
// 已经烘焙好的物品模型（模型表里的键是「物品 id + #inventory」），所以直接写模型文件
// 路径（如 mypack:item/reset_core_gold）是**不会被接受**的，只会打一条警告。
// StructureReset.setItemModel('structurereset:structure_reset_core', 'minecraft:clock');
//
// 想真正换**贴图**，有两条正常路子（详见 docs/RESOURCEPACK.md）：
//   1) 资源包替换 assets/structurereset/textures/item/structure_reset_core.png（连同 .mcmeta）；
//   2) 资源包替换 models/item/structure_reset_core.json，加一个 custom_model_data 覆盖。

// 撤销模型覆盖、恢复模组自带模型：
// StructureReset.clearItemModel('structurereset:structure_reset_core');

// 读取当前覆盖（没有时返回 null）：
// console.info('model = ' + StructureReset.itemModel('structurereset:structure_reset_core'));

/*
  显示名与描述：写 kubejs/assets/structurereset/lang/zh_cn.json
  （KubeJS 会把 kubejs/assets/ 当成内建资源包加载，不需要 pack.mcmeta）：

  {
    "item.structurereset.structure_reset_core": "结构重置核心·改",
    "item.structurereset.structure_reset_core.desc": "右键结构锚点即可重置"
  }

  贴图替换的完整文件树见 docs/RESOURCEPACK.md。
*/
