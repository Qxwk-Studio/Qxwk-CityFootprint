// 认证与密码工具（Web Crypto / PBKDF2）
import { error } from './utils.js';

// 每个人分配的地图颜色（色相差开，便于区分）
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

function toHex(buf) {
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

function randomBytes(n) {
  const arr = new Uint8Array(n);
  crypto.getRandomValues(arr);
  return arr;
}

// PBKDF2 密码哈希，返回 "salt:hash"（salt 为 16 字节 hex）
export async function hashPassword(password) {
  const salt = toHex(randomBytes(16));
  const keyMaterial = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(password),
    'PBKDF2', false, ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: new TextEncoder().encode(salt),
      iterations: 100000,
      hash: 'SHA-256',
    },
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
    {
      name: 'PBKDF2',
      salt: new TextEncoder().encode(salt),
      iterations: 100000,
      hash: 'SHA-256',
    },
    keyMaterial, 256
  );
  const computed = toHex(bits);
  return computed === hash;
}

// 生成会话 token（32 字节 hex）
export function generateToken() {
  return toHex(randomBytes(32));
}

// 建立会话，返回 token
export async function createSession(DB, userId) {
  const token = generateToken();
  await DB.prepare('INSERT INTO sessions (token, user_id) VALUES (?, ?)')
    .bind(token, userId).run();
  return token;
}

// 从请求中解析用户 id（Authorization: Bearer <token>）
// 无效返回 null
export async function getUserId(env, request) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token) return null;
  const row = await env.DB.prepare('SELECT user_id FROM sessions WHERE token = ?')
    .bind(token).first();
  return row ? row.user_id : null;
}

// 需要登录的接口包装
export async function requireUser(env, request) {
  const userId = await getUserId(env, request);
  if (!userId) {
    return { error: error('未登录', 401) };
  }
  return { userId };
}

// 为注册用户分配颜色
export async function assignColor(DB) {
  const { count } = await DB.prepare('SELECT COUNT(*) as count FROM users').first();
  return USER_COLORS[count % USER_COLORS.length];
}
