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

// 通行证弹窗登录：打开 login.html 弹窗（popup=1），登录成功后回传 token 并自动关窗。
// 通行证已移除旧整页跳转（登录后会留在账号中心），各站须用弹窗模式接收回传。
function passportLogin() {
  const PASSPORT_URL = 'https://account.qxwkstudio.top';
  const here = location.origin + location.pathname;
  const w = window.open(
    PASSPORT_URL + '/login.html?redirect=' + encodeURIComponent(here) + '&popup=1',
    'qxwk_sso',
    'width=420,height=640'
  );
  if (!w) alert('请允许浏览器弹出窗口，以便完成通行证登录');
}

// 监听通行证弹窗回传：type=qxwk-sso 且 origin 为通行证时落地登录
window.addEventListener('message', async (e) => {
  if (e.origin !== 'https://account.qxwkstudio.top') return;
  const d = e.data || {};
  if (d.type !== 'qxwk-sso' || !d.token) return;
  localStorage.setItem(LS_TOKEN, d.token);
  try {
    const me = await fetch(API_BASE + '/me', {
      headers: { Authorization: 'Bearer ' + d.token },
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
  location.reload(); // 刷新页面以更新登录态 UI
});
