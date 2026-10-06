// 个人中心页脚本，原先内联在 account.html 里。抽出是为了配合 CSP（script-src 'self'，内联脚本会被拦）。
// 依赖顺序：app.js 先，本文件最后。

// 网页端当前版本号（硬编码）：本站是纯静态页、没有版本接口，「关于软件」卡片只把它作为
// 一行文字展示。发版时改这里，并同步 docs/news.html「网页端」更新日志最新一条的 timeline-tag。
const APP_VERSION = '1.3.3';
// 登录表单：直接跨域调通行证 /api/login（app.js 的 passportLogin），成功后本地已存好 token，直接进个人中心
document.getElementById('loginForm').addEventListener('submit', async function (e) {
  e.preventDefault();
  const account = document.getElementById('loginAccount').value.trim();
  const password = document.getElementById('loginPassword').value;
  const errEl = document.getElementById('loginError');
  const btn = document.getElementById('loginSubmitBtn');
  const accountEl = document.getElementById('loginAccount');
  const pwEl = document.getElementById('loginPassword');
  const agreeEl = document.getElementById('agreeTerms');

  function fail(msg) {
    errEl.textContent = msg;
    errEl.style.display = '';
  }
  errEl.style.display = 'none';
  if (!account || !password) return fail('请填写昵称/邮箱和密码');
  // 没勾协议就不发请求（与 App 登录页同一口径）：这是本站自己的前置条件，
  // 通行证那边并不知道有这份协议，所以只能拦在这里
  if (!agreeEl.checked) return fail('请先阅读并同意《用户协议》');

  // 提交期间禁用控件，避免连点发出多次登录（通行证侧有失败限流，重复请求没意义）
  btn.disabled = true;
  accountEl.disabled = true;
  pwEl.disabled = true;
  btn.textContent = '登录中…';
  try {
    await passportLogin(account, password);
    // 本地已存好 token；再拉一次本站 /api/me 拿本地用户信息（本地 id / 昵称 / 颜色 / 注册时间）并进入个人中心。
    // 首次登录时本地那一行也是在这次 /api/me 里建的（后端 resolveViewer）
    onMeLoaded(await api('/me'));
  } catch (err) {
    fail(err && err.message ? err.message : '登录失败，请稍后重试');
  } finally {
    btn.disabled = false;
    accountEl.disabled = false;
    pwEl.disabled = false;
    btn.textContent = '登录';
  }
});

// 展示未登录界面（无会话，或会话校验失败）
function showAuthView() {
  document.getElementById('loadingView').style.display = 'none';
  document.getElementById('authView').style.display = '';
  document.body.classList.remove('no-scroll');
}

function enterUserView() {
  document.getElementById('loadingView').style.display = 'none';
  document.getElementById('authView').style.display = 'none';
  document.body.classList.remove('no-scroll');
  const mainEl = document.getElementById('mainContent');
  if (mainEl) mainEl.style.display = '';
  document.getElementById('userView').style.display = '';
  const s = getSession();
  // 专属颜色驱动主卡渐变背景
  if (s && s.color) document.documentElement.style.setProperty('--user-color', s.color);
  document.getElementById('welcomeText').textContent = (s ? s.nickname : '') + '，欢迎回来 👋';
  const badge = document.getElementById('adminBadge');
  if (badge) badge.style.display = (s && s.is_admin) ? '' : 'none';
  const uidEl = document.getElementById('userUid');
  if (uidEl && s) uidEl.textContent = 'UID：' + s.userId;
  // 大头像：直接使用通行证返回的头像链接，无链接或加载失败回退昵称首字 + 专属颜色
  const avatar = document.getElementById('profileAvatar');
  if (avatar && s) {
    setAvatarFromUrl(avatar, s.avatar, s.nickname, s.color);

  }
  // 专属颜色（地图打点色）
  const colorDot = document.getElementById('userColor');
  if (colorDot && s && s.color) {
    colorDot.style.background = s.color;
    colorDot.style.boxShadow = '0 0 0 3px ' + s.color + '40';
  }
}

// /api/me 返回值的落地：写回本地会话缓存，然后渲染已登录界面
function onMeLoaded(data) {
  if (data && data.userId) {
    // 键名用 app.js 里的 LS_USER 常量，别再写一遍字面量 —— 两处各写一份，
    // 将来改键名必漏一处，登录态就会「存进去读不出来」。script 级 const 对后续经典脚本可见
    localStorage.setItem(LS_USER, JSON.stringify({
      userId: data.userId,
      nickname: data.nickname,
      color: data.color,
      is_admin: !!data.is_admin,
      avatar: data.avatar || null,
    }));
  }
  enterUserView();
}

// 页面加载：本脚本执行时本地可能已有会话（刚在本页登录成功，或之前登录过直接访问）。
// 有 token 就校验本站 /api/me，成功进个人中心、失败回登录界面
//（token 失效时 app.js 的 401 处理已清掉本地会话）
if (getSession()) {
  api('/me').then(onMeLoaded).catch(function () { showAuthView(); });
} else {
  showAuthView();
}

// ---------- 关于软件：版本显示 ----------
// 版本号在脚本执行时就填上（script 挂在 body 末尾，DOM 已就绪）
document.getElementById('appVersion').textContent = 'v' + APP_VERSION;

// 清除缓存：接口报文 + 地图边界缓存（具体清哪些见 app.js 的 clearAppCache）。
// 不动登录态与主题 / 图例折叠等偏好，所以清完不必重新登录。
function clearCache() {
  const msg = document.getElementById('cacheMsg');
  clearAppCache();
  msg.className = 'msg ok';
  msg.textContent = '已清除本机缓存';
}

// 退出登录按钮走 data-action（HTML 内联 onclick 会被 CSP 拦）。logout 定义在 app.js，
// 不关心 bindActions 传进来的 (el, e)，直接挂引用即可
bindActions({ logout, 'clear-cache': clearCache });
