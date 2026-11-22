// Scenario 3: event update with ~1000 booked customers.
// Phase 1 (0-60s): every seeded customer books the event once (needs seed with customers>=1000).
// Phase 2 (t=90s): the organizer updates the event ONCE. We time that single request.
// Then check notification delivery with the SQL in the README.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';

const seed = JSON.parse(open('./seed.json'));
const eventId = seed.eventIds[0];
const updateLatency = new Trend('event_update_latency_ms', true);

export const options = {
  scenarios: {
    prep: {
      executor: 'shared-iterations', vus: 50, iterations: seed.tokens.length, maxDuration: '80s',
      exec: 'book',
    },
    update: {
      executor: 'shared-iterations', vus: 1, iterations: 1, startTime: '90s', maxDuration: '10m',
      exec: 'update',
    },
  },
};

export function book() {
  const t = seed.tokens[exec.scenario.iterationInTest];
  http.post(`${__ENV.BASE}/api/events/${eventId}/book`, JSON.stringify({ quantity: 1 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, timeout: '60s' });
}

export function update() {
  const body = {
    title: `Load Test Event UPDATED ${new Date().toISOString()}`,
    description: 'updated by k6',
    venue: 'New Test Arena',
    startTime: new Date(Date.now() + 40 * 86400000).toISOString(),
    totalSeats: seed.seatsPerEvent,
    price: 12.5,
  };
  const res = http.put(`${__ENV.BASE}/api/events/${eventId}`, JSON.stringify(body),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${seed.organizerToken}` }, timeout: '600s' });
  updateLatency.add(res.timings.duration);
  check(res, { 'update 200': (r) => r.status === 200 });
}
