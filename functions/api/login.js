import { json, error } from '../_lib/utils.js';
import { verifyPassword, createSession } from '../_lib/auth.js';

export async function onRequestPost(context) {
  const { request, env } = context;
  const body = await request.json().catch(() => ({}));

  const nickname = String(body.nickname || '').trim();
  const password = String(body.password || '');

  if (!nickname || !password) return error('请填写昵称和密码');

  const user = await env.DB.prepare('SELECT * FROM users WHERE nickname = ?')
    .bind(nickname).first();
  if (!user) return error('昵称或密码不正确', 401);

  const ok = await verifyPassword(password, user.password_hash);
  if (!ok) return error('昵称或密码不正确', 401);

  const token = await createSession(env.DB, user.id);
  return json({ token, userId: user.id, nickname: user.nickname, color: user.color });
}
