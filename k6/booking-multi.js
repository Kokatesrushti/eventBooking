// Scenario 2: bookings spread across many events (no single hot row).
// Seed with several events first:  POST /api/admin/seed?events=50
import http from 'k6/http';
import { check } from 'k6';

const seed = JSON.parse(open('./seed.json'));
const S = parseFloat(__ENV.SCALE || '1');
const r = (n) => Math.max(1, Math.round(n * S));
http.setResponseCallback(http.expectedStatuses(201));

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: r(10), timeUnit: '1s',
      preAllocatedVUs: 500, maxVUs: 3000,
      stages: [
        { target: r(100), duration: '30s' },
        { target: r(300), duration: '30s' },
        { target: r(600), duration: '30s' },
        { target: r(1000), duration: '30s' },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'] },
};

export default function () {
  const t = seed.tokens[Math.floor(Math.random() * seed.tokens.length)];
  const eventId = seed.eventIds[Math.floor(Math.random() * seed.eventIds.length)];
  const res = http.post(`${__ENV.BASE}/api/events/${eventId}/book`,
    JSON.stringify({ quantity: 1 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, timeout: '30s' });
  check(res, { 'booked (201)': (x) => x.status === 201 });
}
