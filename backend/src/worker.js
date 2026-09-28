// Qxwk-CityFootprint · Worker
// 只提供 /api/* 接口：页面已搬去 GitHub Pages（仓库 docs/，域名 travel.qxwkstudio.top），
// 这个 Worker 挂在 api.travel.qxwkstudio.top 上，前后端不同源，故末尾统一挂 CORS 头
import { json, error, getUserId, resolveViewer, visibility } from './lib.js';
import { getAchievements, achievementCounts } from './achievements.js';
import { CITY_CODES } from './city-codes.js';

// ---------- 城市字典与出行方式 ----------

// 按城市名派生 adcode：命中字典就落库（前端地图据此取边界填色，web/app 都不用再自带映射）。
// 字典未命中（用户自造的城市名）留 NULL，**不因此拒绝请求** —— 前端本来就有「找不到就画圆点」的回退。
// 与 docs/city-codes.js 同源（都由 scripts/gen-city-codes.js 生成）。
function adcodeOf(city) {
  const code = CITY_CODES[city];
  return code == null ? null : String(code);
}

// 出行方式白名单 + 固定顺序：入库前过滤未知 code、按下面的顺序去重排序，落成 JSON 数组字符串。
// 顺序写死而不是沿用用户勾选顺序 —— 否则同一组选择会落出不同的字符串，比较/统计都不方便。
// code 列表必须与前端 docs/app.js 的 window.TRANSPORTS 保持一致。
const TRANSPORTS = ['plane', 'train', 'hsr', 'car', 'bus', 'ship', 'bike', 'walk', 'other'];

// 请求里的 transport（数组）→ 白名单内的 code 数组（无效输入返回空数组 = 不记录）
function pickTransports(value) {
  if (!Array.isArray(value)) return [];
  return TRANSPORTS.filter((t) => value.includes(t));
}

// 库里的 transport（JSON 数组字符串）→ 数组（NULL / 坏数据都回空数组，不让读取接口炸）
function parseTransports(raw) {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

// ---------- API 处理 ----------

// 当前请求用户：通行证验证后映射到本地用户，返回 {userId(未登录=0), isAdmin, nickname, color}
async function getViewer(DB, request) {
  const v = await resolveViewer(DB, request);
  return v
    ? { userId: v.id, isAdmin: v.isAdmin, nickname: v.nickname, color: v.color }
    : { userId: 0, isAdmin: false };
}

async function handleApi(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  const DB = env.DB;

  // GET /api/me（登录：返回本地用户信息，token 经通行证验证）
  if (method === 'GET' && path === '/api/me') {
    const v = await resolveViewer(DB, request);
    if (!v) return error('未登录', 401);
    const u = await DB.prepare('SELECT created_at FROM cf_users WHERE id = ?').bind(v.id).first();
    return json({ userId: v.id, nickname: v.nickname, color: v.color, is_admin: v.isAdmin, created_at: u && u.created_at, avatar: v.avatar });
  }
  // GET /api/geo/:adcode（代理 DataV 边界接口，规避浏览器跨域/来源限制）
  const geoMatch = path.match(/^\/api\/geo\/(\d+)$/);
  if (method === 'GET' && geoMatch) {
    const adcode = geoMatch[1];
    try {
      const upstream = `https://geo.datav.aliyun.com/areas_v3/bound/${adcode}.json`;
      const cacheKey = new Request(upstream);
      let resp = await caches.default.match(cacheKey);
      if (!resp) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 10000);
        try {
          resp = await fetch(upstream, { signal: controller.signal });
        } catch (e) {
          clearTimeout(timer);
          return error('边界获取超时', 504);
        }
        clearTimeout(timer);
        if (resp.ok) {
          const clone = resp.clone();
          const headers = new Headers(clone.headers);
          headers.set('Cache-Control', 'public, max-age=86400');
          await caches.default.put(cacheKey, new Response(clone.body, {
            status: clone.status, statusText: clone.statusText, headers,
          }));
        }
      }
      if (!resp.ok) return error('边界获取失败', resp.status);
      // 返回给浏览器的这一份也要带 24h：上面 put 进 caches.default 的是**缓存副本**，它头上的
      // max-age 只管 Worker 内部那份；json() 现造的这个响应原先一个缓存头都没有，浏览器 HTTP
      // 缓存完全不吃（只剩前端 IndexedDB 顶 24h）。补上后三层 TTL 一致。
      return json(await resp.json(), 200, { 'Cache-Control': 'public, max-age=86400' });
    } catch (e) {
      return error('边界获取失败', 502);
    }
  }

  // GET /api/cities（公开：地图数据；已登录用户可见自己的私密行程，管理员可见全部）
  // 只回「城市 + 坐标 + 去过的人（昵称/颜色）」—— 这三样正是地图上色和「谁的足迹」图例筛选取的。
  // 日期 / 备注 / 私密标记这些明细不在这里铺开：点开某座城市时再调 GET /api/cities/{城市名} 按需拉。
  if (method === 'GET' && path === '/api/cities') {
    const viewer = await getViewer(DB, request);
    const vis = visibility(viewer, 'v.is_private', 'v.user_id');
    const rows = await DB.prepare(
      `SELECT v.city, v.adcode, v.lat, v.lng, v.created_at, u.nickname, u.color
       FROM cf_visits v JOIN cf_users u ON v.user_id = u.id
       ${vis.sql ? `WHERE ${vis.sql}` : ''}
       ORDER BY v.created_at ASC, v.id ASC`
    ).bind(...vis.params).all();

    // people 保持 created_at 升序（末位 = 最新），客户端沿用「取 people 末位的颜色」上色；
    // last 只是排序用的临时字段，下面会剥掉
    const cityMap = new Map();
    for (const r of rows.results) {
      if (!cityMap.has(r.city)) {
        // adcode 优先取库里存的，旧行（加列前）没有就按城市名字典兜底 → 客户端拿到即可直接取边界
        cityMap.set(r.city, { city: r.city, adcode: r.adcode || adcodeOf(r.city), lat: r.lat, lng: r.lng, people: [] });
      }
      const entry = cityMap.get(r.city);
      entry.people.push({ nickname: r.nickname, color: r.color });
      entry.last = r.created_at;
    }
    // 最近有活动的城市排在后（绘制时盖在上层）
    const cities = [...cityMap.values()]
      .sort((a, b) => (a.last < b.last ? -1 : a.last > b.last ? 1 : 0))
      .map(({ last, ...c }) => c);
    return json({ cities, isAdmin: viewer.isAdmin });
  }

  // GET /api/cities/:city（公开：某座城市的最近 10 条行程，供地图弹窗按需拉取）
  // 城市名直接当路径参数 —— 库里只有 city 名、没有 adcode（见 migrations/0001_init.sql），
  // adcode 由客户端用本地城市表映射。只给最新 10 条，且**不按 visit_date 排**：
  // 它可空、还允许只填年份，拿它排序口径会打架。
  const cityMatch = path.match(/^\/api\/cities\/(.+)$/);
  if (method === 'GET' && cityMatch) {
    let city = '';
    try {
      city = decodeURIComponent(cityMatch[1]).trim();
    } catch {
      return error('城市名无效', 400);
    }
    if (!city || city.length > 30) return error('城市名无效', 400);

    const viewer = await getViewer(DB, request);
    const vis = visibility(viewer, 'v.is_private', 'v.user_id');
    const rows = await DB.prepare(
      `SELECT u.nickname, u.color, v.adcode, v.visit_date, v.note, v.is_private, v.transport
       FROM cf_visits v JOIN cf_users u ON v.user_id = u.id
       WHERE v.city = ? ${vis.sql ? `AND ${vis.sql}` : ''}
       ORDER BY v.created_at DESC, v.id DESC LIMIT 10`
    ).bind(city, ...vis.params).all();
    // 该城市没有记录也回 200 + 空数组：空不是错误。
    // transport 从 JSON 字符串还原成数组再给前端（前端不做 JSON.parse 的活）；
    // adcode 逐条兜底成字典值，旧行也有值。
    const visits = rows.results.map((r) => ({
      nickname: r.nickname, color: r.color, visit_date: r.visit_date, note: r.note, is_private: r.is_private,
      adcode: r.adcode || adcodeOf(city),
      transport: parseTransports(r.transport),
    }));
    return json({ city, visits });
  }

  // GET /api/stats（公开：全站统计；管理员统计全部行程含私密）
  // 成就达成人数由后端算（定义唯一一份在 achievements.js），所以**不再回 users[] 明细**，
  // 只回一个 totalUsers 数字 —— 客户端不再需要 users[].cities 去本地判定。
  // user 侧一律查 cf_users（本站独占）：原先查的共享 users 里还躺着 Qxwk-Blog 的行，totalUsers 会虚高。
  if (method === 'GET' && path === '/api/stats') {
    const viewer = await getViewer(DB, request);
    // 落 WHERE 用的片段（FROM cf_visits，无别名）与落 JOIN 的 ON 用的片段（别名 v）分开取
    const visVisit = visibility(viewer);
    const whereVisit = visVisit.sql ? `WHERE ${visVisit.sql}` : '';

    const totalVisits = (await DB.prepare(`SELECT COUNT(*) as c FROM cf_visits ${whereVisit}`).bind(...visVisit.params).first()).c;
    const totalCities = (await DB.prepare(`SELECT COUNT(DISTINCT city) as c FROM cf_visits ${whereVisit}`).bind(...visVisit.params).first()).c;
    const totalUsers = (await DB.prepare('SELECT COUNT(*) as c FROM cf_users').first()).c;
    const cityRank = await DB.prepare(
      `SELECT city, COUNT(*) as count, COUNT(DISTINCT user_id) as people FROM cf_visits ${whereVisit} GROUP BY city ORDER BY count DESC, city ASC`
    ).bind(...visVisit.params).all();

    // 每个用户的「去过的城市」集合，供成就判定。可见性片段必须落在 JOIN 的 ON 上，
    // 否则 LEFT JOIN 出来的 NULL 行会让 WHERE 判定为假、把零足迹的用户整行滤掉。
    const visJoin = visibility(viewer, 'v.is_private', 'v.user_id');
    const userCities = await DB.prepare(
      `SELECT COALESCE(GROUP_CONCAT(DISTINCT v.city), '') as cities
       FROM cf_users u LEFT JOIN cf_visits v ON v.user_id = u.id ${visJoin.sql ? `AND ${visJoin.sql}` : ''}
       GROUP BY u.id ORDER BY u.id`
    ).bind(...visJoin.params).all();
    const achievements = achievementCounts(userCities.results.map(r => (r.cities ? r.cities.split(',') : [])));

    return json({
      totalVisits, totalCities, totalUsers,
      cityRank: cityRank.results, achievements, isAdmin: viewer.isAdmin,
    });
  }

  // GET /api/my-visits（登录：我的行程 + 我的成就）
  // 成就直接用这一批**已经取出来的行**就地判定（城市名去重），零额外查询 ——
  // 这也是不单开 /api/my-achievements 的原因。
  if (method === 'GET' && path === '/api/my-visits') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);
    const visits = await DB.prepare(
      'SELECT id, city, adcode, lat, lng, visit_date, note, is_private, transport FROM cf_visits WHERE user_id = ? ORDER BY created_at DESC, id DESC'
    ).bind(userId).all();
    // transport 还原成数组、adcode 兜底（旧行没有），再一并给前端的列表 / 编辑弹窗用
    const rows = visits.results.map((v) => ({
      ...v, adcode: v.adcode || adcodeOf(v.city), transport: parseTransports(v.transport),
    }));
    return json({
      visits: rows,
      achievements: getAchievements(rows.map(v => v.city)),
    });
  }

  // POST /api/visits（登录：添加）
  if (method === 'POST' && path === '/api/visits') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);

    const body = await request.json().catch(() => ({}));
    const city = String(body.city || '').trim();
    const lat = Number(body.lat);
    const lng = Number(body.lng);
    const visitDate = body.visit_date || null;
    const note = String(body.note || '').trim().slice(0, 100);
    const isPrivate = body.is_private ? 1 : 0;
    const transport = pickTransports(body.transport);

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    // adcode 由后端按城市名字典派生（前端不用带），字典未命中留 NULL
    const adcode = adcodeOf(city);
    const transportJson = transport.length ? JSON.stringify(transport) : null;

    const res = await DB.prepare(
      'INSERT INTO cf_visits (user_id, city, adcode, lat, lng, visit_date, note, is_private, transport) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    ).bind(userId, city, adcode, lat, lng, visitDate, note, isPrivate, transportJson).run();
    return json({ id: res.meta.last_row_id, city, adcode, lat, lng, visit_date: visitDate, note, is_private: isPrivate, transport }, 201);
  }

  // PUT/DELETE /api/visits/:id（登录：仅本人）
  const visitMatch = path.match(/^\/api\/visits\/(\d+)$/);
  if (visitMatch && (method === 'PUT' || method === 'DELETE')) {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);

    const id = Number(visitMatch[1]);
    const visit = await DB.prepare('SELECT * FROM cf_visits WHERE id = ?').bind(id).first();
    if (!visit) return error('记录不存在', 404);
    if (visit.user_id !== userId) return error('无权操作他人的记录', 403);

    if (method === 'DELETE') {
      await DB.prepare('DELETE FROM cf_visits WHERE id = ?').bind(id).run();
      return json({ ok: true });
    }

    const body = await request.json().catch(() => ({}));
    const city = String(body.city || '').trim();
    const lat = Number(body.lat);
    const lng = Number(body.lng);
    const visitDate = body.visit_date || null;
    const note = String(body.note || '').trim().slice(0, 100);
    const isPrivate = body.is_private ? 1 : 0;
    const transport = pickTransports(body.transport);

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    // 与 POST 同：adcode 按（可能改过的）城市名重算，transport 过滤白名单后落 JSON
    const adcode = adcodeOf(city);
    const transportJson = transport.length ? JSON.stringify(transport) : null;

    await DB.prepare('UPDATE cf_visits SET city = ?, adcode = ?, lat = ?, lng = ?, visit_date = ?, note = ?, is_private = ?, transport = ? WHERE id = ?')
      .bind(city, adcode, lat, lng, visitDate, note, isPrivate, transportJson, id).run();
    return json({ id, city, adcode, lat, lng, visit_date: visitDate, note, is_private: isPrivate, transport });
  }

  return null; // 不是已知 API 路由
}

// ---------- CORS ----------
// 前端（GitHub Pages，travel.qxwkstudio.top）与这个 Worker（api.travel.qxwkstudio.top）不同源。
// 前端请求带 Authorization / Content-Type 这类非简单头，浏览器会**先发 OPTIONS 预检**，
// 预检不过响应连读都读不到，所以接口必须显式放行。
//
// 用白名单而不是 `*`：本站身份靠 Bearer token（不用 cookie），本来就不存在「靠 CORS 挡人」的
// 安全边界，但也没必要让任意站点都能读响应。本地那两个 origin 是给「本地起静态服务器调试
// Pages 前端、接口仍打线上」用的 —— 端口不固定，所以用正则而不是写死端口。
const ALLOWED_ORIGINS = [
  /^https:\/\/travel\.qxwkstudio\.top$/,
  /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/,
];

// 命中白名单才给头；不在白名单返回 null（不给头，浏览器侧自然被拦，服务端不额外报错）
function corsHeaders(request) {
  const origin = request.headers.get('Origin');
  if (!origin || !ALLOWED_ORIGINS.some((re) => re.test(origin))) return null;
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Max-Age': '86400',
    // 回显 Origin 就必须带 Vary：否则 CDN / 浏览器可能把 A 来源的响应用给 B 来源
    Vary: 'Origin',
  };
}

// 把 CORS 头贴到任意响应上。4xx/5xx 也要贴：不贴的话浏览器只报 "CORS error"，
// 真正的状态码和错误信息（比如 401 未登录、403 越权）前端根本看不到。
function withCors(resp, request) {
  const extra = corsHeaders(request);
  if (!extra) return resp;
  const headers = new Headers(resp.headers);
  for (const [k, v] of Object.entries(extra)) headers.set(k, v);
  return new Response(resp.body, { status: resp.status, statusText: resp.statusText, headers });
}

// ---------- 入口 ----------

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // API 路由
    if (url.pathname.startsWith('/api/')) {
      // OPTIONS 预检：直接回 204，不进业务路由（预检不带 Authorization，进了也只会拿到 401）
      if (request.method === 'OPTIONS') {
        const headers = corsHeaders(request);
        return headers ? new Response(null, { status: 204, headers }) : new Response(null, { status: 403 });
      }
      try {
        const result = await handleApi(request, env);
        return withCors(result || json({ error: '接口不存在' }, 404), request);
      } catch (e) {
        return withCors(json({ error: '服务器错误: ' + (e && e.message ? e.message : String(e)) }, 500), request);
      }
    }

    // 其余路径一律 404：本 Worker 只提供接口，页面全在 GitHub Pages 上。
    // （这里原先回落到 env.ASSETS 取静态资源；前端搬走后 [assets] 绑定已从 wrangler.toml 删除，
    //   再留着这句会拿 undefined 去 fetch 并抛错。）
    return withCors(json({ error: '接口不存在' }, 404), request);
  },
};
