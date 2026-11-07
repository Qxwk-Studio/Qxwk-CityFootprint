import assert from 'node:assert/strict';
import test from 'node:test';
import { handleSyncPull } from '../src/sync.js';

function mockDb() {
  const trip = {
    uuid: 'trip-1', user_id: 1, title: '成都', note: null,
    timeline: '{"items":[]}', start_time: 1, end_time: 2, total_cost_cents: 0,
    trip_state: 2, revision: 1, client_modified_at: 10, server_modified_at: 10, deleted_at: null,
  };
  const checkin = {
    uuid: 'checkin-1', user_id: 1, city_name: '成都', city_code: 510100, province: '四川',
    checkin_at: 1, created_at: 1, date_precision: 2, note: null, companions: null,
    rating: null, amount_cents: null, stay_days: null, revision: 1,
    client_modified_at: 10, server_modified_at: 10, deleted_at: null,
  };
  return {
    prepare(sql) {
      return {
        bind() { return this; },
        async first() {
          if (sql.includes('MAX(seq)')) return { seq: 0 };
          return null;
        },
        async all() {
          if (sql.includes('FROM trips')) return { results: [trip] };
          if (sql.includes('FROM checkins')) return { results: [checkin] };
          return { results: [] };
        },
      };
    },
  };
}

test('new device with cursor 0 receives existing rows as snapshot', async () => {
  const request = new Request('https://example.test/api/sync/pull?since=0');
  const response = await handleSyncPull(request, { DB: mockDb() }, { id: 1 });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.changes.length, 2);
  assert.deepEqual(body.changes.map((c) => c.entity_type).sort(), ['checkin', 'trip']);
  assert.equal(body.has_more, false);
});
