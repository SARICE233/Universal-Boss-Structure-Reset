// kubejs-examples/server_scripts/03_events_and_actions.js
// 用法：复制到整合包的 kubejs/server_scripts/。
// 作用：注册自定义 action、用 onPreReset 做权限检查、用 cancel() 拦下重置。
//
// 说明：StructureReset / StructureResetEvents 这两个全局对象由本模组的 KubeJS 插件注册，
//       见 docs/KUBEJS.md。属性名与 Java 访问器一一对应（Rhino 的 JavaBean 规则）：
//       context.player -> getPlayer()、event.definition -> getDefinition()……

// ---------------------------------------------------------------- 1) 自定义动作
// 注册后用 { "type": "mypack:announce", "params": { "text": "..." } } 调用。
StructureReset.registerAction('mypack:announce', context => {
  // context.params 是当前动作的 params 对象（com.google.gson.JsonObject）
  const params = context.params;
  const text = params.has('text') ? params.get('text').getAsString() : '结构已重置';

  if (context.player) {
    context.player.tell('§e' + text);
  }
  console.log('[mypack:announce] ' + (context.definition ? context.definition.id : '?')
      + ' @ ' + context.origin + ' -> ' + text);

  // 想让这次动作被判定为失败，调用 context.cancel()
});

// ---------------------------------------------------------------- 2) 前置监听
// 可以做权限/条件检查，并用 cancel() 中止本次重置。
StructureResetEvents.onPreReset(event => {
  // 只有 OP（权限等级 >= 2）才允许触发"需要物品"的重置
  const player = event.player;
  const required = event.definition ? event.definition.requiredItem : null;

  if (required && !(player && player.hasPermissions(2))) {
    event.setMessage('§c你需要管理员权限才能重置该结构');
    event.cancel();   // 中止：KubeJS 会抛 EventExit 结束这个回调
    return;
  }

  // 例：禁止在下界重置
  if (event.level && String(event.level.dimension.location) === 'minecraft:the_nether') {
    event.setMessage('§c下界不允许重置');
    event.cancel();
  }
});

// ---------------------------------------------------------------- 3) 后置监听
// 重置完成后做善后（广播、统计、刷新 GUI 等）。
StructureResetEvents.onPostReset(event => {
  console.log('[StructureReset] ' + event.structure
      + ' success=' + event.success
      + ' actionsRun=' + event.actionsRun
      + ' box=' + event.box);
  if (event.message) {
    console.log('[StructureReset] message: ' + event.message);
  }
});

// ---------------------------------------------------------------- 4) 动态增删
StructureReset.remove('mypack:my_dungeon');
console.log('[StructureReset] definitions after remove: ' + StructureReset.list().length);

// 手动重跑一次原版 /reload，让数据包里的 structure_resets 变更立即生效
StructureReset.reload();
