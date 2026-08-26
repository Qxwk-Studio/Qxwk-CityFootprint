// Qxwk-CityFootprint · 拾光迹 Worker
// 一个 Worker 同时处理 /api/* 接口和静态资源（public/）
// 认证由通行证 account.qxwkstudio.top 统一管理（SSO），本站不再持有密码/会话
import { json, error, getUserId, resolveViewer } from './lib.js';

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
    const u = await DB.prepare('SELECT created_at FROM users WHERE id = ?').bind(v.id).first();
    return json({ userId: v.id, nickname: v.nickname, color: v.color, is_admin: v.isAdmin, created_at: u && u.created_at });
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
      return json(await resp.json());
    } catch (e) {
      return error('边界获取失败', 502);
    }
  }

  // GET /api/cities（公开：地图数据；已登录用户可见自己的私密行程，管理员可见全部）
  if (method === 'GET' && path === '/api/cities') {
    const { userId, isAdmin } = await getViewer(DB, request);
    const sql = `SELECT v.id, v.city, v.lat, v.lng, v.visit_date, v.note, v.is_private,
                        u.id AS user_id, u.nickname, u.color
                 FROM visits v JOIN users u ON v.user_id = u.id
                 ${isAdmin ? '' : 'WHERE v.is_private = 0 OR v.user_id = ?'}
                 ORDER BY v.created_at ASC`;
    const rows = await DB.prepare(sql).bind(...(isAdmin ? [] : [userId])).all();
    const cityMap = new Map();
    for (const r of rows.results) {
      if (!cityMap.has(r.city)) {
        cityMap.set(r.city, { city: r.city, lat: r.lat, lng: r.lng, people: [] });
      }
      cityMap.get(r.city).people.push({
        nickname: r.nickname,
        color: r.color,
        visit_date: r.visit_date,
        note: r.note,
        is_private: r.is_private,
      });
    }
    return json({ cities: [...cityMap.values()], isAdmin });
  }

  // GET /api/stats（公开：全站统计；管理员统计全部行程含私密）
  if (method === 'GET' && path === '/api/stats') {
    const { isAdmin } = await getViewer(DB, request);
    const visFilter = isAdmin ? '' : 'WHERE is_private = 0';
    const joinFilter = isAdmin ? '' : 'AND v.is_private = 0';
    const totalVisits = (await DB.prepare(`SELECT COUNT(*) as c FROM visits ${visFilter}`).first()).c;
    const totalCities = (await DB.prepare(`SELECT COUNT(DISTINCT city) as c FROM visits ${visFilter}`).first()).c;
    const cityRank = await DB.prepare(
      `SELECT city, COUNT(*) as count, COUNT(DISTINCT user_id) as people FROM visits ${visFilter} GROUP BY city ORDER BY count DESC, city ASC`
    ).all();
    const users = await DB.prepare(
      `SELECT u.nickname, u.color, COALESCE(GROUP_CONCAT(DISTINCT v.city), '') as cities
       FROM users u LEFT JOIN visits v ON v.user_id = u.id ${joinFilter}
       GROUP BY u.id ORDER BY u.id`
    ).all();
    return json({ totalVisits, totalCities, cityRank: cityRank.results, users: users.results, isAdmin });
  }

  // GET /api/user/:nickname（公开：某人足迹）
  const userMatch = path.match(/^\/api\/user\/([^/]+)$/);
  if (method === 'GET' && userMatch) {
    const nickname = decodeURIComponent(userMatch[1]);
    const user = await DB.prepare('SELECT id, nickname, color, created_at FROM users WHERE nickname = ?')
      .bind(nickname).first();
    if (!user) return error('用户不存在', 404);

    const isAdmin = (await getViewer(DB, request)).isAdmin;
    const visits = await DB.prepare(
      `SELECT id, city, lat, lng, visit_date, note FROM visits WHERE user_id = ? ${isAdmin ? '' : 'AND is_private = 0'} ORDER BY created_at ASC`
    ).bind(user.id).all();
    return json({ user: { id: user.id, nickname: user.nickname, color: user.color, created_at: user.created_at }, visits: visits.results });
  }

  // GET /api/my-visits（登录）
  if (method === 'GET' && path === '/api/my-visits') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);
    const visits = await DB.prepare(
      'SELECT id, city, lat, lng, visit_date, note, is_private FROM visits WHERE user_id = ? ORDER BY id DESC'
    ).bind(userId).all();
    return json({ visits: visits.results });
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

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    const res = await DB.prepare(
      'INSERT INTO visits (user_id, city, lat, lng, visit_date, note, is_private) VALUES (?, ?, ?, ?, ?, ?, ?)'
    ).bind(userId, city, lat, lng, visitDate, note, isPrivate).run();
    return json({ id: res.meta.last_row_id, city, lat, lng, visit_date: visitDate, note, is_private: isPrivate }, 201);
  }

  // PUT/DELETE /api/visits/:id（登录：仅本人）
  const visitMatch = path.match(/^\/api\/visits\/(\d+)$/);
  if (visitMatch && (method === 'PUT' || method === 'DELETE')) {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);

    const id = Number(visitMatch[1]);
    const visit = await DB.prepare('SELECT * FROM visits WHERE id = ?').bind(id).first();
    if (!visit) return error('记录不存在', 404);
    if (visit.user_id !== userId) return error('无权操作他人的记录', 403);

    if (method === 'DELETE') {
      await DB.prepare('DELETE FROM visits WHERE id = ?').bind(id).run();
      return json({ ok: true });
    }

    const body = await request.json().catch(() => ({}));
    const city = String(body.city || '').trim();
    const lat = Number(body.lat);
    const lng = Number(body.lng);
    const visitDate = body.visit_date || null;
    const note = String(body.note || '').trim().slice(0, 100);
    const isPrivate = body.is_private ? 1 : 0;

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    await DB.prepare('UPDATE visits SET city = ?, lat = ?, lng = ?, visit_date = ?, note = ?, is_private = ? WHERE id = ?')
      .bind(city, lat, lng, visitDate, note, isPrivate, id).run();
    return json({ id, city, lat, lng, visit_date: visitDate, note, is_private: isPrivate });
  }

  return null; // 不是已知 API 路由
}

// ---------- 入口 ----------

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // API 路由
    if (url.pathname.startsWith('/api/')) {
      try {
        const result = await handleApi(request, env);
        return result || json({ error: '接口不存在' }, 404);
      } catch (e) {
        return json({ error: '服务器错误: ' + (e && e.message ? e.message : String(e)) }, 500);
      }
    }

    // 其余：静态资源（public/）
    // 无扩展名的路径自动补 .html（如 /account -> /account.html）
    if (url.pathname !== '/' && !/\.[^/]+$/.test(url.pathname)) {
      const tryUrl = new URL(request.url);
      tryUrl.pathname = tryUrl.pathname.replace(/\/$/, '') + '.html';
      const tryResp = await env.ASSETS.fetch(new Request(tryUrl.toString(), request));
      if (tryResp.ok) return tryResp;
    }
    return env.ASSETS.fetch(request);
  },
};