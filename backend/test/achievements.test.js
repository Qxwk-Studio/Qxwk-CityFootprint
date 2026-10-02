// 成就判定与达成人数统计的检查。
// 定义只有一份（src/achievements.js）—— app 侧那份 Kotlin 判定表连同它的 10 个单测一起删除后，
// 等价的用例搬到了这里。判定逻辑一旦改动，这个文件是唯一的守门人。
import test from 'node:test';
import assert from 'node:assert/strict';
import { getAchievements, achievementCounts } from '../src/achievements.js';

const items = cities => getAchievements(cities).flatMap(c => c.items);
const find = (cities, code) => items(cities).find(a => a.code === code);
const doneCodes = cities => items(cities).filter(a => a.done).map(a => a.code);
const fake = k => Array.from({ length: k }, (_, i) => '城' + i);

test('空城市集：一个成就都没达成', () => {
  assert.deepEqual(doneCodes([]), []);
});

test('城市数量阈值逐级点亮，差一座就不给', () => {
  assert.equal(find(fake(1), 'first_trip').done, false);
  assert.equal(find(fake(2), 'first_trip').done, true);
  assert.equal(find(fake(4), 'city_roamer').done, false);
  assert.equal(find(fake(5), 'city_roamer').done, true);
  assert.equal(find(fake(9), 'footprint_hunter').done, false);
  assert.equal(find(fake(10), 'footprint_hunter').done, true);
  assert.equal(find(fake(25), 'travel_regular').done, true);
  assert.equal(find(fake(50), 'globe_trotter').done, true);
  assert.equal(find(fake(100), 'city_collector').done, true);
  assert.equal(find(fake(200), 'city_king').done, true);
  assert.equal(find(fake(292), 'grand_tour').done, false);
  assert.equal(find(fake(293), 'grand_tour').done, true);
});

test('走遍全国：数据集全量（384）到顶才给', () => {
  assert.equal(find(fake(383), 'all_regions').done, false);
  assert.equal(find(fake(384), 'all_regions').done, true);
});

test('同一座城市重复去只算一座', () => {
  assert.equal(find(['北京', '北京', '北京'], 'first_trip').done, false);
});

test('都市集章者：四城全含才给，缺一不可', () => {
  assert.equal(find(['北京', '上海', '广州', '深圳'], 'metro_all').done, true);
  assert.equal(find(['北京', '上海', '广州'], 'metro_all').done, false);
});

test('直辖市览胜 / 港澳穿梭：同为「全含」类', () => {
  assert.equal(find(['北京', '天津', '上海', '重庆'], 'municipality_all').done, true);
  assert.equal(find(['北京', '天津', '上海'], 'municipality_all').done, false);
  assert.equal(find(['香港', '澳门'], 'hk_macau').done, true);
  assert.equal(find(['香港'], 'hk_macau').done, false);
});

test('八大古都：全含才给', () => {
  const all = ['西安', '洛阳', '北京', '南京', '开封', '杭州', '安阳', '郑州'];
  assert.equal(find(all, 'ancient_capitals').done, true);
  assert.equal(find(all.slice(1), 'ancient_capitals').done, false);
});

test('「任一即达成」类：命中一座就够', () => {
  assert.equal(find(['徐州'], 'city_xuzhou').done, true);
  assert.equal(find(['苏州'], 'city_xuzhou').done, false);
  assert.equal(find(['拉萨'], 'plateau_city').done, true);
  assert.equal(find(['大连'], 'coastal_city').done, true);
  assert.equal(find(['三沙'], 'south_end_sansha').done, true);
  assert.equal(find(['大兴安岭'], 'north_end_daxinganling').done, true);
});

test('吐鲁番同时点亮「盆地之渊」与「火洲炼狱」', () => {
  const codes = doneCodes(['吐鲁番']);
  assert.ok(codes.includes('lowest_turpan'), '应点亮盆地之渊');
  assert.ok(codes.includes('hottest_turpan'), '应点亮火洲炼狱');
});

test('共 43 条成就、4 个分类', () => {
  const cats = getAchievements([]);
  assert.equal(cats.length, 4);
  assert.equal(cats.flatMap(c => c.items).length, 43);
});

test('code 全局唯一 —— 统计页拿它当计数键的前提', () => {
  const codes = items([]).map(a => a.code);
  assert.equal(new Set(codes).size, codes.length);
});

test('每条成就的展示字段都齐全（网页/app 要靠它渲染，缺一不可）', () => {
  for (const a of items([])) {
    assert.ok(a.code, 'code 缺失');
    assert.ok(a.icon, a.code + ' 缺 icon');
    assert.ok(a.name, a.code + ' 缺 name');
    assert.ok(a.desc, a.code + ' 缺 desc');
  }
});

test('achievementCounts：按人累计，且骨架保留全部 43 条', () => {
  const all = achievementCounts([['北京', '上海'], ['北京'], []]).flatMap(c => c.items);
  assert.equal(all.length, 43);
  const get = code => all.find(a => a.code === code).count;
  assert.equal(get('first_trip'), 1); // 只有第一位用户 ≥2 城
  assert.equal(get('city_xuzhou'), 0); // 没人去过徐州
});

test('achievementCounts：同一成就多人达成逐个累加', () => {
  const all = achievementCounts([['徐州'], ['徐州'], ['苏州']]).flatMap(c => c.items);
  assert.equal(all.find(a => a.code === 'city_xuzhou').count, 2);
});