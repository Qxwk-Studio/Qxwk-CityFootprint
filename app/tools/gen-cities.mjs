#!/usr/bin/env node
/**
 * Qxwk City Footprint · 城市数据导出（app/src/main/assets/cities.json）
 *
 * 为什么要一个脚本、而不是让 app 直接读前端的 JS：
 * 安卓端拿不到 window.CITIES，也不该在运行时解析 JS；但城市名/省份/坐标/行政区划代码是**跨端约定**
 * （网页版用同一份数据，地图边界靠 adcode 取），两边一旦分叉就会出现「App 里有的城市网页上没有」
 * 或者「坐标不同、同一座城打在两个位置」。所以城市表在仓库里只有一份源（frontend/*.js），
 * 这份 assets 是它的**生成物**，前端改了城市数据就重跑本脚本（见 app/README.md）。
 *
 * 零依赖：只用 node 内置模块 + 正则。
 * 为什么用正则而不是 import 那两个文件：它们是浏览器脚本（没有 export），
 * 为了「优雅地解析」而给仓库加一个打包器/转译器，比这四十行正则贵得多。
 *
 * 可重复运行：同样的输入一定产出逐字节相同的输出（不写时间戳 —— 那会让每次重跑都产生 diff）。
 *
 * 用到的两个源文件格式（改格式必须同步改这里）：
 *   frontend/cities.js     { name: '北京', province: '北京', lat: 39.904, lng: 116.407 },
 *   frontend/city-codes.js "北京": 110000,
 *
 * 跑法：node app/tools/gen-cities.mjs
 * 校验失败（条数对不上/重名）会以退出码 1 结束，不会写出半份数据。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const citiesSource = resolve(repoRoot, 'frontend', 'cities.js');
const codesSource = resolve(repoRoot, 'frontend', 'city-codes.js');
const outFile = resolve(here, '..', 'src', 'main', 'assets', 'cities.json');

/** 一条城市记录。lat/lng 必须都是数字，借此天然跳过文件头注释里那份「示例」。 */
const CITY_RE = /\{\s*name:\s*'([^']+)'\s*,\s*province:\s*'([^']+)'\s*,\s*lat:\s*(-?\d+(?:\.\d+)?)\s*,\s*lng:\s*(-?\d+(?:\.\d+)?)\s*\}/g;
/**
 * 用来独立数一遍条数：与上面解析出的条数必须一致，否则说明正则漏了格式变体。
 * 锚在行首（`^\s*{`）是必须的：文件头的注释里也有一句「// 结构：[{ name: '市名', ...」，
 * 不锚行首就会把示例也算成一条记录，条数校验永远差 1。
 */
const CITY_ENTRY_RE = /^\s*\{\s*name:\s*'/gm;
/** city-codes.js 的一行："北京": 110000, */
const CODE_RE = /^\s*"([^"]+)"\s*:\s*(\d+)\s*,?\s*$/gm;

const read = (file) => readFileSync(file, 'utf8');
const rel = (file) => relative(repoRoot, file).replace(/\\/g, '/');

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

// ── 解析 ──
const citiesText = read(citiesSource);
const codesText = read(codesSource);

const cities = [];
for (const m of citiesText.matchAll(CITY_RE)) {
  cities.push({
    name: m[1],
    province: m[2],
    lat: Number(m[3]),
    lng: Number(m[4]),
  });
}

const codes = new Map();
for (const m of codesText.matchAll(CODE_RE)) {
  codes.set(m[1], Number(m[2]));
}

// ── 校验（宁可失败，也不要写出一份悄悄缺城市的 assets）──
const rawEntries = [...citiesText.matchAll(CITY_ENTRY_RE)].length;
if (cities.length !== rawEntries) {
  fail(`cities.js 里有 ${rawEntries} 条 { name: ... }，但只解析出 ${cities.length} 条 —— 格式变了，请同步改 CITY_RE`);
}
if (codes.size === 0) fail('city-codes.js 一条都没解析出来 —— 格式变了，请同步改 CODE_RE');

const seen = new Set();
for (const c of cities) {
  if (seen.has(c.name)) fail(`cities.js 里有重复城市名：${c.name}`);
  seen.add(c.name);
  if (!Number.isFinite(c.lat) || !Number.isFinite(c.lng)) fail(`${c.name} 的坐标不是数字`);
  // 中国的经纬度大致范围，防止 lat/lng 写反（写反了地图上会跑到境外，肉眼很难往回查）
  if (c.lat < 3 || c.lat > 54 || c.lng < 73 || c.lng > 136) {
    fail(`${c.name} 的坐标 (${c.lat}, ${c.lng}) 超出中国范围，检查 lat/lng 是否写反`);
  }
}

// ── 合并 adcode ──
// 源数据里有县级市（格尔木、伊宁…）在 city-codes.js 里没有记录：adcode 留 null，
// app 侧据此把地图降级成「只显示标记」（见 logic/City.kt 与 ui/MapFragment）。
const usedCodes = new Set();
let withAdcode = 0;
const merged = cities.map((c) => {
  const adcode = codes.get(c.name) ?? null;
  if (adcode !== null) {
    withAdcode += 1;
    if (usedCodes.has(adcode)) {
      // 只有警告：同一个 adcode 落到两个城市名下是数据问题，但还不至于拦下整次导出
      console.warn(`! adcode ${adcode} 被多个城市共用（${c.name}）`);
    }
    usedCodes.add(adcode);
  }
  return { name: c.name, province: c.province, lat: c.lat, lng: c.lng, adcode };
});

const payload = {
  // 生成物标记 + 来源：给以后翻到这个 JSON 的人一条线索（JSON 不支持注释，只能写进字段）
  source: 'frontend/cities.js + frontend/city-codes.js',
  generator: 'app/tools/gen-cities.mjs',
  count: merged.length,
  withAdcode,
  cities: merged,
};

// ── 落盘 ──
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');

// ── 报告 ──
const missing = merged.filter((c) => c.adcode === null);
const unusedCodes = [...codes.keys()].filter((name) => !seen.has(name));
console.log(`✓ ${rel(outFile)}`);
console.log(`  城市 ${merged.length} 条（${rel(citiesSource)} ${rawEntries} 条），其中 ${withAdcode} 条有 adcode`);
console.log(`  缺 adcode ${missing.length} 条：${missing.map((c) => c.name).join('、') || '无'}`);
if (unusedCodes.length) {
  console.log(`  未被城市表用到的 adcode ${unusedCodes.length} 条（城市表里没有这些名字）：${unusedCodes.join('、')}`);
}