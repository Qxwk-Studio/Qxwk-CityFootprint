// 主页（地图）页面脚本，原先内联在 index.html 里。抽出是为了配合 CSP（script-src 'self'，
// 内联脚本会被拦）；页面上的交互一律走 data-action，由 app.js 的 bindActions 统一分发。
// 加载顺序：leaflet.js / cities.js / city-codes.js / app.js 先，本文件最后。
const map = L.map('map', { zoomControl: false }).setView([35.5, 105], 4);
// 高德免 Key 瓦片（国内加载快），style=8 街道图
L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
  subdomains: ['1', '2', '3', '4'],
  maxZoom: 18,
  attribution: '&copy; <a href="https://www.amap.com/" target="_blank" rel="noopener">高德地图</a>',
}).addTo(map);

let markers = [];
// 空态提示控件：只建一次，且有数据后要移除 —— 否则「空态下加了第一条足迹」之后，
// 这块「还没有足迹」会一直挂在图上
let emptyCtl = null;
const userInfo = new Map(); // nickname -> { color }
const offset = 0.15;         // 同城多点错开距离
const geoCache = new Map();  // adcode -> Promise<geojson|null>，边界缓存
let renderSeq = 0;           // 渲染批次号，避免异步竞态
let citiesSeq = 0;           // /api/cities 请求批次号：首屏 SWR 与添加后的 reloadMap 可能并发，晚回的旧响应不许盖新数据

// 边界请求并发控制：最多同时发起 GEO_CONCURRENCY 个，避免几十个城市同时请求
const GEO_CONCURRENCY = 6;
let geoActive = 0;
const geoQueue = [];
function geoFetch(url) {
  return new Promise((resolve, reject) => {
    const run = () => {
      geoActive++;
      fetch(url).then(resolve).catch(reject).finally(() => {
        geoActive--;
        if (geoQueue.length) geoQueue.shift()();
      });
    };
    if (geoActive < GEO_CONCURRENCY) run();
    else geoQueue.push(run);
  });
}

// ---------- 边界 IndexedDB 缓存（跨刷新生效，7 天过期，与服务端 Cache-Control 一致） ----------
const GEO_DB_NAME = 'cityfootprint-geo';
const GEO_STORE = 'boundaries';
// 行政边界几年才动一次（撤市设区那类），7 天很安全；与后端 /api/geo 的 Cache-Control 保持同一口径 ——
// 两边 TTL 不一致时前端会先到期，白白多发一次请求（能命中后端缓存，但仍是一次往返）
const GEO_MAX_AGE = 7 * 24 * 60 * 60 * 1000; // 7 天

let geoDBPromise = null;
// 打开数据库（复用同一连接；不支持/失败时返回 null → 退化为无缓存）
function getGeoDB() {
  if (!geoDBPromise) {
    geoDBPromise = new Promise((resolve) => {
      try {
        const req = indexedDB.open(GEO_DB_NAME, 1);
        req.onupgradeneeded = () => {
          if (!req.result.objectStoreNames.contains(GEO_STORE)) {
            req.result.createObjectStore(GEO_STORE, { keyPath: 'adcode' });
          }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
      } catch { resolve(null); }
    });
  }
  return geoDBPromise;
}

// 读缓存：没有返回 null；有则返回 { data, stale } —— stale 为 true 表示已过期，
// 但几何仍可用，调用方拿它先画（见 loadCityGeo 的 SWR）
async function geoDBRead(adcode) {
  const db = await getGeoDB();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const tx = db.transaction(GEO_STORE, 'readonly');
      const get = tx.objectStore(GEO_STORE).get(adcode);
      get.onsuccess = () => {
        const row = get.result;
        if (!row) return resolve(null);
        resolve({ data: row.data, stale: Date.now() - row.ts >= GEO_MAX_AGE });
      };
      get.onerror = () => resolve(null);
    } catch { resolve(null); }
  });
}

// 写缓存（异步，失败静默）
async function geoDBWrite(adcode, data) {
  const db = await getGeoDB();
  if (!db) return;
  try {
    const tx = db.transaction(GEO_STORE, 'readwrite');
    tx.objectStore(GEO_STORE).put({ adcode, data, ts: Date.now() });
  } catch { /* 忽略 */ }
}

// 网络取边界（经 geoFetch 并发控制；API_BASE 来自 app.js —— 后端与页面不同源，必须绝对地址）：
// 校验是合法 GeoJSON 才写入 IndexedDB 并返回，否则返回 null（回退圆点）
async function geoFetchGeo(adcode) {
  const r = await geoFetch(`${API_BASE}/geo/${adcode}`);
  if (!r.ok) return null; // 404/错误 → 回退圆点
  let g = null;
  try {
    g = await r.json();
    // 校验是合法 GeoJSON，避免把错误对象传给 Leaflet
    if (!(g && g.type === 'FeatureCollection' && Array.isArray(g.features))) g = null;
  } catch { g = null; }
  if (g) geoDBWrite(adcode, g); // 写入 IndexedDB，下次打开直接命中
  return g;
}

// 加载城市边界。命中顺序：内存缓存 → IndexedDB → 网络（并发受控）；失败返回 null（回退圆点）。
// IndexedDB 里那份过期时走 SWR：先把旧几何交出去，同时在后台重下刷新缓存 ——
// 边界几年才动一次，先画旧的完全不亏；少了这一步，用户会先看到一个圆点，
// 等网络回来才变成边界，而旧几何明明就在手边。
// 后台只刷缓存、不重画：形状不变，重画还得先撤掉这一座城市已画上的旧图层，不值当。
function loadCityGeo(adcode) {
  if (!geoCache.has(adcode)) {
    geoCache.set(adcode, (async () => {
      try {
        const row = await geoDBRead(adcode);
        if (row) {
          if (row.stale) geoFetchGeo(adcode).catch(() => {}); // 后台重下，不阻塞本次渲染
          return row.data;
        }
        return await geoFetchGeo(adcode); // 完全没有缓存：只能等网络
      } catch {
        // 网络直接挂了（离线、DNS 失败）时 fetch 本身会 reject，而 geoFetchGeo 只兜了非 2xx 与坏 JSON。
        // 这里的 promise 会被 memo 进 geoCache，一旦 reject 就**永远是** rejected：之后每次渲染都拿它、
        // 继续 reject，这座城市连回退圆点都画不出来，只能刷新页面。所以统一兜成 null（调用方按 null 走圆点）。
        return null;
      }
    })());
  }
  return geoCache.get(adcode);
}

function render(data) {
  // 首次加载时移除全屏加载层；添加行程后的局部刷新无需重复移除
  const loadingEl = document.getElementById('loading');
  if (loadingEl) loadingEl.remove();

  // 管理员视图提示（数据来自后端 isAdmin，始终与所见一致）
  const adminNote = document.getElementById('adminViewNote');
  if (adminNote) adminNote.style.display = data.isAdmin ? '' : 'none';

  if (!data.cities.length) {
    // 空态：隐藏图例面板，仅保留左上角的空态提示，避免两者重叠。
    // 控件只建一次（render 会被反复调用，每次都新建就叠起来了）
    document.getElementById('legend').style.display = 'none';
    if (!emptyCtl) {
      emptyCtl = L.control({ position: 'topleft' });
      emptyCtl.onAdd = () => {
        const div = L.DomUtil.create('div');
        div.className = 'leaflet-control';
        div.style.cssText = 'background:var(--surface);padding:16px 20px;border-radius:var(--radius-md);box-shadow:var(--shadow-md);font-size:14px;color:var(--text-secondary);border:1px solid var(--border);';
        div.textContent = '还没有足迹，去添加第一座城市吧 ✈️';
        return div;
      };
      emptyCtl.addTo(map);
    }
    return;
  }
  // 有数据了：撤掉空态提示、把图例恢复显示。这一支在「空态下加了第一条足迹」时会走到，
  // 少了这两句，图例就会一直停在 display:none（直到刷新页面）
  if (emptyCtl) { emptyCtl.remove(); emptyCtl = null; }
  document.getElementById('legend').style.display = '';

  // 收集用户信息。先清空：userInfo 是模块级的，只 set 不 clear 的话，某个人的足迹删光之后
  // 他那一行还会留在图例里（直到刷新页面）
  userInfo.clear();
  for (const c of data.cities) {
    for (const p of c.people) {
      userInfo.set(p.nickname, p.color);
    }
  }

  // 图例（复选框多选，默认全选）
  const legendList = document.getElementById('legendList');
  legendList.innerHTML = '';
  // 全选行
  const allLabel = document.createElement('label');
  allLabel.className = 'user user-all';
  const allCb = document.createElement('input');
  allCb.type = 'checkbox';
  allCb.id = 'selectAll';
  allCb.checked = true;
  allCb.addEventListener('change', () => {
    const checked = allCb.checked;
    legendList.querySelectorAll('.user-check').forEach(cb => { cb.checked = checked; });
    updateSelectAllState();
    renderFilter();
  });
  const allTxt = document.createElement('span');
  allTxt.textContent = '全选';
  allLabel.append(allCb, allTxt);
  legendList.appendChild(allLabel);
  // 用户行
  for (const [name, color] of userInfo) {
    const label = document.createElement('label');
    label.className = 'user';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.className = 'user-check';
    cb.checked = true;
    cb.dataset.nick = name;
    cb.addEventListener('change', () => { updateSelectAllState(); renderFilter(); });
    const dot = document.createElement('span');
    dot.className = 'dot';
    dot.style.background = color;
    const txt = document.createElement('span');
    txt.textContent = name; // textContent 自动转义，安全
    label.append(cb, dot, txt);
    legendList.appendChild(label);
  }
  if (!userInfo.size) legendList.innerHTML = '<div class="empty">暂无数据</div>';

  renderFilter();
}

// 当前勾选的用户集合（图例筛选用）
function currentSelected() {
  return new Set(
    [...document.querySelectorAll('#legendList .user-check:checked')].map(cb => cb.dataset.nick)
  );
}

function renderFilter() {
  // 清掉旧图层
  const seq = ++renderSeq;
  markers.forEach(m => m.remove());
  markers = [];

  // 读取勾选的用户（全勾选 = 显示全部；全不勾 = 显示空）
  const selected = currentSelected();
  const allChecked = selected.size === userInfo.size;

  const cities = window.__citiesData;
  for (const c of cities) {
    const people = allChecked ? c.people : c.people.filter(p => selected.has(p.nickname));
    if (!people.length) continue;
    // 城市颜色：取该城市被勾选人中最后一位的颜色
    // （people 由后端按 created_at 升序给出，末位 = 最新，见 worker.js /api/cities）
    const color = people[people.length - 1].color;

    // adcode 现在由后端随 /api/cities 一起下发（写入时按城市字典派生），旧数据没有时再用
    // 本地城市表兜底 —— 两者都拿不到才回退圆点
    const adcode = c.adcode || CITY_CODES[c.city];
    if (!adcode) { addFallbackDot(c, color); continue; }

    // 异步加载边界，渲染半透明多边形；失败回退圆点
    loadCityGeo(adcode).then(geo => {
      if (seq !== renderSeq) return; // 本次渲染已被新筛选作废
      if (geo) addPolygon(geo, color, c.city);
      else addFallbackDot(c, color);
    });
  }
}

// 收起/展开图例面板，折叠状态用 localStorage('legendCollapsed') 记忆
const LEGEND_KEY = 'legendCollapsed';
// 标题行是 <button>（键盘可达），aria-expanded 得跟着 collapsed class 一起改，
// 否则读屏软件会一直念着错的展开态
function syncLegendA11y() {
  const header = document.querySelector('.legend-header');
  if (!header) return;
  const collapsed = document.getElementById('legend').classList.contains('collapsed');
  header.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
}
function toggleLegend() {
  const collapsed = document.getElementById('legend').classList.toggle('collapsed');
  syncLegendA11y();
  try { localStorage.setItem(LEGEND_KEY, collapsed ? '1' : '0'); } catch (e) {}
}
// 恢复上次折叠状态；无记录时保持默认折叠（见 HTML 里的 collapsed class）
(function restoreLegend() {
  let v = null;
  try { v = localStorage.getItem(LEGEND_KEY); } catch (e) {}
  if (v === '0') document.getElementById('legend').classList.remove('collapsed');
  syncLegendA11y();
})();

// 汉堡菜单开关（独立按钮展开/收起）
function toggleFabMenu() {
  const open = document.getElementById('fabBtn').classList.toggle('active');
  document.querySelectorAll('.fab-item').forEach(item => item.classList.toggle('open', open));
}
// 点击外部收起
document.addEventListener('click', (e) => {
  if (!e.target.closest('.fab-btn') && !e.target.closest('.fab-item')) {
    document.getElementById('fabBtn').classList.remove('active');
    document.querySelectorAll('.fab-item').forEach(i => i.classList.remove('open'));
  }
});
// 点击按钮后收起
document.querySelectorAll('.fab-item').forEach(item => {
  item.addEventListener('click', () => {
    document.getElementById('fabBtn').classList.remove('active');
    document.querySelectorAll('.fab-item').forEach(i => i.classList.remove('open'));
  });
});

// 同步"全选"复选框状态（全选 / 半选 / 全不选）
function updateSelectAllState() {
  const cbs = document.querySelectorAll('#legendList .user-check');
  const allCb = document.getElementById('selectAll');
  if (!allCb || !cbs.length) return;
  const checkedCount = [...cbs].filter(cb => cb.checked).length;
  allCb.checked = checkedCount === cbs.length;
  allCb.indeterminate = checkedCount > 0 && checkedCount < cbs.length;
}

// 城市行程明细：点开弹窗时才请求 —— /api/cities 现在只回「城市 + 坐标 + 去过的人（昵称/颜色）」，
// 日期/备注/私密标记不再随地图数据一起下发。结果按城市缓存，避免反复开关同一弹窗时重复请求。
const cityDetailCache = new Map();
function loadCityDetail(city) {
  if (!cityDetailCache.has(city)) {
    const p = api('/city/' + encodeURIComponent(city)).then(d => d.visits);
    // 失败必须把这个 Promise 从 Map 里删掉：memo 住的 rejected Promise 就**永远**是 rejected，
    // 之后每次点开同一座城市都直接落到下面的 catch、显示「加载失败」，只能刷新页面。
    // 与 loadCityGeo 同一类坑（见那边的注释），这里补上；调用方仍能收到这次 reject。
    p.catch(() => cityDetailCache.delete(city));
    cityDetailCache.set(city, p);
  }
  return cityDetailCache.get(city);
}

// 绑定「按需加载」的弹窗：先占位，点开时才拉该城市最近 10 条行程再填内容
function bindCityPopup(layer, city) {
  layer.bindPopup(`<div class="popup-title">${escapeHtml(city)}</div><div class="popup-more">加载中…</div>`);
  layer.on('popupopen', () => {
    const selected = currentSelected();
    loadCityDetail(city)
      .then(rows => layer.setPopupContent(buildPopupHtml(city, rows, selected)))
      .catch(() => layer.setPopupContent(`<div class="popup-title">${escapeHtml(city)}</div><div class="popup-more">加载失败</div>`));
  });
}

// 弹窗内容：城市名 + 最近 10 条行程（备注单行省略，要看全文去「足迹管理」点开这条）。
// 接口现在回该城市全部行程，截断只在这里做，保持弹窗体积原来的样子。
// rows 已在服务端按可见性筛过；这里再按图例勾选过滤一次，
// 保持「取消勾选谁，弹窗里也就没有谁」的既有行为。
function buildPopupHtml(city, rows, selected) {
  const allChecked = selected.size === userInfo.size;
  const filtered = allChecked ? rows : rows.filter(r => selected.has(r.nickname));
  const shown = filtered.slice(0, 10); // 服务端已按 created_at 倒序，前 10 条即最近 10 条
  let html = `<div class="popup-title">${escapeHtml(city)}</div>`;
  if (!shown.length) return html + '<div class="popup-more">没有符合条件的行程</div>';
  html += shown.map(q => `
    <div class="popup-person">
      <span class="name" style="color:${safeColor(q.color)}">${escapeHtml(q.nickname)}${q.is_private ? ' 🔒' : ''}</span>
      <span class="meta">${escapeHtml(fmtDate(q.visit_date))}</span>
      ${q.note ? `<span class="note">${escapeHtml(q.note)}</span>` : ''}
    </div>`).join('');
  if (filtered.length > 10) html += '<div class="popup-more">仅显示最近 10 条</div>';
  return html;
}

// 渲染市辖区半透明多边形（weight:1 显示全市边界轮廓，hover 提亮）
function addPolygon(geo, color, city) {
  const layer = L.geoJSON(geo, {
    style: { color, weight: 1, fillColor: color, fillOpacity: 0.4 },
  });
  layer.on('mouseover', () => layer.setStyle({ fillOpacity: 0.6 }));
  layer.on('mouseout', () => layer.setStyle({ fillOpacity: 0.4 }));
  bindCityPopup(layer, city);
  layer.addTo(map);
  markers.push(layer);
}

// 边界加载失败或无 adcode 时的回退：圆点
function addFallbackDot(c, color) {
  const circle = L.circleMarker([c.lat, c.lng], {
    radius: 7, color: '#fff', weight: 1.5, fillColor: color, fillOpacity: 0.9,
  });
  bindCityPopup(circle, c.city);
  circle.addTo(map);
  markers.push(circle);
}

// ---------- Toast 提示（与其他项目统一：顶部滑入 + 信息图标） ----------
function showToast(msg) {
  const el = document.getElementById('toast');
  document.getElementById('toastText').textContent = msg;
  el.classList.add('show');
  if (window._toastTimer) clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(() => el.classList.remove('show'), 2500);
}
function hideToast() {
  document.getElementById('toast').classList.remove('show');
  if (window._toastTimer) clearTimeout(window._toastTimer);
}

// ---------- 添加行程 ----------
// 打开添加行程：先开弹窗再尽力定位 —— 定位只是帮忙把城市填成最近的一座，
// 没权限 / 超时都不该把入口堵死（这里已是全站唯一的添加入口）。
function openQuickAdd() {
  if (!getSession()) { showToast('还未登录'); return; }
  openQuickModal();
  locateCity();
}

// 定位并把「城市」填成最近的一座。开弹窗与手点准心按钮都走这里 —— 一处实现两个入口
function locateCity() {
  if (!navigator.geolocation) { showToast('浏览器不支持定位'); return; }
  showToast('正在定位…');
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      hideToast();
      updateMyLocation(pos.coords.latitude, pos.coords.longitude);
      const city = nearestCity(pos.coords.latitude, pos.coords.longitude);
      if (!city) { showToast('未找到附近城市'); return; }
      quickCitySelects.setCity(city.name); // 三级下拉一起回填（省也得跟着选中）
    },
    (err) => { showToast(geoErrorMsg(err)); },
    { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
  );
}

// 城市三级下拉与年 / 月下拉的选项只在启动时铺一次（与足迹管理页同一份 window.CITIES）
let quickCitySelects = null;
(function initQuickForm() {
  // 国 → 省 → 市三级联动（逻辑在 app.js，与足迹管理页编辑弹窗共用一份）
  quickCitySelects = initCitySelects(document.getElementById('quickCitySelects'));

  // 出行方式 chips（枚举与渲染逻辑在 app.js，与足迹管理页共用）
  renderTransportChips(document.getElementById('quickTransport'));

  const yearSel = document.getElementById('quickYear');
  const monthSel = document.getElementById('quickMonth');
  const currentYear = new Date().getFullYear();
  yearSel.innerHTML = '<option value="">年份</option>';
  for (let y = currentYear; y >= 2000; y--) {
    const opt = document.createElement('option');
    opt.value = String(y);
    opt.textContent = y + ' 年';
    yearSel.appendChild(opt);
  }
  monthSel.innerHTML = '<option value="">仅年份</option>';
  for (let m = 1; m <= 12; m++) {
    const opt = document.createElement('option');
    opt.value = String(m).padStart(2, '0');
    opt.textContent = m + ' 月';
    monthSel.appendChild(opt);
  }
})();

// 定位错误 → 差异化提示（GPS 关闭时浏览器多表现为超时，需引导检查定位开关）
function geoErrorMsg(err) {
  if (err && err.code === err.PERMISSION_DENIED) return '未获得定位权限，请允许定位';
  if (err && err.code === err.POSITION_UNAVAILABLE) return '定位失败，请检查 GPS 是否已开启';
  if (err && err.code === err.TIMEOUT) return '定位超时，请检查定位（GPS）是否已开启';
  return '定位失败，请重试';
}

// 我的位置圆点（仅在用户主动定位后显示，不做自动定位），颜色跟随当前登录用户
let myLocationMarker = null;
function updateMyLocation(lat, lng) {
  if (myLocationMarker) map.removeLayer(myLocationMarker);
  const s = getSession();
  // 颜色要拼进下面的 html 字符串，先过 safeColor（只认 #rrggbb）防属性注入，见 app.js
  const color = safeColor(s && s.color);
  const icon = L.divIcon({
    className: 'my-loc-wrap',
    html: `<div class="my-loc-dot" style="background:${color};box-shadow:0 0 0 2px ${color}73, 0 2px 8px rgba(0,0,0,.3);"></div>`,
    iconSize: [20, 20],
    iconAnchor: [10, 10],
  });
  myLocationMarker = L.marker([lat, lng], { icon, zIndexOffset: 1000 }).addTo(map);
}

// 在 CITIES 中找离指定坐标最近的城市（城市级反查，忽略坐标系偏移影响）
function nearestCity(lat, lng) {
  let best = null, bestD = Infinity;
  for (const c of window.CITIES) {
    const d = (c.lat - lat) * (c.lat - lat) + (c.lng - lng) * (c.lng - lng);
    if (d < bestD) { bestD = d; best = c; }
  }
  return best;
}

// 「记不清了」：禁用并清空年月下拉（与后端 ^\d{4}(-\d{2})?$ 的可空规则对齐）
function toggleQuickDate() {
  const forgot = document.getElementById('quickForgot').checked;
  const yearSel = document.getElementById('quickYear');
  const monthSel = document.getElementById('quickMonth');
  yearSel.disabled = forgot;
  monthSel.disabled = forgot;
  if (forgot) { yearSel.value = ''; monthSel.value = ''; }
}

// 年 / 月组合成 '2024' 或 '2024-08'，未选年返回 null（= 记不清）
function quickVisitDate() {
  const y = document.getElementById('quickYear').value;
  const m = document.getElementById('quickMonth').value;
  if (!y) return null;
  return m ? y + '-' + m : y;
}

function updateQuickNoteCount() {
  document.getElementById('quickNoteCount').textContent = document.getElementById('quickNote').value.length + '/100';
}

// 开弹窗并复位：时间默认填「本月」——添加行程时最常见的值，用户不动就是对的
function openQuickModal() {
  const now = new Date();
  quickCitySelects.reset();
  document.getElementById('quickYear').value = String(now.getFullYear());
  document.getElementById('quickMonth').value = String(now.getMonth() + 1).padStart(2, '0');
  document.getElementById('quickForgot').checked = false;
  toggleQuickDate();
  document.getElementById('quickNote').value = '';
  updateQuickNoteCount();
  setCheckedTransports(document.getElementById('quickTransport'), []);
  document.getElementById('quickPrivate').checked = false;
  document.getElementById('quickMsg').textContent = '';
  document.getElementById('quickSubmit').disabled = false;
  // showModal 才会进 top layer（拿到遮罩、焦点陷阱与 inert 背景）；锁滚动见上面的 body.no-scroll
  document.getElementById('quickModal').showModal();
  document.body.classList.add('no-scroll');
  document.getElementById('quickProvince').focus();
}

function closeQuickModal() {
  // 未打开时 close() 是空操作；滚动锁在下面的 close 事件里统一解
  document.getElementById('quickModal').close();
}

async function submitQuickAdd() {
  const btn = document.getElementById('quickSubmit');
  const msg = document.getElementById('quickMsg');
  // 城市必须来自城市表：坐标由它带出来（后端只校验坐标是数字，认不认得出城市名是前端的事）。
  // findCity 回的是城市表里那一行，只取 lat/lng；城市名用下拉选中的原值（三级没选完时 getCity() 为空串）
  const name = quickCitySelects.getCity();
  const c = findCity(name);
  if (!c) {
    msg.className = 'msg error';
    msg.textContent = '请选择省份和城市';
    return;
  }
  btn.disabled = true;
  msg.className = 'msg error';
  msg.textContent = '正在添加…';

  const payload = {
    city: name,
    lat: c.lat,
    lng: c.lng,
    visit_date: quickVisitDate(),
    note: document.getElementById('quickNote').value.trim(),
    is_private: document.getElementById('quickPrivate').checked,
    transport: getCheckedTransports(document.getElementById('quickTransport')),
  };

  try {
    await api('/visits', { method: 'POST', body: JSON.stringify(payload) });
    closeQuickModal();
    showToast('已添加');
    reloadMap();
  } catch (err) {
    msg.className = 'msg error';
    msg.textContent = err.message;
    btn.disabled = false;
  }
}

// 添加成功后刷新地图（重新拉取 /api/cities 并渲染）
function reloadMap() {
  const feedSeq = ++citiesSeq; // 顶掉首屏那一发还没回来的响应，见 citiesSeq 的注释
  cityDetailCache.clear(); // 新增了行程，已缓存的弹窗明细作废
  // 这里刻意用 api() 而不是 apiWatch()：刚 POST 成功，app.js 已把报文缓存整体作废，
  // 这一发必然走网络、拿到含新城市的数据；换 apiWatch 只是多一次必然落空的缓存查询
  api('/cities')
    .then(data => {
      if (feedSeq !== citiesSeq) return; // 又被更新的一发放到后面去了，这版不要了
      window.__citiesData = data.cities;
      render(data);
    })
    .catch(err => showToast(err.message));
}

// 点遮罩关闭：::backdrop 命中的也是 dialog 自己（e.target === dialog），但在弹窗的内边距、
// 字段之间的空隙上点击同样落在 dialog 上 —— 只看 e.target 会「点空白就关掉、输入白填」。
// 所以再用坐标确认点确实落在弹窗矩形之外（网上的通行写法）
const quickModalEl = document.getElementById('quickModal');
quickModalEl.addEventListener('click', (e) => {
  if (e.target !== quickModalEl) return;
  const r = quickModalEl.getBoundingClientRect();
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) {
    closeQuickModal();
  }
});
// Esc 关闭是 <dialog> 的原生行为（还顺带把焦点还给触发它的按钮），不走上面的按钮，
// 所以恢复页面滚动这件事只能挂在 close 事件上（cancel → close 两条路径都会经过这里）
quickModalEl.addEventListener('close', () => {
  document.body.classList.remove('no-scroll');
});

// 页面交互入口（HTML 里全是 data-action，见 app.js 的 bindActions）
bindActions({
  'toggle-legend': toggleLegend,
  'toggle-fab': toggleFabMenu,
  'open-quick-add': openQuickAdd,
  'close-quick-modal': closeQuickModal,
  'locate-city': locateCity,
  'toggle-quick-date': toggleQuickDate,
  'update-quick-note-count': updateQuickNoteCount,
  'submit-quick-add': submitQuickAdd,
  'retry-load': () => window.location.reload(),
});

// 拉数据。用 apiWatch（SWR）：会话级报文缓存命中就先画一版 —— 地图与「谁的足迹」图例
// 立刻出来，不用等 /api/cities 回来；网络那份回来后照原样再画一次覆盖上去。
const feedSeq = ++citiesSeq;
apiWatch('/cities', data => {
  // 已经被后发的 reloadMap 顶掉（用户在这一发还没回来时就先添了行程）：丢弃，别拿旧数据盖新数据。
  // 缓存命中的那一版是在发起时同步画的，那时本 seq 还是当前的，不受影响
  if (feedSeq !== citiesSeq) return;
  window.__citiesData = data.cities;
  render(data);
})
  .catch(err => {
    // 失败时不能只把文案塞进加载层：那是一块 inset:0 / z-index 1500 的全屏遮罩，连导航栏一起盖住，
    // 用户看不到任何入口也没法重试，只能靠浏览器后退。就地补一颗「重试」（整页重来，数据会重新拉）
    const loadingEl = document.getElementById('loading');
    // 但加载层可能已经不在了：缓存命中时 render() 开头就把它 remove 掉（网络那份失败才走到这里），
    // 这时对 null 取 innerHTML 会直接抛错，用户连提示都看不到。退回轻提示
    if (!loadingEl) { showToast(err.message); return; }
    loadingEl.innerHTML =
      `<div>❌ ${escapeHtml(err.message)}</div>` +
      '<button type="button" class="btn btn-primary" data-action="retry-load" style="width:auto;">重试</button>';
  });
