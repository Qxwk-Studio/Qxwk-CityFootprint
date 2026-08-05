import { json, error, isValidNickname, isValidPassword } from '../_lib/utils.js';
import { hashPassword, createSession, assignColor } from '../_lib/auth.js';

export async function onRequestPost(context) {
  const { request, env } = context;
  const body = await request.json().catch(() => ({}));

  const nickname = String(body.nickname || '').trim();
  const password = String(body.password || '');

  if (!isValidNickname(nickname)) return error('昵称需为 1-20 个字符');
  if (!isValidPassword(password)) return error('密码需为 4-50 个字符');

  const existing = await env.DB.prepare('SELECT id FROM users WHERE nickname = ?')
    .bind(nickname).first();
  if (existing) return error('昵称已被占用，换一个吧', 409);

  const passwordHash = await hashPassword(password);
  const color = await assignColor(env.DB);

  const res = await env.DB.prepare(
    'INSERT INTO users (nickname, password_hash, color) VALUES (?, ?, ?)'
  ).bind(nickname, passwordHash, color).run();

  const userId = res.meta.last_row_id;
  const token = await createSession(env.DB, userId);

  return json({ token, userId, nickname, color });
}
