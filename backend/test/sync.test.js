import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeCheckinData, normalizeTripData } from '../src/sync.js';

const trip = {
  title: '成都',
  note: '测试',
  timeline: '{"items":[{"type":"visit","text":"成都"}]}',
  start_time: 1700000000000,
  end_time: 1700100000000,
  total_cost_cents: 100,
  trip_state: 2,
  client_modified_at: 1700100000000,
};

const checkin = {
  city_name: '成都',
  province: '四川',
  checkin_at: 1700000000000,
  created_at: 1700000000000,
  date_precision: 2,
  client_modified_at: 1700000000000,
};

test('normalizeTripData accepts a valid trip', () => {
  const result = normalizeTripData(trip);
  assert.equal(result.title, '成都');
  assert.equal(result.startTime, 1700000000000);
});

test('normalizeTripData rejects invalid title/timeline/time', () => {
  assert.throws(() => normalizeTripData({ ...trip, title: '' }));
  assert.throws(() => normalizeTripData({ ...trip, title: 'x'.repeat(24) }));
  assert.throws(() => normalizeTripData({ ...trip, timeline: 'not-json' }));
  assert.throws(() => normalizeTripData({ ...trip, end_time: 1 }));
});

test('normalizeCheckinData accepts a valid checkin', () => {
  const result = normalizeCheckinData(checkin);
  assert.equal(result.cityName, '成都');
  assert.equal(result.province, '四川');
});

test('normalizeCheckinData rejects invalid fields', () => {
  assert.throws(() => normalizeCheckinData({ ...checkin, city_name: '' }));
  assert.throws(() => normalizeCheckinData({ ...checkin, province: '' }));
  assert.throws(() => normalizeCheckinData({ ...checkin, date_precision: 4 }));
  assert.throws(() => normalizeCheckinData({ ...checkin, rating: 11 }));
  assert.throws(() => normalizeCheckinData({ ...checkin, stay_days: 0 }));
});
