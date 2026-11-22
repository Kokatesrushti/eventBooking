// Scenario 1: many customers booking ONE hot event. Ramps the arrival rate until the app breaks.
// Run:  k6 run -e BASE=https://your-app.onrender.com k6/booking-hot.js
//       k6 run -e BASE=... -e SCALE=0.25 k6/booking-hot.js     (scale all stage rates down for a small host)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const seed = JSON.parse(open('./seed.json'));
const tokens = seed.tokens;
const eventId = seed.eventIds[0];
const S = parseFloat(__ENV.SCALE || '1');
const r = (n) => Math.max(1, Math.round(n * S));

const ok201 = new Counter('bookings_201');
const soldOut409 = new Counter('responses_409');
const busy503 = new Counter('responses_503');
const limited429 = new Counter('responses_429');
const other5xx = new Counter('responses_5xx_other');

http.setResponseCallback(http.expectedStatuses(201));

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: r(10), timeUnit: '1s',
      preAllocatedVUs: 500, maxVUs: 3000,
      stages: [
        { target: r(50),  duration: '30s' },
        { target: r(100), duration: '30s' },
        { target: r(200), duration: '30s' },
        { target: r(400), duration: '30s' },
        { target: r(800), duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    dropped_iterations: ['count<1'],
  },
};

export default function () {
  const t = tokens[Math.floor(Math.random() * tokens.length)];
  const res = http.post(`${__ENV.BASE}/api/events/${eventId}/book`,
    JSON.stringify({ quantity: 1 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, timeout: '30s' });
  if (res.status === 201) ok201.add(1);
  else if (res.status === 409) soldOut409.add(1);
  else if (res.status === 503) busy503.add(1);
  else if (res.status === 429) limited429.add(1);
  else if (res.status >= 500 || res.status === 0) other5xx.add(1);
  check(res, { 'booked (201)': (x) => x.status === 201 });
}
