// 共享前端逻辑：API 客户端 + 通行证登录 + 会话管理

// 后端 Worker 的绝对地址。页面在 GitHub Pages（travel.qxwkstudio.top）、后端在
// api.travel.qxwkstudio.top，两者不同源，所以这里**不能**再用相对路径 /api。
// Worker 侧对本页 origin（以及本地调试用的 localhost 端口）放行，见 backend/src/worker.js 的 ALLOWED_ORIGINS
const API_BASE = 'https://api.travel.qxwkstudio.top/api';
const PASSPORT_URL = 'https://account.qxwkstudio.top';
const LS_TOKEN = 'qxwf_token';
const LS_USER = 'qxwf_user';

// ========= 报文缓存（会话级，1 天）+ SWR =========
// 做在 api() 这一层：它是所有读接口的唯一入口，页面就不必各自记「我缓存过什么」。
//
// 为什么用 sessionStorage 而不是 localStorage：报文里可能含自己的私密足迹，
// 按标签页隔离、关标签即清，比长期留在本机风险小。代价是 **F5 不会清掉它**，
// 所以「刷新页面」不是一个可用的失效通道 —— 失效只有三条路：
//   1) 换账号 / 登录 / 登出（见 cacheInvalidate 的三处调用点）
//   2) 写操作成功后（增删改自己的足迹，地图与统计跟着变）
//   3) 24 小时自然过期
const CACHE_PREFIX = 'qxwf_cache_';
const CACHE_TTL_MS = 24 * 60 * 60 * 1000;

// 刻意不缓存的路径：/me 是会话校验，缓存它会让「token 已被撤销」晚一天才被发现，
// 那一天的观感是「看着还登录着，点什么都失败」，比多打一次请求糟得多。
const CACHE_SKIP = ['/me'];

// key 必须按账号隔离：隔离漏了，换账号后（缓存还在 1 天有效期内）会先画出上一个人的
// 私密足迹与统计。未登录时用 anon —— 匿名能看到的本来就只是公开数据。
function cacheKey(path) {
  const u = getSession();
  return CACHE_PREFIX + ((u && u.userId) || 'anon') + '|' + path;
}

// 读缓存。隐私模式 / 配额满 / 手里是脏数据，一律当「没有缓存」返回 null：
// 缓存只是加速，任何异常都不该把页面卡住。
function cacheRead(path) {
  if (CACHE_SKIP.indexOf(path) >= 0) return null;
  try {
    const raw = sessionStorage.getItem(cacheKey(path));
    if (!raw) return null;
    const row = JSON.parse(raw);
    if (!row || Date.now() - row.at > CACHE_TTL_MS) return null;
    return row.data;
  } catch (e) {
    return null;
  }
}

function cacheWrite(path, data) {
  if (CACHE_SKIP.indexOf(path) >= 0) return;
  try {
    sessionStorage.setItem(cacheKey(path), JSON.stringify({ at: Date.now(), data }));
  } catch (e) { /* 配额满就不存，不是错误 */ }
}

// 整体作废。与 App 那边 Store.invalidatePayloads() 同一套思路：不按 key 精细区分，
// 就几份报文，一起丢最省心也不会漏。注意**边界 GeoJSON（IndexedDB）不在这里** ——
// 它是公共数据，与账号无关，没道理跟着登录态被清（见 index.js 的 GEO_MAX_AGE）。
function cacheInvalidate() {
  try {
    const keys = [];
    for (let i = 0; i < sessionStorage.length; i++) keys.push(sessionStorage.key(i));
    keys.filter(k => k && k.indexOf(CACHE_PREFIX) === 0).forEach(k => sessionStorage.removeItem(k));
  } catch (e) { /* 同上，失败不影响主流程 */ }
}

// 清除本站本地缓存：接口报文（sessionStorage）+ 地图行政边界（IndexedDB），供个人中心的
// 「清除缓存」卡片调用。**不动** localStorage 里的登录态与主题 / 图例折叠等偏好 ——
// 那些是用户设置、不是缓存，清掉只会让人莫名其妙（所以清完不必重新登录）。
// 库名与 index.js 的 GEO_DB_NAME 同源（地图页的边界缓存就存在这个库里），改一处要同步另一处。
function clearAppCache() {
  cacheInvalidate();
  try {
    // 删库是异步的；account 页从没打开过这个连接，不会因为「连接未关」而删不掉
    indexedDB.deleteDatabase('cityfootprint-geo');
  } catch (e) { /* 不支持 IndexedDB（隐私模式等）时本来也没有边界缓存 */ }
}

/**
 * 读接口 + SWR：缓存命中就先把缓存交给页面画一版，网络回来再画一版。
 * 内容相同也照画一次，调用方只要守「渲染是幂等的」这一个约定，省掉一层深度比对。
 * 需要「必须最新」的场合（比如增删改之后重新拉地图）直接调 api() —— 那种情况下
 * 缓存已被 cacheInvalidate 清掉，本来也不会命中。
 */
function apiWatch(path, onData, onError) {
  const cached = cacheRead(path);
  if (cached !== null) {
    try { onData(cached); } catch (e) { /* 缓存那份渲染出错，不该挡住下面的网络请求 */ }
  }
  return api(path).then(onData).catch(onError);
}

async function api(path, options = {}) {
  // Content-Type 只在真的带 body 时设：GET 也挂一个 application/json 会让
  // 「没带 Authorization 的公开请求」（/api/cities、/api/geo/:adcode）变成非简单请求，
  // 白白多一次 CORS 预检。带 body 的 PUT/POST 照旧。
  const headers = { ...(options.headers || {}) };
  if (options.body) headers['Content-Type'] = 'application/json';
  const method = (options.method || 'GET').toUpperCase();
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
      // 会话没了，报文缓存也一起清：里面是按 userId 存的上一个人的数据
      cacheInvalidate();
      // 站内路径不带 .html（见 README 设计说明），但旧链接 / 直接手输可能带后缀，也可能被补上末尾斜杠，
      // 所以先归一化再比 —— 否则这层 401 兜底会悄悄失效，用户卡在一个已经没有登录态的页面上
      const p = window.location.pathname.replace(/\.html$/, '').replace(/\/+$/, '');
      if (p === '/account') {
        window.location.reload();          // 个人中心：回到登录视图
      } else if (p === '/visits') {
        window.location.href = '/account'; // 足迹管理：跳转登录页
      }
    }
    throw new Error(data.error || '请求失败 (' + res.status + ')');
  }
  // 读接口成功 → 存起来给 apiWatch 用；写接口成功 → 整体作废（自己的足迹变了，
  // 地图/统计/足迹三份报文都跟着变，不按 key 精细区分）。
  // 这是「写完重新拉」那几处（index.js 的 reloadMap、visits.js 的 loadVisits）
  // 能拿到新数据的保证 —— 少了它，那些地方会命中刚写完的旧缓存，界面像是没保存上。
  if (method === 'GET') cacheWrite(path, data);
  else cacheInvalidate();
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
  // 报文缓存按 userId 存，退出登录时一并清掉，别留给下一个人或下一次登录
  cacheInvalidate();
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

// 从城市表按名字取那一行（不在表里返回 null）。调用方自己挑字段：坐标用 lat/lng，省份走 findProvince
function findCity(name) {
  return (window.CITIES || []).find(c => c.name === name) || null;
}

// 城市名 → 省份，查不到返回空串（调用方自行 filter 掉）。
// 只有这一份 —— 原先 visits.js / stats.js 各写一份，一个回「未知」一个回空串：
// 有人直接调接口写个城市表里没有的城市名时，「去过几个省」两页就会一个多一个少
function findProvince(name) {
  const c = findCity(name);
  return c ? c.province : '';
}

// 专属颜色只认十六进制色值（3/4/6/8 位都放过，别处还拿它拼 `color + '40'` 当 alpha 用，
// 本来就只可能是 hex）。它会被拼进 style="color:…" / divIcon 的 html 字符串里，而 escapeHtml
// 只管 <>&、不转引号，用户可控的字符串直接进属性就是一处属性注入。
// 颜色本应由通行证侧限死成 #rrggbb，这里再兜一道，不合规就退回默认蓝
function safeColor(c) {
  return /^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(c || '') ? c : '#3b82f6';
}

// HTML 转义：凡是要把「用户能填的内容」（城市名、备注）拼进 innerHTML 的地方都得过一遍。
// 只有这一份 —— 各页原先各抄一份，抄漏的那页就是一处 XSS（全站统计页的城市名就漏了：
// 后端只校验 city 非空且 ≤30 字符，谁都能直接调接口写个带脚本的城市名进去）。
function escapeHtml(t) {
  const d = document.createElement('div');
  d.textContent = t || '';
  return d.innerHTML;
}

// ========= 城市三级下拉（国 → 省 → 市） =========
// 主页「添加行程」弹窗与足迹管理「编辑行程」弹窗共用这一份联动逻辑。
// 国家这一级当前只有「中国」—— cities.js 是纯国内地级市表，没有 country 字段；
// 但仍照三级铺开并在这里按 country 过滤，将来数据带上 country 就能自动多出选项，
// 页面不用改结构（跨端契约：城市名仍是提交给后端的 city 原值）。
// box 是含 data-city="country|province|city" 三个 <select> 的容器。
// 返回值供定位回填 / 编辑回填 / 提交取值用，页面里不要再自己去读写 select。
function initCitySelects(box) {
  const countrySel = box.querySelector('[data-city="country"]');
  const provSel = box.querySelector('[data-city="province"]');
  const citySel = box.querySelector('[data-city="city"]');
  const cities = window.CITIES || [];
  const countryOf = c => c.country || '中国';
  const countries = [...new Set(cities.map(countryOf))];

  function fillCities() {
    const list = provSel.value
      ? cities.filter(c => countryOf(c) === countrySel.value && c.province === provSel.value)
      : [];
    citySel.innerHTML = '<option value="">城市</option>' +
      list.map(c => `<option value="${c.name}">${c.name}</option>`).join('');
  }

  function fillProvinces() {
    const provs = [...new Set(cities.filter(c => countryOf(c) === countrySel.value).map(c => c.province))];
    provSel.innerHTML = '<option value="">省份</option>' +
      provs.map(p => `<option value="${p}">${p}</option>`).join('');
    fillCities();
  }

  countrySel.innerHTML = (countries.length ? countries : ['中国'])
    .map(n => `<option value="${n}">${n}</option>`).join('');
  countrySel.addEventListener('change', fillProvinces);
  provSel.addEventListener('change', fillCities);
  fillProvinces();

  return {
    // 当前选中的城市名（没选完为空串）
    getCity() { return citySel.value; },
    // 按城市名回填三级（定位回填、编辑回填共用）；名字不在城市表里则清空
    setCity(name) {
      const c = cities.find(x => x.name === name);
      countrySel.value = c ? countryOf(c) : (countries[0] || '中国');
      fillProvinces();
      if (!c) return;
      provSel.value = c.province;
      fillCities();
      citySel.value = c.name;
    },
    // 复位到初始态（打开添加弹窗时用）：省/市都回到占位项
    reset() {
      countrySel.value = countries[0] || '中国';
      fillProvinces();
    },
  };
}

// 出行方式枚举：code 必须与后端白名单一致（backend/src/worker.js 的 TRANSPORTS），
// 这里的顺序即展示顺序，也是后端落库时的排序依据。label 供 UI 展示。
window.TRANSPORTS = [
  { code: 'plane', label: '✈️ 飞机' },
  { code: 'train', label: '🚆 火车' },
  { code: 'hsr',   label: '🚄 高铁' },
  { code: 'car',   label: '🚗 自驾' },
  { code: 'bus',   label: '🚌 大巴' },
  { code: 'ship',  label: '🚢 轮船' },
  { code: 'bike',  label: '🚲 骑行' },
  { code: 'walk',  label: '🥾 徒步' },
  { code: 'other', label: '🧭 其他' },
];

// 出行方式多选 chips：主页添加弹窗与足迹管理编辑弹窗共用这一套（结构 input + span，
// 选中态靠 CSS 的 `input:checked + span`，不需要 JS 逐个切 class）
function renderTransportChips(container) {
  if (!container) return;
  container.innerHTML = (window.TRANSPORTS || []).map(t =>
    `<label class="tp-chip"><input type="checkbox" value="${t.code}"><span>${t.label}</span></label>`
  ).join('');
}

// 读容器里勾选的 code（DOM 顺序 = 展示顺序）
function getCheckedTransports(container) {
  if (!container) return [];
  return [...container.querySelectorAll('input:checked')].map(i => i.value);
}

// 按 code 数组回填勾选状态（编辑已有行程时用）
function setCheckedTransports(container, codes) {
  if (!container) return;
  const set = new Set(codes || []);
  container.querySelectorAll('input').forEach(i => { i.checked = set.has(i.value); });
}

// code 数组 -> 展示标签数组（列表徽章用）
function transportLabels(codes) {
  const map = new Map((window.TRANSPORTS || []).map(t => [t.code, t.label]));
  return (codes || []).map(c => map.get(c)).filter(Boolean);
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
  // 刚登录：把上一轮的报文缓存清掉。缓存 key 已按 userId 隔离（换账号不会串），
  // 这里清的是「同一个账号上一次会话留下的旧报文」，让本次登录从新数据开始
  cacheInvalidate();
  await applyMe(data.token);
  return data;
}

// ========= data-action 事件委托 =========
// 站点 CSP 是 script-src 'self'（没有 unsafe-inline），写在 HTML 里的 onclick / onchange
// 会被浏览器直接拦掉，所以各页交互一律写成 data-action="动作名"，由这里集中分发。
// 页面脚本调用 bindActions({ 动作名: 处理函数 }) 注册自己那批动作即可，不用再往元素上绑监听。
// 只监听 click / change / input 三种冒泡事件；取事件源最近的一个 [data-action] 命中即调用。
function bindActions(map) {
  const run = (e) => {
    const el = e.target.closest && e.target.closest('[data-action]');
    if (!el || !map[el.dataset.action]) return;
    if (e.type === 'click') {
      // 动作挂在复选框/下拉这类表单控件自身上时（如「记不清了」的开关），
      // 点击的默认行为就是切换选中，在这里 preventDefault 会被浏览器当成「取消」，
      // 把选中状态撤回去、change 也不再触发 —— 这类控件交给 change 分支跑，click 直接放过
      if (/^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) return;
      // 其余入口（<a href="#">、按钮、可点区域）挡掉默认行为，
      // 免得 href="#" 把锚点写进地址栏
      e.preventDefault();
    }
    map[el.dataset.action](el, e);
  };
  document.addEventListener('click', run);
  document.addEventListener('change', run);
  document.addEventListener('input', run);
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
