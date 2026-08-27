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

function saveSession(data) {
  localStorage.setItem(LS_TOKEN, data.token);
  localStorage.setItem(LS_USER, JSON.stringify({
    userId: data.userId,
    nickname: data.nickname,
    color: data.color,
    is_admin: !!data.is_admin,
    avatar: data.avatar || null,
  }));
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

// SSO 落地：URL 带 token 时存 localStorage，拉 /api/me 同步用户信息
async function handleSsoLanding() {
  const params = new URLSearchParams(location.search);
  const token = params.get('token');
  if (!token) return;
  localStorage.setItem(LS_TOKEN, token);
  // 清掉 URL 上的 token/uid/nick，避免分享链接泄露
  const cleanUrl = location.origin + location.pathname;
  history.replaceState(null, '', cleanUrl);
  try {
    const me = await fetch(API_BASE + '/me', {
      headers: { Authorization: 'Bearer ' + token },
    }).then(r => r.ok ? r.json() : null).catch(() => null);
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

// 通行证登录 URL（带 redirect 回跳本页）
function passportLoginUrl() {
  const PASSPORT_URL = 'https://account.qxwkstudio.top';
  const here = location.origin + location.pathname;
  return PASSPORT_URL + '/login.html?redirect=' + encodeURIComponent(here);
}

// 页面加载时尝试 SSO 落地，暴露 Promise 供页面内联脚本 await
window._ssoLanding = handleSsoLanding();
