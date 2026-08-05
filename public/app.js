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
    // 登录态失效（token 过期/无效）：清除本地会话；管理页自动回到登录界面
    if (res.status === 401 && token) {
      localStorage.removeItem(LS_TOKEN);
      localStorage.removeItem(LS_USER);
      if (window.location.pathname.endsWith('manage.html')) {
        window.location.reload();
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
