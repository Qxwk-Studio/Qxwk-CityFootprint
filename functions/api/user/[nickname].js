import { json, error } from '../../_lib/utils.js';

// 公开接口：查看某个人的足迹明细
export async function onRequestGet(context) {
  const { env, params } = context;
  const nickname = decodeURIComponent(params.nickname || '');

  const user = await env.DB.prepare('SELECT id, nickname, color, created_at FROM users WHERE nickname = ?')
    .bind(nickname).first();
  if (!user) return error('用户不存在', 404);

  const visits = await env.DB.prepare(
    'SELECT id, city, lat, lng, visit_date, note FROM visits WHERE user_id = ? ORDER BY created_at ASC'
  ).bind(user.id).all();

  return json({
    user: {
      id: user.id,
      nickname: user.nickname,
      color: user.color,
      created_at: user.created_at,
    },
    visits: visits.results,
  });
}
