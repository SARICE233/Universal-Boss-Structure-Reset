// kubejs-examples/server_scripts/06_learned_anchors.js
// 用法：复制到整合包的 kubejs/server_scripts/。
// 作用：查看 / 删除「学习（教学）」记录 —— StructureReset.learned() 与 StructureReset.forget(id)。
//
// 重要：**教学只能在游戏里做** —— 玩家潜行（Shift）并手持 structurereset:structure_reset_core
//       右键一个 Mob，服务端才会把「结构 <-> Boss <-> 箱子」写成一条学习记录；
//       或者用命令 /structurereset learned teach-here（权限等级 2）教最近的生物。
//       KubeJS 侧没有任何写入接口，脚本只能**查看**与**删除**已有记录。
//       完整说明见 docs/LEARNING.md。

// ------------------------------------------------------------------ 1) 查看记录
// learned() 返回一条记录一个 com.google.gson.JsonObject 的新数组；
// 它是副本，脚本里怎么改都不会写回存档。
const anchors = StructureReset.learned();
console.log('[StructureReset] learned anchors: ' + anchors.length);

for (let i = 0; i < anchors.length; i++) {
  const anchor = anchors[i];
  const id = anchor.get('id').getAsString();
  const dimension = anchor.get('dimension').getAsString();
  const boss = anchor.get('boss_entity').getAsString();
  const containers = anchor.get('containers');   // JsonArray

  console.log('[StructureReset] ' + id + ' | ' + dimension + ' | boss=' + boss
      + ' | containers=' + containers.size()
      + ' | at ' + anchor.get('x').getAsInt() + ' ' + anchor.get('y').getAsInt()
      + ' ' + anchor.get('z').getAsInt());
}

// ------------------------------------------------------------------ 2) 删除记录
// forget(id) 返回布尔：真的删掉了才是 true，id 不存在返回 false。
// 下面删掉本次列出的第一条记录，请按需换成你自己的 id。
const firstId = anchors.length > 0 ? anchors[0].get('id').getAsString() : 'learned:1';
const removed = StructureReset.forget(firstId);

if (removed) {
  console.log('[StructureReset] forgot ' + firstId);
} else {
  console.log('[StructureReset] no learned anchor with id ' + firstId + '（记录不存在）');
}