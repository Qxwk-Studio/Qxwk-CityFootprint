// 共享前端逻辑：API 客户端 + 会话管理

const API_BASE = '/api';
const LS_TOKEN = 'qxwf_token';
const LS_USER = 'qxwf_user';

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = localStorage.getItem(LS_TOKEN);
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(API_BASE + path, { ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    // 登录态失效（token 过期/无效）：清除本地会话；
    // 个人中心回到登录界面，足迹管理跳转到登录页
    if (res.status === 401 && token) {
      localStorage.removeItem(LS_TOKEN);
      localStorage.removeItem(LS_USER);
      const p = window.location.pathname;
      if (p.endsWith('account.html')) {
        window.location.reload();          // 个人中心：回到登录视图
      } else if (p.endsWith('visits.html')) {
        window.location.href = 'account.html'; // 足迹管理：跳转登录页
      }
    }
    throw new Error(data.error || '请求失败 (' + res.status + ')');
  }
  return data;
}

function getSession() {
  try {
    const u = localStorage.getItem(LS_USER);
    return u ? JSON.parse(u) : null;
  } catch {
    return null;
  }
}

function logout() {
  localStorage.removeItem(LS_TOKEN);
  localStorage.removeItem(LS_USER);
  window.location.reload();
}

// 日期显示：空显示"时间未知"
function fmtDate(d) {
  if (!d) return '时间未知';
  return d;
}

// 从城市名找坐标
function findCity(name) {
  const c = (window.CITIES || []).find(c => c.name === name);
  return c ? { lat: c.lat, lng: c.lng } : null;
}

// 直接使用头像链接（由通行证 /api/me 返回的 avatar 字段）设置头像；
// 无链接或图片加载失败时，回退为昵称首字 + 专属颜色
function setAvatarFromUrl(el, avatarUrl, nickname, color) {
  function fallback() {
    el.textContent = (nickname || '?').charAt(0).toUpperCase();
    el.style.background = color || 'var(--primary)';
    el.style.boxShadow = '0 6px 20px ' + (color || '#2563eb') + '55';
  }
  if (!avatarUrl) { fallback(); return; }
  var img = document.createElement('img');
  img.src = avatarUrl;
  img.alt = (nickname || '用户') + ' 的头像';
  img.referrerPolicy = 'no-referrer';
  img.onerror = fallback;
  el.textContent = '';
  el.style.background = 'var(--bg)';
  el.style.boxShadow = '0 6px 20px ' + (color || '#2563eb') + '55';
  el.appendChild(img);
}

// 通行证登录：电脑端弹窗登录（popup=1，登录后 postMessage 回传 token 并自动关窗）；
// 手机端（含微信）无法可靠使用弹窗回传，改整页跳转（登录后带 token 回跳本页，由 _ssoLanding 落地）
function passportLogin() {
  const PASSPORT_URL = 'https://account.qxwkstudio.top';
  const here = location.origin + location.pathname;
  if (/(Android|iPhone|iPad|iPod|Mobile)/i.test(navigator.userAgent)) {
    location.href = PASSPORT_URL + '/login.html?redirect=' + encodeURIComponent(here);
    return;
  }
  const w = window.open(
    PASSPORT_URL + '/login.html?redirect=' + encodeURIComponent(here) + '&popup=1',
    'qxwk_sso',
    'width=420,height=640'
  );
  if (!w) alert('请允许浏览器弹出窗口，以便完成通行证登录');
}

// 拉取 /me 并写入本地会话（弹窗回传 / 整页回跳共用）
async function applyMe(token) {
  try {
    const me = await fetch(API_BASE + '/me', {
      headers: { Authorization: 'Bearer ' + token },
    }).then(r => r.ok ? r.json() : null);
    if (me && me.userId) {
      localStorage.setItem(LS_USER, JSON.stringify({
        userId: me.userId,
        nickname: me.nickname,
        color: me.color,
        is_admin: !!me.is_admin,
        avatar: me.avatar || null,
      }));
    }
  } catch { /* /api/me 拉取失败由后续请求触发 401 兜底 */ }
}

// 监听通行证弹窗回传：type=qxwk-sso 且 origin 为通行证时落地登录
window.addEventListener('message', async (e) => {
  if (e.origin !== 'https://account.qxwkstudio.top') return;
  const d = e.data || {};
  if (d.type !== 'qxwk-sso' || !d.token) return;
  localStorage.setItem(LS_TOKEN, d.token);
  await applyMe(d.token);
  location.reload(); // 刷新页面以更新登录态 UI
});

// SSO 整页回跳落地（微信等无法弹窗的环境）：URL 带 token 则存入本地会话并清理地址栏。
// 页面可通过 window._ssoLanding 等待落地完成后再初始化（如 account/visits 页）。
window._ssoLanding = (async function ssoLanding() {
  const params = new URLSearchParams(location.search);
  const t = params.get('token');
  if (!t) return;
  localStorage.setItem(LS_TOKEN, t);
  // 清掉 URL 上的 token，避免分享链接泄露登录凭证
  history.replaceState(null, '', location.origin + location.pathname);
  await applyMe(t);
})();
