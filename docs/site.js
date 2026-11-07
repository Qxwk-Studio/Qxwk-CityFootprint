(() => {
  const API_BASE = 'https://api.travel.qxwkstudio.top';

  // 当前页高亮
  const path = window.location.pathname.replace(/\/+$/, '') || '/';
  document.querySelectorAll('.site-header nav a').forEach((a) => {
    const href = new URL(a.href, window.location.origin).pathname.replace(/\/+$/, '') || '/';
    if (href === path) a.setAttribute('aria-current', 'page');
  });

  const versionEl = document.getElementById('releaseVersion');
  const githubLink = document.getElementById('githubDownload');
  if (versionEl) {
    fetch(API_BASE + '/api/manifest', { headers: { Accept: 'application/json' } })
      .then((res) => res.ok ? res.json() : null)
      .then((data) => {
        const android = data?.android;
        if (!android?.version_code) return;
        versionEl.textContent = 'v' + android.version_name;
        if (githubLink && android.download_url) githubLink.href = android.download_url;
      })
      .catch(() => {});
  }

  const form = document.querySelector('[data-lookup]');
  if (!form) return;

  const kind = form.dataset.lookup; // user | trip
  const input = form.querySelector('input');
  const button = form.querySelector('button');
  const result = document.getElementById('lookupResult');
  const idParam = new URLSearchParams(window.location.search).get('id') || '';

  function text(value, fallback = '—') {
    return value === null || value === undefined || value === '' ? fallback : String(value);
  }

  function fmtTime(value) {
    if (!value) return '—';
    const n = Number(value);
    if (!Number.isFinite(n)) return text(value);
    return new Date(n).toLocaleString('zh-CN', { hour12: false });
  }

  function renderUser(data) {
    const user = data?.user || data;
    const rows = [
      ['用户 UUID', text(user?.uuid || user?.passport_uuid)],
      ['昵称', text(user?.nickname)],
      ['颜色', text(user?.color)],
      ['行程数', text(user?.trip_count, '0')],
      ['城市数', text(user?.city_count, '0')],
      ['创建时间', fmtTime(user?.created_at)],
    ];
    return rows.map(([k, v]) => `<div class="row"><span>${k}</span><strong>${escapeHtml(v)}</strong></div>`).join('');
  }

  function renderTrip(data) {
    const trip = data?.trip || data;
    let cities = [];
    let items = [];
    try {
      const timeline = typeof trip?.timeline === 'string' ? JSON.parse(trip.timeline) : trip?.timeline;
      items = timeline?.items || [];
      cities = items.filter((x) => x.type === 'visit').map((x) => x.text).filter(Boolean);
    } catch { /* 忽略时间线解析失败 */ }
    const rows = [
      ['行程 UUID', text(trip?.uuid)],
      ['标题', text(trip?.title)],
      ['归属用户', text(data?.user?.nickname)],
      ['城市', cities.length ? cities.join(' · ') : '—'],
      ['时间线', `${items.length} 项`],
      ['开始', fmtTime(trip?.start_time)],
      ['结束', fmtTime(trip?.end_time)],
    ];
    return rows.map(([k, v]) => `<div class="row"><span>${k}</span><strong>${escapeHtml(v)}</strong></div>`).join('');
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#039;');
  }

  function show(state, html) {
    result.hidden = false;
    result.className = `result-state ${state}`;
    result.innerHTML = html;
  }

  async function lookup(id) {
    const pathName = kind === 'user' ? 'users' : 'trips';
    const url = `${API_BASE}/api/${pathName}/${encodeURIComponent(id)}`;
    button.disabled = true;
    show('empty', '查询中…');
    try {
      const res = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!res.ok) {
        if (res.status === 404) return show('empty', '未找到对应记录。');
        if (res.status === 401 || res.status === 403) return show('error', '查询接口需要登录，当前页面暂未接入。');
        return show('error', `查询失败（HTTP ${res.status}）`);
      }
      const data = await res.json();
      const html = kind === 'user' ? renderUser(data) : renderTrip(data);
      show('result', `<div class="result-card">${html}</div>`);
    } catch {
      show('error', '查询服务暂不可用，请稍后重试。');
    } finally {
      button.disabled = false;
    }
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const id = input.value.trim();
    if (!id) {
      show('error', kind === 'user' ? '请输入用户 UUID。' : '请输入行程 UUID。');
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.set('id', id);
    window.history.replaceState(null, '', url);
    lookup(id);
  });

  if (idParam) {
    input.value = idParam;
    lookup(idParam);
  }
})();
