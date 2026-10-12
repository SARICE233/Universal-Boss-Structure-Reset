// kubejs-examples/server_scripts/01_custom_definition.js
// 用法：把本文件复制到整合包的 kubejs/server_scripts/ 下（文件可以改名）。
// 作用：用脚本在运行时注册自定义结构重置定义 —— 不需要写数据包 JSON。
// 说明：本全局对象 StructureReset 由 mod 的 KubeJS 插件注册（见 docs/KUBEJS.md）。

// 1) 注册一条全新的定义。字段与数据包 JSON 一一对应（见 docs/DATAPACK.md）。
StructureReset.register({
  // id 必须写全，"namespace:path" 两段都不能空
  id: 'mypack:my_dungeon',

  // structure 可以是字符串，也可以是字符串数组；以 "#" 开头表示结构标签
  structure: ['minecraft:stronghold', '#minecraft:village'],

  radius: 96,                 // 触发/作用半径（方块），必须 > 0
  trigger: 'either',          // in_structure | anchor_block | either
  mode: 'per_player',         // global | per_player
  anchor_blocks: ['minecraft:lodestone'],
  cooldown_seconds: 1800,     // 冷却秒数，必须 >= 0
  consume_item: true,         // 触发时是否消耗 required_item
  required_item: 'minecraft:echo_shard',
  priority: 10,               // 同一结构被多条定义命中时，priority 大者优先

  actions: [
    {
      type: 'structurereset:respawn_boss',
      params: {
        entity: 'minecraft:warden',
        count: 1
      }
    },
    {
      type: 'structurereset:refill_loot',
      params: {
        mode: 'empty_only',   // empty_only = 只补空箱子；all = 重填全部
        reroll_seed: true     // 重新随机战利品表种子
      }
    }
  ]
});

// 2) 覆盖同 id 的定义（重复 register 即替换），随后立即生效。
StructureReset.register({
  id: 'mypack:my_dungeon',
  structure: 'minecraft:stronghold',
  radius: 64,
  trigger: 'anchor_block',
  anchor_blocks: ['minecraft:lodestone'],
  cooldown_seconds: 600,
  actions: [
    { type: 'structurereset:respawn_boss', params: { entity: 'minecraft:warden', count: 2 } }
  ]
});

// 3) 列出当前已加载的定义 id（返回字符串数组，方便 /kubejs 脚本里 self-check）。
console.log('[StructureReset] loaded definitions: ' + StructureReset.list().join(', '));

// 4) 手动重新加载：重新读取 data/<ns>/structure_resets/ 下所有 JSON。
//    适合在做完数据包热重载后再跑一次。
StructureReset.reload();
