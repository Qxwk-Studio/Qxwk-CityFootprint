/* 主题切换（全站共用一份）
   localStorage('theme') 优先，其次跟随系统 prefers-color-scheme；同步写入 <html> 的 data-theme 防闪烁。

   原先 index / account / visits / stats / news 五个页面各自内联一份完全相同的脚本，
   改一处要记得改五处；现在各页在 <head> 里用 <script src="theme.js"></script> 引入。
   必须在 <head>、body 渲染之前执行（外部脚本同样阻塞解析，时机与原来的内联版一致），
   否则会先按浅色渲染再切深色，闪一下。

   setup.html 是独立落地页，刻意不引任何外部文件，自带一份同逻辑的脚本，没跟着抽。 */
(function () {
  var KEY = 'theme';
  var root = document.documentElement;
  var mq = window.matchMedia('(prefers-color-scheme: dark)');
  function system() { return mq.matches ? 'dark' : 'light'; }
  function stored() { try { var v = localStorage.getItem(KEY); return (v === 'dark' || v === 'light') ? v : null; } catch (e) { return null; } }
  function apply(t) { root.setAttribute('data-theme', t); }
  function current() { return stored() || system(); }
  apply(current());
  function toggle() { var next = current() === 'dark' ? 'light' : 'dark'; apply(next); try { localStorage.setItem(KEY, next); } catch (e) {} }
  // 按钮轮询绑定（不依赖 DOMContentLoaded，避免被外部脚本阻塞导致按钮长时间无效）
  (function tryBind() {
    var btn = document.getElementById('themeToggle');
    if (btn) btn.addEventListener('click', toggle);
    else setTimeout(tryBind, 50);
  })();
  if (mq.addEventListener) mq.addEventListener('change', function (e) { if (!stored()) apply(e.matches ? 'dark' : 'light'); });
  else if (mq.addListener) mq.addListener(function (e) { if (!stored()) apply(e.matches ? 'dark' : 'light'); });
  window.addEventListener('storage', function (e) { if (e.key === KEY) apply(current()); });
})();