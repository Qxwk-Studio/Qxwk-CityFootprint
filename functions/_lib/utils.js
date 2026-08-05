// 通用响应工具

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

export function error(message, status = 400) {
  return json({ error: message }, status);
}

// 简单校验昵称/密码
export function isValidNickname(n) {
  return typeof n === 'string' && n.trim().length >= 1 && n.trim().length <= 20;
}

export function isValidPassword(p) {
  return typeof p === 'string' && p.length >= 4 && p.length <= 50;
}
