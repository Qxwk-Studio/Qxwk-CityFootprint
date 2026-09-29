// 足迹管理页脚本，原先内联在 visits.html 里。抽出是为了配合 CSP（script-src 'self'，
// 内联脚本会被拦）；页面上的交互一律走 data-action，由 app.js 的 bindActions 统一分发。
// 依赖顺序：cities.js / app.js 先，本文件最后。
// 未登录跳个人中心（那里是登录入口），已登录校验凭证后加载
if (!getSession()) {
  location.href = '/account';
} else {
  api('/me')
    .then(function () {
      document.getElementById('loadingView').style.display = 'none';
      document.getElementById('mainContent').style.display = '';
      loadVisits();
    })
    .catch(function () {
      // token 失效或校验失败：回登录入口页
      location.href = '/account';
    });
}

// 城市三级下拉（国 → 省 → 市）：联动逻辑在 app.js，与主页添加弹窗共用一份
const visitCitySelects = initCitySelects(document.getElementById('visitCitySelects'));

// 出行方式 chips（枚举与渲染逻辑在 app.js，与主页添加弹窗共用）
renderTransportChips(document.getElementById('visitTransport'));

// 时间下拉（年 + 月），防止手输格式错误
(function() {
  const yearSel = document.getElementById('visitYear');
  const monthSel = document.getElementById('visitMonth');
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

let myVisits = [];

// 日期切换（记不清：禁用年月下拉并清空）
function toggleDate() {
  const forgot = document.getElementById('forgotDate').checked;
  const yearSel = document.getElementById('visitYear');
  const monthSel = document.getElementById('visitMonth');
  yearSel.disabled = forgot;
  monthSel.disabled = forgot;
  if (forgot) {
    yearSel.value = '';
    monthSel.value = '';
  }
}

// 从年/月下拉组合日期值（'2024' 或 '2024-08'），未选年返回 null
function getVisitDate() {
  const y = document.getElementById('visitYear').value;
  const m = document.getElementById('visitMonth').value;
  if (!y) return null;
  return m ? y + '-' + m : y;
}

// 更新备注字数提醒（x/100）
function updateNoteCount() {
  const el = document.getElementById('noteCount');
  const input = document.getElementById('visitNote');
  if (el && input) el.textContent = input.value.length + '/100';
}

// 更新足迹统计：去过几座城市（去重）+ 足迹总数
// （城市 → 省份的 findProvince 已统一到 app.js，与全站统计页共用一份）

// 最常用出行方式：把每条足迹的 transport 数组摊平计票，取票数最高的一种。
// 并列时取 TRANSPORTS 展示顺序靠前的（filter 已按原顺序，稳定排序保住同票的名次）；
// 一条出行方式都没填则回「—」。返回的是中文名，去掉 label 里的 emoji ——
// 卡片标题已经带了 🚆，值里再来一个就重了。
function topTransportName() {
  const votes = {};
  myVisits.forEach(v => (v.transport || []).forEach(code => { votes[code] = (votes[code] || 0) + 1; }));
  const top = (window.TRANSPORTS || []).map(t => t.code)
    .filter(code => votes[code])
    .sort((a, b) => votes[b] - votes[a])[0];
  const label = top ? transportLabels([top])[0] : null;
  return label ? label.replace(/^\S+\s+/, '') : '—';
}

function updateVisitStats() {
  const el = document.getElementById('visitStats');
  if (!el) return;
  const cityCount = new Set(myVisits.map(v => v.city)).size;
  // 查不到的城市（有人直接调接口写进来的）不计入省份数 —— 与全站统计页同一口径
  const provinceCount = new Set(myVisits.map(v => findProvince(v.city)).filter(Boolean)).size;
  const topTransport = topTransportName();
  const dates = myVisits.map(v => v.visit_date).filter(Boolean).sort();
  const first = dates[0] || '—';
  const last = dates[dates.length - 1] || '—';
  // 第一行整行是时间跨度长卡，其余四张按每行两个排（布局见页内 .visit-stats）
  el.innerHTML = `
    <div class="stat-card stat-wide">
      <div class="label">⏳ 时间跨度</div>
      <div class="value date-range">${first} → ${last}</div>
    </div>
    <div class="stat-card">
      <div class="label">✈️ 去过城市</div>
      <div class="value">${cityCount}</div>
    </div>
    <div class="stat-card">
      <div class="label">📍 足迹</div>
      <div class="value">${myVisits.length}</div>
    </div>
    <div class="stat-card">
      <div class="label">🗺️ 省份</div>
      <div class="value">${provinceCount}</div>
    </div>
    <div class="stat-card">
      <div class="label">🚆 最常用出行</div>
      <div class="value">${topTransport}</div>
    </div>`;
}

// 渲染成就卡片：先算总进度写到卡片顶部，再按 4 个分类铺开
// CATEGORIES 直接来自 /api/my-visits 的 achievements —— 判定逻辑只留在后端
// （backend/src/achievements.js），网页不再自带一份定义，两端也不会算出不同结果。
function renderAchievements(CATEGORIES) {
  const el = document.getElementById('achievementGrid');
  if (!el) return;

  // 总进度（卡片头部那一行）：「已点亮 X / Y」+ 进度条宽度
  const doneAll = CATEGORIES.reduce((n, c) => n + c.items.filter(a => a.done).length, 0);
  const totalAll = CATEGORIES.reduce((n, c) => n + c.items.length, 0);
  const doneEl = document.getElementById('achvDone');
  const totalEl = document.getElementById('achvTotal');
  const barEl = document.getElementById('achvBar');
  if (doneEl) doneEl.textContent = doneAll;
  if (totalEl) totalEl.textContent = totalAll;
  if (barEl) barEl.style.width = (totalAll ? (doneAll / totalAll) * 100 : 0) + '%';

  el.innerHTML = CATEGORIES.map(cat => {
    const doneCount = cat.items.filter(a => a.done).length;
    const pct = cat.items.length ? (doneCount / cat.items.length) * 100 : 0;
    return `
      <div class="achievement-category">
        <div class="category-header">
          <span class="category-title">${cat.title}</span>
          <span class="category-bar"><i style="width:${pct}%"></i></span>
          <span class="category-progress">${doneCount}/${cat.items.length}</span>
        </div>
        <div class="category-body">
          ${cat.items.map(a => `
            <div class="achievement${a.done ? ' done' : ''}">
              <div class="icon">${a.icon}</div>
              <div class="body">
                <div class="name">${a.name}</div>
                <div class="desc">${a.desc}</div>
              </div>
              <span class="check">✓</span>
            </div>`).join('')}
        </div>
      </div>`;
  }).join('');
}

// SWR：缓存命中先把列表 / 统计卡 / 成就画一版，网络回来再画一版。
// 增删改之后（本文件 saveEdit / delVisit 末尾）调到这里时，app.js 已把报文缓存整体作废，
// 只会走网络那一支，不会命中刚写完的旧报文。
function loadVisits() {
  apiWatch('/my-visits', renderVisits, showVisitsError);
}

function renderVisits(data) {
  const list = document.getElementById('visitList');
  myVisits = data.visits;
  updateVisitStats();
  renderAchievements(data.achievements || []);
  if (!myVisits.length) { list.innerHTML = '<div class="empty">还没有足迹，回主页点右下角「添加行程」记一笔 ✈️</div>'; return; }
  list.innerHTML = '';
  for (let i = 0; i < myVisits.length; i++) {
    const v = myVisits[i];
    const item = document.createElement('div');
    item.className = 'visit-item';
    // 模板里的缩进保持原样不改：它同时是写进 DOM 的字符串，动缩进就等于动输出
    item.innerHTML = `
        <span class="visit-dot">${myVisits.length - i}</span>
        <div class="visit-main" tabindex="0" role="button">
          <div class="visit-city">${escapeHtml(v.city)}${v.is_private ? '<span class="visit-private-badge" title="不公开行程，仅自己可见">🔒 不公开</span>' : ''}<span class="visit-meta">· ${escapeHtml(fmtDate(v.visit_date))}</span></div>
          ${(v.transport && v.transport.length) ? `<div class="visit-transport">${transportLabels(v.transport).map(l => `<span class="tp-badge">${escapeHtml(l)}</span>`).join('')}</div>` : ''}
          ${v.note ? `<div class="visit-note">${escapeHtml(v.note)}</div>` : ''}
        </div>
        <div class="visit-actions">
          <button class="btn btn-ghost btn-sm visit-share" type="button" title="分享" aria-label="分享">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
          </button>
          <button class="btn btn-ghost btn-sm" data-action="edit" data-id="${v.id}">编辑</button>
          <button class="btn btn-danger btn-sm" data-action="del" data-id="${v.id}">删除</button>
        </div>`;
    // 点整条 = 查看（只读弹窗）。行内按钮各干各的事，点它们时别再顺手弹详情，
    // 所以先看事件是否落在动作区里
    item.onclick = (e) => {
      if (e.target.closest('.visit-actions')) return;
      showVisitDetail(v.id);
    };
    // 键盘等价物：可聚焦的是 .visit-main 而不是整行 —— role=button 放在行上会跟行内的
    // 编辑/删除按钮形成嵌套交互元素。回车 / 空格与点整行同效
    item.querySelector('.visit-main').onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); showVisitDetail(v.id); }
    };
    list.appendChild(item);
  }
}

function showVisitsError(err) {
  const list = document.getElementById('visitList');
  list.innerHTML = '<div class="empty">加载失败：' + escapeHtml(err.message) + '</div>';
  // 同一个请求还供着上方五张统计卡与右侧成就：它们本来是「— → — / 0」和「正在加载…」的占位，
  // 失败时也得各自收尾，否则看着像还在加载（成就顶部那行「已点亮 X / Y」数据没变，一并写死 0）
  document.getElementById('visitStats').innerHTML = '<div class="empty stat-wide">统计加载失败</div>';
  document.getElementById('achievementGrid').innerHTML = '<div class="empty">成就加载失败</div>';
  document.getElementById('achvDone').textContent = '0';
  document.getElementById('achvTotal').textContent = '0';
  document.getElementById('achvBar').style.width = '0%';
}

let editingId = null;

// 保存编辑。添加行程不在这里 —— 已统一到主页的「添加行程」弹窗（index.html），
// 全站只有那一处能新建，避免两个入口各写一份校验。
async function saveEdit(e) {
  e.preventDefault();
  if (!editingId) return false;
  const msg = document.getElementById('addMsg');
  msg.className = 'msg error';
  const name = visitCitySelects.getCity();
  const c = findCity(name);
  if (!c) { msg.textContent = '请选择省份和城市'; return false; }

  const payload = {
    city: name,
    lat: c.lat,
    lng: c.lng,
    visit_date: getVisitDate(),
    note: document.getElementById('visitNote').value.trim(),
    is_private: document.getElementById('visitPrivate').checked,
    transport: getCheckedTransports(document.getElementById('visitTransport')),
  };

  try {
    await api('/visits/' + editingId, { method: 'PUT', body: JSON.stringify(payload) });
    closeEditModal();
    loadVisits();
  } catch (err) { msg.textContent = err.message; }
  return false;
}

function startEdit(id) {
  const v = myVisits.find(x => x.id === id);
  if (!v) return;
  editingId = id;
  visitCitySelects.setCity(v.city); // 三级下拉一起回填
  document.getElementById('visitNote').value = v.note || '';
  document.getElementById('visitPrivate').checked = !!v.is_private;
  setCheckedTransports(document.getElementById('visitTransport'), v.transport);
  updateNoteCount();
  const yearSel = document.getElementById('visitYear');
  const monthSel = document.getElementById('visitMonth');
  if (v.visit_date) {
    document.getElementById('forgotDate').checked = false;
    const parts = String(v.visit_date).split('-');
    yearSel.value = parts[0] || '';
    monthSel.value = parts[1] || '';
    yearSel.disabled = false;
    monthSel.disabled = false;
  } else {
    document.getElementById('forgotDate').checked = true;
    yearSel.value = '';
    monthSel.value = '';
    yearSel.disabled = true;
    monthSel.disabled = true;
  }
  const msg = document.getElementById('addMsg');
  msg.textContent = '正在编辑：' + v.city;
  msg.className = 'msg ok';
  document.body.classList.add('no-scroll'); // 锁住背后页面：原生模态挡不住滚轮
  document.getElementById('editDialog').showModal();
}

// 关弹窗即清编辑态：点「取消」/ 保存成功直接走这里
function closeEditModal() {
  editingId = null;
  document.getElementById('editDialog').close();
  document.getElementById('addMsg').textContent = '';
}

// Esc 关闭是 <dialog> 的原生行为，不经过上面的按钮，得靠 close 事件兜住 ——
// 否则 editingId 会残留成上一条，虽然弹窗已关不会误提交，但留个脏状态迟早坑人
document.getElementById('editDialog').addEventListener('close', () => {
  editingId = null;
  document.body.classList.remove('no-scroll'); // 关弹窗（含 Esc）恢复页面滚动
});

// 查看（只读）弹窗：列表里备注只占一行、多出来的省略号收住，全文在这里看。
// 与 app 端同一套交互：点条目看详情，要改走行内那颗「编辑」，删除也在编辑弹窗里。
function showVisitDetail(id) {
  const v = myVisits.find(x => x.id === id);
  if (!v) return;
  // 城市名照旧 escapeHtml，私密徽章是固定文案 —— 与列表里那条的写法保持一致
  document.getElementById('detailCity').innerHTML =
    escapeHtml(v.city) + (v.is_private ? '<span class="visit-private-badge" title="不公开行程，仅自己可见">🔒 不公开</span>' : '');
  document.getElementById('detailDate').textContent = fmtDate(v.visit_date);

  // 出行方式、备注没填就整行隐藏（含标签）：留一个空标题比少一行更难看
  const transports = transportLabels(v.transport || []);
  document.getElementById('detailTransportRow').style.display = transports.length ? '' : 'none';
  document.getElementById('detailTransport').textContent = transports.join(' / ');
  document.getElementById('detailNoteRow').style.display = v.note ? '' : 'none';
  document.getElementById('detailNote').textContent = v.note || '';

  document.body.classList.add('no-scroll'); // 与编辑弹窗同理：原生模态挡不住背后页面的滚轮
  document.getElementById('visitDetailDialog').showModal();
}

function closeVisitDetail() {
  document.getElementById('visitDetailDialog').close();
}

// Esc 关闭不经过上面的按钮，照编辑弹窗那套用 close 事件恢复页面滚动
document.getElementById('visitDetailDialog').addEventListener('close', () => {
  document.body.classList.remove('no-scroll');
});

async function delVisit(id) {
  if (!confirm('确定删除这条足迹吗？')) return;
  try {
    await api('/visits/' + id, { method: 'DELETE' });
    loadVisits();
  } catch (err) { alert(err.message); }
}

// 编辑表单提交：用 addEventListener 绑 submit（不再走 HTML 内联 onsubmit，CSP 会拦）。
// saveEdit 内部已 preventDefault，它返回 false 是内联写法的老习惯，这里忽略返回值即可
document.getElementById('editForm').addEventListener('submit', saveEdit);

// 页面交互入口（HTML 里全是 data-action，见 app.js 的 bindActions）。
// 「编辑 / 删除」由列表模板动态生成，data-id 带的是这条足迹的 id，这里转回数字 ——
// startEdit / delVisit 用 === 在 myVisits 里找，字符串对不上数字会找不到
bindActions({
  'close-edit-modal': closeEditModal,
  'close-visit-detail': closeVisitDetail,
  'toggle-date': toggleDate,
  'update-note-count': updateNoteCount,
  'edit': (el) => startEdit(Number(el.dataset.id)),
  'del': (el) => delVisit(Number(el.dataset.id)),
});
