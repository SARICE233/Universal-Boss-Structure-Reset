// kubejs-examples/startup_scripts/05_bindings_probe.js
// 用法：复制到整合包的 kubejs/startup_scripts/。
// 作用：一个最小自检片段 —— 确认 mod 的 KubeJS 集成真的被加载了。

if (typeof StructureReset === 'undefined') {
  console.warn('[StructureReset] KubeJS 绑定未加载：请确认已安装 Structure Reset 模组。');
} else {
  console.log('[StructureReset] bindings OK, definitions=' + StructureReset.list().length);
}

if (typeof StructureResetEvents === 'undefined') {
  console.warn('[StructureReset] 事件组未加载：onPreReset/onPostReset 不可用。');
}
