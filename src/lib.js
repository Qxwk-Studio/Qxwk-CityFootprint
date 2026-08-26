// 工具与通行证 SSO 验证（Worker 版）
// 本站不再持有密码 / 会话，认证统一由通行证 account.qxwkstudio.top 处理
// 业务路由拿 Bearer token 去问通行证 /api/me，按 nickname 映射到本地用户

// 通行证地址（本地 dev 改 http://localhost:8787）
const PASSPORT_URL = 'https://account.qxwkstudio.top';
const TOKEN_CACHE_TTL = 120; // 秒：按 token 缓存通行证验证结果，避免每请求都打通行证

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

export function error(message, status = 400) {
  return json({ error: message }, status);
}

// 用 Bearer token 去通行证 /api/me 验证，返回 {userId, nickname, color} 或 null
// 结果按 token 缓存 TOKEN_CACHE_TTL 秒，token 在通行证侧注销后最多残留该时长
async function getPassportUser(request) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token) return null;
  const cacheKey = new Request(`https://cf-auth-cache.local/${token}`);
  const cached = await caches.default.get(cacheKey);
  if (cached) return await cached.json();
  try {
    const r = await fetch(`${PASSPORT_URL}/api/me`, {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!r.ok) return null;
    const u = await r.json();
    if (!u || !u.userId) return null;
    const body = { userId: u.userId, nickname: u.nickname, color: u.color };
    await caches.default.put(
      cacheKey,
      new Response(JSON.stringify(body), {
        headers: { 'Content-Type': 'application/json', 'Cache-Control': `private, max-age=${TOKEN_CACHE_TTL}` },
      })
    );
    return body;
  } catch {
    return null;
  }
}

// 通行证用户 -> 本地 CF 用户：首次自动建（颜色同步通行证），返回 {id, nickname, color, isAdmin} 或 null
async function resolveViewer(DB, request) {
  const p = await getPassportUser(request);
  if (!p) return null;
  await DB.prepare(
    "INSERT INTO users (nickname, color) VALUES (?, ?) ON CONFLICT(nickname) DO UPDATE SET color = excluded.color"
  ).bind(p.nickname, p.color).run();
  const u = await DB.prepare('SELECT id, nickname, color, is_admin FROM users WHERE nickname = ?')
    .bind(p.nickname).first();
  return u ? { id: u.id, nickname: u.nickname, color: u.color, isAdmin: !!u.is_admin } : null;
}

// 从 Authorization 解析本地用户 id（业务路由用，无 token / 验证失败返回 null）
export async function getUserId(DB, request) {
  const v = await resolveViewer(DB, request);
  return v ? v.id : null;
}

export { resolveViewer };