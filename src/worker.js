// Qxwk-CityFootprint · 拾光迹 Worker
// 一个 Worker 同时处理 /api/* 接口和静态资源（public/）
import {
  json, error,
  hashPassword, verifyPassword, createSession, getUserId, assignColor,
  isValidNickname, isValidPassword,
} from './lib.js';

// ---------- API 处理 ----------

// 当前请求用户：返回 { userId(未登录=0), isAdmin }
async function getViewer(DB, request) {
  const userId = (await getUserId(DB, request)) || 0;
  if (!userId) return { userId: 0, isAdmin: false };
  const u = await DB.prepare('SELECT is_admin FROM users WHERE id = ?').bind(userId).first();
  return { userId, isAdmin: !!(u && u.is_admin) };
}

// 生成一次性邀请码：8 位，去易混淆字符（I/O/0/1），32 字符表可整除 256 → 无偏
function generateInviteCode() {
  const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  let code = '';
  for (let i = 0; i < 8; i++) code += ALPHABET[bytes[i] % ALPHABET.length];
  return code;
}

// 读取系统设置（settings 键值表），无记录时返回默认值
async function getSetting(DB, key, def) {
  const row = await DB.prepare('SELECT value FROM settings WHERE key = ?').bind(key).first();
  return row ? row.value : def;
}

async function handleApi(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  const DB = env.DB;

  // POST /api/register（需一次性邀请码）
  if (method === 'POST' && path === '/api/register') {
    const body = await request.json().catch(() => ({}));
    const nickname = String(body.nickname || '').trim();
    const password = String(body.password || '');
    const inviteCode = String(body.invite_code || '').trim();

    if (!isValidNickname(nickname)) return error('昵称需为 1-20 个字符');
    if (!isValidPassword(password)) return error('密码需为 4-50 个字符');
    if (!inviteCode) return error('请填写邀请码');
    if ((await getSetting(DB, 'invite_register_enabled', '1')) !== '1') return error('注册已暂停，暂不接受新注册', 403);

    const existing = await DB.prepare('SELECT id FROM users WHERE nickname = ?').bind(nickname).first();
    if (existing) return error('昵称已被占用，换一个吧', 409);

    // 原子消耗一次性邀请码（用后即焚，防止并发重复使用）
    const consume = await DB.prepare(
      `UPDATE invite_codes SET used_at = datetime('now'), used_by = NULL
       WHERE code = ? AND used_at IS NULL`
    ).bind(inviteCode).run();
    if (consume.meta.changes === 0) return error('邀请码无效或已被使用', 403);

    const passwordHash = await hashPassword(password);
    const color = await assignColor(DB);
    const res = await DB.prepare('INSERT INTO users (nickname, password_hash, color) VALUES (?, ?, ?)')
      .bind(nickname, passwordHash, color).run();
    const userId = res.meta.last_row_id;
    // 回填实际用户 id
    await DB.prepare('UPDATE invite_codes SET used_by = ? WHERE code = ?')
      .bind(userId, inviteCode).run();
    const token = await createSession(DB, userId);
    return json({ token, userId, nickname, color });
  }

  // GET /api/config（公开：注册配置，供前端决定是否显示邀请码输入框）
  if (method === 'GET' && path === '/api/config') {
    const inviteGenerateEnabled = (await getSetting(DB, 'invite_generate_enabled', '1')) === '1';
    const inviteRegisterEnabled = (await getSetting(DB, 'invite_register_enabled', '1')) === '1';
    return json({ inviteCodeRequired: true, inviteGenerateEnabled, inviteRegisterEnabled });
  }

  // GET /api/invite-code（登录用户：取本人未使用的邀请码，没有则自动生成一个，便于溯源）
  if (method === 'GET' && path === '/api/invite-code') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);
    // 暂停邀请码生成时不再发码
    if ((await getSetting(DB, 'invite_generate_enabled', '1')) !== '1') {
      return json({ paused: true, code: null });
    }
    // 1) 查该用户未使用的邀请码
    let row = await DB.prepare(
      'SELECT code FROM invite_codes WHERE used_at IS NULL AND created_by = ? ORDER BY rowid ASC LIMIT 1'
    ).bind(userId).first();
    // 2) 没有则生成一个（写入 created_by 记录生成人，便于溯源）
    if (!row) {
      let code = generateInviteCode();
      let inserted = false;
      for (let i = 0; i < 5 && !inserted; i++) {
        try {
          await DB.prepare('INSERT INTO invite_codes (code, created_by) VALUES (?, ?)')
            .bind(code, userId).run();
          inserted = true;
        } catch (e) {
          code = generateInviteCode(); // 撞码（PRIMARY KEY 冲突）时换一个重试
        }
      }
      if (!inserted) return error('邀请码生成失败，请重试', 500);
      row = { code };
    }
    return json({ paused: false, code: row.code });
  }

  // GET /api/geo/:adcode（代理 DataV 边界接口，规避浏览器跨域/来源限制）
  const geoMatch = path.match(/^\/api\/geo\/(\d+)$/);
  if (method === 'GET' && geoMatch) {
    const adcode = geoMatch[1];
    try {
      // 市本级边界（覆盖全市辖区，单 feature，比 _full 含区县小约 75%）
      const upstream = `https://geo.datav.aliyun.com/areas_v3/bound/${adcode}.json`;
      const cacheKey = new Request(upstream);
      let resp = await caches.default.match(cacheKey);
      if (!resp) {
        // 超时控制：DataV 挂起时不无限等待
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
          // 用 clone 缓存（独立 body 流），避免与下方读取 resp.body 冲突
          const clone = resp.clone();
          const headers = new Headers(clone.headers);
          headers.set('Cache-Control', 'public, max-age=86400'); // 缓存 24h
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

  // POST /api/login
  if (method === 'POST' && path === '/api/login') {
    const body = await request.json().catch(() => ({}));
    const nickname = String(body.nickname || '').trim();
    const password = String(body.password || '');
    if (!nickname || !password) return error('请填写昵称和密码');

    const user = await DB.prepare('SELECT * FROM users WHERE nickname = ?').bind(nickname).first();
    if (!user) return error('昵称或密码不正确', 401);
    // 密码字段为 NULL（管理员已清空，待重置）：返回信号，由前端引导设置新密码
    if (!user.password_hash) {
      return json({ needSetPassword: true, nickname: user.nickname });
    }
    const ok = await verifyPassword(password, user.password_hash);
    if (!ok) return error('昵称或密码不正确', 401);

    const token = await createSession(DB, user.id);
    return json({ token, userId: user.id, nickname: user.nickname, color: user.color, is_admin: user.is_admin });
  }

  // POST /api/set-password（仅当密码字段为 NULL 时可设置；管理员清空后用于重置）
  if (method === 'POST' && path === '/api/set-password') {
    const body = await request.json().catch(() => ({}));
    const nickname = String(body.nickname || '').trim();
    const password = String(body.password || '');

    if (!isValidPassword(password)) return error('密码需为 4-50 个字符');
    const user = await DB.prepare('SELECT * FROM users WHERE nickname = ?').bind(nickname).first();
    if (!user) return error('用户不存在', 404);
    if (user.password_hash) return error('该账号已有密码，如需重置请联系管理员', 403);

    const passwordHash = await hashPassword(password);
    await DB.prepare('UPDATE users SET password_hash = ? WHERE id = ?').bind(passwordHash, user.id).run();
    const token = await createSession(DB, user.id);
    return json({ token, userId: user.id, nickname: user.nickname, color: user.color });
  }

  // GET /api/me（登录：返回当前用户信息，供每次访问网站时验证凭证是否仍有效）
  if (method === 'GET' && path === '/api/me') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);
    const user = await DB.prepare('SELECT id, nickname, color, is_admin FROM users WHERE id = ?').bind(userId).first();
    if (!user) return error('用户不存在', 401);
    return json({ userId: user.id, nickname: user.nickname, color: user.color, is_admin: user.is_admin });
  }

  // GET /api/cities（公开：地图数据；已登录用户可见自己的私密行程，管理员可见全部）
  if (method === 'GET' && path === '/api/cities') {
    const { userId, isAdmin } = await getViewer(DB, request);
    // 管理员：全部行程（含所有私密）；否则：公开 + 本人私密
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
    // 管理员统计全部（含私密），普通用户只统计公开行程
    const visFilter = isAdmin ? '' : 'WHERE is_private = 0';
    const joinFilter = isAdmin ? '' : 'AND v.is_private = 0';
    const totalVisits = (await DB.prepare(`SELECT COUNT(*) as c FROM visits ${visFilter}`).first()).c;
    const totalCities = (await DB.prepare(`SELECT COUNT(DISTINCT city) as c FROM visits ${visFilter}`).first()).c;
    const cityRank = await DB.prepare(
      `SELECT city, COUNT(*) as count, COUNT(DISTINCT user_id) as people FROM visits ${visFilter} GROUP BY city ORDER BY count DESC, city ASC`
    ).all();
    // 每用户去重城市列表（成就统计用，管理员含私密）
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
    // 倒序：最新的足迹排在上面（id 自增，越大越新）
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
      const result = await handleApi(request, env);
      return result || json({ error: '接口不存在' }, 404);
    }

    // 其余：静态资源（public/）
    return env.ASSETS.fetch(request);
  },
};
