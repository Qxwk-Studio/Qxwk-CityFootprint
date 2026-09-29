// 公告与更新日志页脚本：更新日志的「网页端 / 安卓 App」两个选项卡。
// 抽成外部文件是为了配合 CSP（script-src 'self'，内联脚本会被拦）；不挂 on* 属性，
// 统一走 data-action 委托（与 app.js 的 bindActions 同一套约定）。

// 选项卡：点一下就切换；聚焦在 tab 上时左右方向键也在两个 tab 之间移动（WAI-ARIA 的 tabs 约定）。
// 选中态只认 aria-selected（CSS 也写在 [aria-selected="true"] 上），不再另挂 class —— 两处状态早晚会打架。
(function () {
  const tabs = Array.from(document.querySelectorAll('[data-action="log-tab"]'));
  if (!tabs.length) return;
  const panels = Array.from(document.querySelectorAll('.log-panel'));

  function select(tab) {
    tabs.forEach(t => {
      const on = t === tab;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      // 只有选中的那个留在 Tab 键序列里，另一项靠方向键进入（ARIA 的 roving tabindex）
      t.tabIndex = on ? 0 : -1;
    });
    panels.forEach(p => { p.hidden = p.id !== tab.dataset.target; });
  }

  document.addEventListener('click', e => {
    const tab = e.target.closest('[data-action="log-tab"]');
    if (tab) select(tab);
  });

  document.addEventListener('keydown', e => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const i = tabs.indexOf(document.activeElement);
    if (i < 0) return; // 焦点不在选项卡上，不抢方向键
    e.preventDefault();
    const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
    next.focus();
    select(next);
  });
})();