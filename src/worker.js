// Qxwk-CityFootprint · 拾光迹 Worker
// 一个 Worker 同时处理 /api/* 接口和静态资源（public/）
import {
  json, error,
  hashPassword, verifyPassword, createSession, getUserId, assignColor,
  isValidNickname, isValidPassword,
} from './lib.js';

// ---------- 邀请码工具 ----------

// 生成一次性邀请码：8 位大写字母+数字，剔除易混淆的 0O1Il
const INVITE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function generateInviteCode() {
  const arr = new Uint8Array(8);
  crypto.getRandomValues(arr);
  let code = '';
  for (let i = 0; i < arr.length; i++) code += INVITE_CHARS[arr[i] % INVITE_CHARS.length];
  return code;
}

// ---------- API 处理 ----------

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

  // POST /api/invites（管理员：生成一次性邀请码）
  if (method === 'POST' && path === '/api/invites') {
    const body = await request.json().catch(() => ({}));
    if (body.admin_pass !== env.ADMIN_PASSWORD) return error('管理员密码错误', 401);

    const count = Math.max(1, Math.min(20, Number(body.count) || 1));
    const codes = [];
    const inserts = [];
    for (let i = 0; i < count; i++) {
      const code = generateInviteCode();
      codes.push(code);
      inserts.push(DB.prepare('INSERT OR IGNORE INTO invite_codes (code) VALUES (?)').bind(code));
    }
    await DB.batch(inserts);
    return json({ codes });
  }

  // GET /api/config（公开：注册配置，供前端决定是否显示邀请码输入框）
  if (method === 'GET' && path === '/api/config') {
    return json({ inviteCodeRequired: true });
  }

  // GET /api/geo/:adcode（代理 DataV 边界接口，规避浏览器跨域/来源限制）
  const geoMatch = path.match(/^\/api\/geo\/(\d+)$/);
  if (method === 'GET' && geoMatch) {
    const adcode = geoMatch[1];
    try {
      // 台湾(710000)无 _full 数据，用非 full 接口取全岛边界
      const url = adcode === '710000'
        ? 'https://geo.datav.aliyun.com/areas_v3/bound/710000.json'
        : `https://geo.datav.aliyun.com/areas_v3/bound/${adcode}_full.json`;
      const resp = await fetch(url);
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
    return json({ token, userId: user.id, nickname: user.nickname, color: user.color });
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
    const user = await DB.prepare('SELECT id, nickname, color FROM users WHERE id = ?').bind(userId).first();
    if (!user) return error('用户不存在', 401);
    return json({ userId: user.id, nickname: user.nickname, color: user.color });
  }

  // GET /api/cities（公开：地图数据）
  if (method === 'GET' && path === '/api/cities') {
    const rows = await DB.prepare(
      `SELECT v.id, v.city, v.lat, v.lng, v.visit_date, v.note,
              u.id AS user_id, u.nickname, u.color
       FROM visits v JOIN users u ON v.user_id = u.id
       ORDER BY v.created_at ASC`
    ).all();

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
      });
    }
    return json({ cities: [...cityMap.values()] });
  }

  // GET /api/user/:nickname（公开：某人足迹）
  const userMatch = path.match(/^\/api\/user\/([^/]+)$/);
  if (method === 'GET' && userMatch) {
    const nickname = decodeURIComponent(userMatch[1]);
    const user = await DB.prepare('SELECT id, nickname, color, created_at FROM users WHERE nickname = ?')
      .bind(nickname).first();
    if (!user) return error('用户不存在', 404);

    const visits = await DB.prepare(
      'SELECT id, city, lat, lng, visit_date, note FROM visits WHERE user_id = ? ORDER BY created_at ASC'
    ).bind(user.id).all();
    return json({ user: { id: user.id, nickname: user.nickname, color: user.color, created_at: user.created_at }, visits: visits.results });
  }

  // GET /api/my-visits（登录）
  if (method === 'GET' && path === '/api/my-visits') {
    const userId = await getUserId(DB, request);
    if (!userId) return error('未登录', 401);
    // 倒序：最新的足迹排在上面（id 自增，越大越新）
    const visits = await DB.prepare(
      'SELECT id, city, lat, lng, visit_date, note FROM visits WHERE user_id = ? ORDER BY id DESC'
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
    const note = String(body.note || '').trim().slice(0, 200);

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    const res = await DB.prepare(
      'INSERT INTO visits (user_id, city, lat, lng, visit_date, note) VALUES (?, ?, ?, ?, ?, ?)'
    ).bind(userId, city, lat, lng, visitDate, note).run();
    return json({ id: res.meta.last_row_id, city, lat, lng, visit_date: visitDate, note }, 201);
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
    const note = String(body.note || '').trim().slice(0, 200);

    if (!city || city.length > 30) return error('请选择城市');
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return error('城市坐标无效');
    if (visitDate !== null && !/^\d{4}(-\d{2})?$/.test(visitDate)) return error('日期格式应为 2024 或 2024-08');

    await DB.prepare('UPDATE visits SET city = ?, lat = ?, lng = ?, visit_date = ?, note = ? WHERE id = ?')
      .bind(city, lat, lng, visitDate, note, id).run();
    return json({ id, city, lat, lng, visit_date: visitDate, note });
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
