// 认证与工具（Worker 版）
// 密码哈希使用 Web Crypto PBKDF2，无需外部依赖

// 30 种 Material 调色板，保证新用户颜色不重复（直到池子占满）
export const USER_COLORS = [
  '#2563eb', '#dc2626', '#16a34a', '#d97706', '#7c3aed', '#db2777', '#0891b2', '#65a30d', '#ea580c', '#4f46e5',
  '#0ea5e9', '#f43f5e', '#22c55e', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4', '#84cc16', '#fb7185', '#6366f1',
  '#14b8a6', '#ef4444', '#a3e635', '#f97316', '#a855f7', '#d946ef', '#10b981', '#eab308', '#f472b6', '#3b82f6',
];

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

export function error(message, status = 400) {
  return json({ error: message }, status);
}

function toHex(buf) {
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

function randomBytes(n) {
  const arr = new Uint8Array(n);
  crypto.getRandomValues(arr);
  return arr;
}

// PBKDF2 密码哈希，返回 "salt:hash"
export async function hashPassword(password) {
  const salt = toHex(randomBytes(16));
  const keyMaterial = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(password),
    'PBKDF2', false, ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: new TextEncoder().encode(salt), iterations: 100000, hash: 'SHA-256' },
    keyMaterial, 256
  );
  return salt + ':' + toHex(bits);
}

export async function verifyPassword(password, stored) {
  const [salt, hash] = String(stored || '').split(':');
  if (!salt || !hash) return false;
  const keyMaterial = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(password),
    'PBKDF2', false, ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: new TextEncoder().encode(salt), iterations: 100000, hash: 'SHA-256' },
    keyMaterial, 256
  );
  return toHex(bits) === hash;
}

function generateToken() {
  return toHex(randomBytes(32));
}

export async function createSession(DB, userId) {
  const token = generateToken();
  await DB.prepare('INSERT INTO sessions (token, user_id) VALUES (?, ?)')
    .bind(token, userId).run();
  return token;
}

// 从 Authorization: Bearer <token> 解析用户 id
export async function getUserId(DB, request) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token) return null;
  const row = await DB.prepare('SELECT user_id FROM sessions WHERE token = ?').bind(token).first();
  return row ? row.user_id : null;
}

// 注册时分配颜色：优先从池中挑一个未被任何用户使用的颜色（不重复）
export async function assignColor(DB) {
  const rows = await DB.prepare('SELECT color FROM users').all();
  const used = new Set(rows.results.map(r => r.color));
  const available = USER_COLORS.filter(c => !used.has(c));
  if (available.length > 0) {
    return available[Math.floor(Math.random() * available.length)];
  }
  // 池子已被占满（超过 30 个用户）：按用户数取模兜底
  const { count } = await DB.prepare('SELECT COUNT(*) as count FROM users').first();
  return USER_COLORS[count % USER_COLORS.length];
}

// 简单校验
export function isValidNickname(n) {
  return typeof n === 'string' && n.trim().length >= 1 && n.trim().length <= 20;
}
export function isValidPassword(p) {
  return typeof p === 'string' && p.length >= 4 && p.length <= 50;
}
