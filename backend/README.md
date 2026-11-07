# City Footprint · 后端

Cloudflare Worker + D1，提供通行证登录、公开查询和可选同步。

- Worker：`qxwk-cityfootprint`
- D1：`qxwk-cityfootprint`
- 线上域名：<https://api.travel.qxwkstudio.top>
- 同步表结构：`migrations-sync/0001_init.sql`

## 目录

```text
backend/
├── src/
│   ├── worker.js             # 路由、公开接口、通行证登录与 token 校验
│   ├── sync.js               # 设备注册、push、pull、快照
│   └── city-coordinates.js   # 城市坐标
├── migrations-sync/          # 同步库建表
├── test/                     # Node test
├── wrangler.toml
└── package.json
```

## 接口

### 公开

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| GET | `/api/manifest` | 安卓版本清单 |
| GET | `/api/server` | 服务器名称、照片上传能力 |
| GET | `/api/map` | 城市、坐标、adcode、最近 10 条记录 |
| GET | `/api/users/:uuid` | 用户公开资料与统计 |
| GET | `/api/trips/:uuid` | 行程详情 |
| GET | `/api/checkins/:uuid` | 打卡详情 |
| GET | `/api/geo/:adcode` | DataV 边界代理，7 天缓存 |

### 登录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 账号密码登录，转发通行证并返回 token |
| GET | `/api/auth/me` | Bearer token 校验 |

### 同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/sync/register` | 注册设备，返回 `device_id` |
| POST | `/api/sync/push` | 推送行程/手动打卡变更 |
| GET | `/api/sync/pull?since=` | 拉取增量 |

同步约定：

- entity_type：`trip` / `checkin`
- op：`upsert` / `delete`
- `revision` 为乐观锁，冲突返回 `status: "conflict"` 与远端 `data`
- 删除用 `deleted_at` 墓碑传播
- `since=0` 返回当前全量快照

快照是为了兼容直接迁入 D1、没有写 `sync_changes` 的旧数据；否则新设备永远拉不到历史记录。

## 环境变量

`wrangler.toml`：

```toml
name = "qxwk-cityfootprint"

[vars]
PASSPORT_BASE = "https://account.qxwkstudio.top"
PASSPORT_CLIENT_ID = "footprint-sync"
```

Secret：

```powershell
npx.cmd wrangler secret put PASSPORT_CLIENT_SECRET
```

## 开发

```powershell
cd backend
npm.cmd test
npx.cmd wrangler d1 migrations apply qxwk-cityfootprint --local
npx.cmd wrangler dev
```

## 测试

```powershell
cd backend
npm.cmd test
```

当前测试：

- 同步参数校验（行程、打卡）
- 新设备 `since=0` 快照

## 部署

```powershell
cd backend
npx.cmd wrangler deploy
```

线上结构/数据变更由维护者执行；部署前先确认 D1 与 Secret 正确。

## 已知限制

- 同步只覆盖行程和手动打卡
- 照片上传未启用（`supports_photo_upload=0`）
- 冲突由客户端整条选择，不做字段级合并
- 公开接口没有独立限流层
