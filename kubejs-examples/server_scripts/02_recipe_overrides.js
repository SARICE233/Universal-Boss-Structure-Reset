// kubejs-examples/server_scripts/02_recipe_overrides.js
// 用法：复制到整合包的 kubejs/server_scripts/。
// 作用：用 ServerEvents.recipes 覆盖/删除本模组自带的配方。
//      这里演示三种手法：整体删除、按产物删除、整条重写为 shapeless。

ServerEvents.recipes(event => {
  // 手法 A：按配方 id 删除（配方 id = data/structurereset/recipes/ 下的路径）
  event.remove({ id: 'structurereset:structure_reset_core' });

  // 手法 B：删除所有产物为某物品的配方（不管配方 id 是什么）
  event.remove({ output: 'structurereset:structure_reset_core' });

  // 手法 C：用整合包自己的材料重写这条配方
  event.shaped('structurereset:structure_reset_core', [
    'ABA',
    'BCB',
    'ABA'
  ], {
    A: 'minecraft:amethyst_shard',
    B: 'minecraft:echo_shard',
    C: 'minecraft:nether_star'
  }).id('mypack:structure_reset_core_from_nether_star');

  // 也可以写成无序配方
  event.shapeless('2x structurereset:structure_reset_core', [
    'minecraft:echo_shard',
    'minecraft:diamond_block'
  ]).id('mypack:structure_reset_core_shapeless');

  // 删除某物品作为原料的所有配方（例如不想让某物品参与合成）
  event.remove({ input: 'minecraft:nether_star' });
});
