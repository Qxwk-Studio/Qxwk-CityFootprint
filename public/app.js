// 共享前端逻辑：API 客户端 + SSO 会话管理
// 认证由通行证 account.qxwkstudio.top 统一管理，本站只存 token

const API_BASE = '/api';
const LS_TOKEN = 'qxwf_token';
const LS_USER = 'qxwf_user';
// 通行证地址（本地 dev 改 http://localhost:8787）
const PASSPORT_URL = 'https://account.qxwkstudio.top';

// 拼通行证登录 URL，带上回跳地址（当前页去参数后的干净 URL）
function passportLoginUrl() {
  const here = location.origin + location.pathname;
  return PASSPORT_URL + '/login.html?redirect=' + encodeURIComponent(here);
}

// SSO 落地：URL 上带 token=... 时存本地、清参数，并拉一次 /api/me 写本地用户信息
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
      }));
    }
  } catch { /* /api/me 拉取失败由后续请求触发 401 兜底 */ }
}

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = localStorage.getItem(LS_TOKEN);
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(API_BASE + path, { ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    // 登录态失效（token 过期/无效）：清本地会话，统一跳通行证登录
    if (res.status === 401) {
      localStorage.removeItem(LS_TOKEN);
      localStorage.removeItem(LS_USER);
      location.href = passportLoginUrl();
    }
    throw new Error(data.error || '请求失败 (' + res.status + ')');
  }
  return data;
}

// 写本地用户缓存（接口返回 userId/nickname/color/is_admin 时复用）
function saveSession(data) {
  localStorage.setItem(LS_TOKEN, data.token);
  localStorage.setItem(LS_USER, JSON.stringify({
    userId: data.userId,
    nickname: data.nickname,
    color: data.color,
    is_admin: !!data.is_admin,
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

// 登出：本地清掉，回通行证登录页（不在本站留登录态）
function logout() {
  localStorage.removeItem(LS_TOKEN);
  localStorage.removeItem(LS_USER);
  location.href = passportLoginUrl();
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

// 页面加载时尝试 SSO 落地（各页内联脚本可在 DOMContentLoaded 前调用以避免闪烁）
handleSsoLanding();