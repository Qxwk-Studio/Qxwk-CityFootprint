// 共享前端逻辑：API 客户端 + 通行证登录 + 会话管理

const API_BASE = '/api';
const PASSPORT_URL = 'https://account.qxwkstudio.top';
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
  const token = localStorage.getItem(LS_TOKEN);
  localStorage.removeItem(LS_TOKEN);
  localStorage.removeItem(LS_USER);
  // 顺手通知通行证撤销这条会话：只清本地的话，通行证那边仍挂着一个属于本站的登录，
  // 会出现在账号中心「已授权网站」卡里，直到 90 天无活动才被回收。
  // 等请求发完再刷新（失败也无所谓，本地 token 已经没了），失败不影响本地登出。
  const done = () => window.location.reload();
  if (!token) return done();
  fetch(PASSPORT_URL + '/api/logout', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
  }).catch(() => {}).then(done);
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

// 通行证登录：本站页面直接跨域调通行证 /api/login 换 token。
// 跨站 SSO / 跳转授权（?redirect= 跳过去、回跳带 #_t=<token>）已在通行证侧整条下线，
// 本站不再有「跳过去登录再回跳」的流程；密码只经本站前端 JS 发给通行证，不落到本站服务器。
async function passportLogin(nickname, password) {
  const res = await fetch(PASSPORT_URL + '/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    // client 声明来源站点：通行证拿它的 apps 白名单校验，命中才在「已授权网站」里显示本站站点名，
    // 未登记则记为「未登记来源」（照样能登录，只是名字认不出来）
    body: JSON.stringify({ nickname, password, client: location.origin }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '登录失败（' + res.status + '）');
  if (!data.token) {
    // 通行证里的「空哈希账号」（管理员预建、还没设过密码）首次登录会返回 need_set_password 且没有 token，
    // 不能把 undefined 当凭证存进 localStorage
    throw new Error(data.need_set_password ? '该账号还没设置密码，请先到通行证设置密码' : '登录失败：没有拿到登录凭证');
  }
  localStorage.setItem(LS_TOKEN, data.token);
  await applyMe(data.token);
  return data;
}

// 拉取本站 /me 并写入本地会话缓存（登录成功后调用）
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
