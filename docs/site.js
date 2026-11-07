(() => {
  const API_BASE = 'https://api.travel.qxwkstudio.top';

  // 当前页高亮
  const path = window.location.pathname.replace(/\/+$/, '') || '/';
  document.querySelectorAll('.site-header nav a').forEach((a) => {
    const href = new URL(a.href, window.location.origin).pathname.replace(/\/+$/, '') || '/';
    if (href === path) a.setAttribute('aria-current', 'page');
  });

  const yearEl = document.getElementById('footprintYear');
  if (yearEl) yearEl.textContent = String(new Date().getFullYear());

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
  const params = new URLSearchParams(window.location.search);
  const idParam = params.get('id') || '';
  let recordType = kind === 'user' ? 'user' : (params.get('type') === 'checkin' ? 'checkin' : 'trip');

  function text(value, fallback = '—') {
    return value === null || value === undefined || value === '' ? fallback : String(value);
  }

  function fmtTime(value) {
    if (!value) return '—';
    const n = Number(value);
    if (!Number.isFinite(n)) return text(value);
    return new Date(n).toLocaleString('zh-CN', { hour12: false });
  }

  function fmtCheckinDate(value, precision) {
    if (!value || Number(precision) === 3) return '时间不详';
    const d = new Date(Number(value));
    if (!Number.isFinite(d.getTime())) return '时间不详';
    const y = d.getUTCFullYear();
    const m = String(d.getUTCMonth() + 1).padStart(2, '0');
    const day = String(d.getUTCDate()).padStart(2, '0');
    if (Number(precision) === 0) return String(y);
    if (Number(precision) === 1) return y + '-' + m;
    return y + '-' + m + '-' + day;
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#039;');
  }

  function rowsToHtml(rows) {
    return rows
      .map(([k, v]) => '<div class="row"><span>' + k + '</span><strong>' + escapeHtml(v) + '</strong></div>')
      .join('');
  }

  function renderUser(data) {
    const user = data?.user || data;
    return rowsToHtml([
      ['用户 UUID', text(user?.uuid || user?.passport_uuid)],
      ['昵称', text(user?.nickname)],
      ['颜色', text(user?.color)],
      ['行程数', text(user?.trip_count, '0')],
      ['城市数', text(user?.city_count, '0')],
      ['创建时间', fmtTime(user?.created_at)],
    ]);
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
    return rowsToHtml([
      ['行程 UUID', text(trip?.uuid)],
      ['标题', text(trip?.title)],
      ['归属用户', text(data?.user?.nickname)],
      ['城市', cities.length ? cities.join(' · ') : '—'],
      ['时间线', items.length + ' 项'],
      ['开始', fmtTime(trip?.start_time)],
      ['结束', fmtTime(trip?.end_time)],
    ]);
  }

  function renderCheckin(data) {
    const checkin = data?.checkin || data;
    const amount = Number(checkin?.amount_cents);
    const days = Number(checkin?.stay_days);
    return rowsToHtml([
      ['打卡 UUID', text(checkin?.uuid)],
      ['城市', text(checkin?.city_name)],
      ['省份', text(checkin?.province)],
      ['归属用户', text(data?.user?.nickname)],
      ['打卡时间', fmtCheckinDate(checkin?.checkin_at, checkin?.date_precision)],
      ['备注', text(checkin?.note)],
      ['同行人', text(checkin?.companions)],
      ['评分', checkin?.rating ? checkin.rating + ' 分' : '—'],
      ['金额', Number.isFinite(amount) ? '¥' + (amount / 100).toFixed(2) : '—'],
      ['停留天数', Number.isFinite(days) ? days + ' 天' : '—'],
    ]);
  }

  function show(state, html) {
    result.hidden = false;
    result.className = 'result-state ' + state;
    result.innerHTML = html;
  }

  async function lookup(id) {
    const pathName = recordType === 'user'
      ? 'users'
      : (recordType === 'checkin' ? 'checkins' : 'trips');
    const url = API_BASE + '/api/' + pathName + '/' + encodeURIComponent(id);
    button.disabled = true;
    show('empty', '查询中…');
    try {
      const res = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!res.ok) {
        if (res.status === 404) return show('empty', '未找到对应记录。');
        if (res.status === 401 || res.status === 403) return show('error', '查询接口需要登录，当前页面暂未接入。');
        return show('error', '查询失败（HTTP ' + res.status + '）');
      }
      const data = await res.json();
      const html = recordType === 'user'
        ? renderUser(data)
        : (recordType === 'checkin' ? renderCheckin(data) : renderTrip(data));
      show('result', '<div class="result-card">' + html + '</div>');
    } catch {
      show('error', '查询服务暂不可用，请稍后重试。');
    } finally {
      button.disabled = false;
    }
  }

  const tabs = document.querySelectorAll('[data-record-type]');
  const label = document.getElementById('lookupLabel');
  const help = document.getElementById('lookupHelp');

  function setType(type, updateUrl = true) {
    recordType = type === 'checkin' ? 'checkin' : 'trip';
    tabs.forEach((tab) => {
      const active = tab.dataset.recordType === recordType;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    if (label) label.textContent = recordType === 'checkin' ? '打卡 UUID' : '行程 UUID';
    if (help) {
      help.textContent = recordType === 'checkin'
        ? '打卡 UUID 可在导出数据中查看。'
        : '行程 UUID 可在导出数据或行程详情中查看。';
    }
    input.placeholder = recordType === 'checkin' ? '例如 550e8400-e29b-...' : '例如 7f3c2a1e-...';
    if (updateUrl) {
      const url = new URL(window.location.href);
      if (recordType === 'checkin') url.searchParams.set('type', 'checkin');
      else url.searchParams.delete('type');
      window.history.replaceState(null, '', url);
    }
  }

  tabs.forEach((tab) => tab.addEventListener('click', () => {
    setType(tab.dataset.recordType);
    const id = input.value.trim();
    if (id) lookup(id);
  }));

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const id = input.value.trim();
    if (!id) {
      show('error', recordType === 'user'
        ? '请输入用户 UUID。'
        : (recordType === 'checkin' ? '请输入打卡 UUID。' : '请输入行程 UUID。'));
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.set('id', id);
    if (recordType === 'checkin') url.searchParams.set('type', 'checkin');
    else url.searchParams.delete('type');
    window.history.replaceState(null, '', url);
    lookup(id);
  });

  if (tabs.length) setType(recordType, false);
  if (idParam) {
    input.value = idParam;
    lookup(idParam);
  }
})();
