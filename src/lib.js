// 认证与工具（Worker 版）
// 密码哈希使用 Web Crypto PBKDF2，无需外部依赖

export const USER_COLORS = [
  '#2563eb', // 蓝
  '#dc2626', // 红
  '#16a34a', // 绿
  '#d97706', // 橙
  '#7c3aed', // 紫
  '#db2777', // 粉
  '#0891b2', // 青
  '#65a30d', // 黄绿
  '#ea580c', // 橙红
  '#4f46e5', // 靛
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

// 注册时分配颜色
export async function assignColor(DB) {
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
