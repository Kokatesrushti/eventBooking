# Event Booking System (Spring Boot)

Backend APIs for an event booking platform with two roles (**ORGANIZER**, **CUSTOMER**), real e-mail
notifications through a background job queue, and a documented stress-test / optimization journey.

- **Live API:** `<DEPLOYED_URL>` | **Swagger UI:** `<DEPLOYED_URL>/swagger-ui.html`
- **Demo video:** `<LOOM_LINK>`

---

## 1. Tech choices and why

| Concern            | Choice                                       | Why                                                                                                       |
| ------------------ | -------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Language/Framework | Java 21, Spring Boot 3.3                     | Familiar stack; Java 21 gives virtual threads for I/O-heavy endpoints                                     |
| Database           | PostgreSQL + Flyway                          | Row-level locking, `SKIP LOCKED`, `CHECK` constraints. Flyway makes the schema reproducible               |
| Auth               | Stateless JWT (HS256) + BCrypt               | No server session, no DB lookup per request (claims carry id/role)                                        |
| Background jobs    | **Postgres outbox table + scheduled worker** | See §6. No extra infra to deploy, and the job is written in the _same transaction_ as the business change |
| Email              | Resend HTTP API                              | Free hosts (Render etc.) block outbound SMTP ports; HTTP works everywhere                                 |
| Load testing       | k6                                           | Arrival-rate executor shows _dropped iterations_ clearly                                                  |
| Deploy             | Docker on Render + managed Postgres          | Free tier                                                                                                 |

Alternatives considered for the queue: RabbitMQ, Kafka, Redis + BullMQ/Redisson, SQS. All are more scalable
but add infrastructure, and none give "job committed atomically with the booking" without an outbox anyway.
`@Async` was rejected because jobs vanish on crash/restart.

## 2. Data model

```
users(id, name, email UNIQUE, password_hash, role, created_at)
events(id, organizer_id FK, title, description, venue, start_time, total_seats, available_seats,
       price, status ACTIVE|CANCELLED, created_at, updated_at,
       CHECK (available_seats >= 0 AND available_seats <= total_seats))
bookings(id, event_id FK, customer_id FK, quantity, status, idempotency_key UNIQUE, email_sent, created_at)
jobs(id, type, payload, status PENDING|PROCESSING|DONE|FAILED, attempts, run_at, last_error,
     locked_at, finished_at, created_at)
```

Indexes: `events(start_time)`, `events(status,start_time)`, `events(organizer_id)`, `bookings(event_id)`,
`bookings(customer_id)`, partial index `jobs(run_at,id) WHERE status='PENDING'` (worker only scans pending rows).
Entities hold plain FK ids (no JPA relations) to avoid N+1 queries and lazy-loading surprises.

## 3. API

| Method | Endpoint                                                                 | Role                   |
| ------ | ------------------------------------------------------------------------ | ---------------------- |
| POST   | `/api/auth/register` `{name,email,password,role}`                        | public                 |
| POST   | `/api/auth/login`                                                        | public                 |
| POST   | `/api/events`                                                            | ORGANIZER              |
| PUT    | `/api/events/{id}`                                                       | ORGANIZER (owner only) |
| DELETE | `/api/events/{id}` (soft-cancel, notifies customers)                     | ORGANIZER (owner only) |
| GET    | `/api/events/mine`                                                       | ORGANIZER              |
| GET    | `/api/events/{id}/bookings`                                              | ORGANIZER (owner only) |
| GET    | `/api/events?page=&size=`                                                | any authenticated      |
| GET    | `/api/events/{id}`                                                       | any authenticated      |
| POST   | `/api/events/{id}/book` `{quantity}` + optional `Idempotency-Key` header | CUSTOMER               |
| GET    | `/api/bookings/mine`                                                     | CUSTOMER               |
| DELETE | `/api/bookings/{id}`                                                     | CUSTOMER (owner only)  |
| GET    | `/actuator/health`                                                       | public                 |

Status codes: 201 created, 400 validation, 401 no/invalid token, 403 wrong role or not owner,
404, 409 sold out / conflict, 503 server saturated (DB pool timeout).

```bash
curl -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Org","email":"org@x.com","password":"secret123","role":"ORGANIZER"}'
curl -X POST $BASE/api/events -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Rock Night","venue":"Arena","startTime":"2030-01-01T18:00:00Z","totalSeats":100,"price":25}'
curl -X POST $BASE/api/events/1/book -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: abc-123' -d '{"quantity":2}'
```

## 4. Auth and RBAC

- `JwtAuthFilter` validates the signature and builds the principal from claims only (no DB hit).
- `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ORGANIZER')")` / `('CUSTOMER')` on controller methods.
- **Ownership** is checked in the service layer (an organizer can only update/cancel/see bookings of _their own_ events).
- Role is chosen at registration (as the task allows). In production, organizer sign-up would need approval.
- BCrypt is never on the booking path; load tests use pre-signed JWTs from the seed endpoint.

## 5. Concurrency strategy for booking

**v1 (naive, `APP_MODE=naive`)**: load event, check `available_seats`, subtract in Java, save, insert booking,
send e-mail synchronously inside the transaction.
Problems: (1) lost update => **overselling** under concurrency, (2) the slow HTTP e-mail call holds a DB
connection, so Hikari's 10 connections are exhausted after ~10 concurrent requests, (3) everything queues on one row.

**Final (`APP_MODE=optimized`)**:

```sql
UPDATE events SET available_seats = available_seats - :qty
WHERE id = :id AND available_seats >= :qty AND status = 'ACTIVE'
```

0 rows updated => 409 sold out. Check and decrement are one atomic statement, the row lock is held for
microseconds, and the `CHECK` constraint is a last line of defence. The booking transaction is now
1 UPDATE + 2 INSERTs and **no external calls**.
Event updates use `SELECT ... FOR UPDATE` so a stale `available_seats` is never written back over concurrent bookings.
`Idempotency-Key` (unique column) makes client retries safe.

## 6. Background jobs (transactional outbox)

- **Task 1 Booking confirmation:** the booking transaction inserts a `BOOKING_CONFIRMATION` job. Worker sends the real e-mail.
- **Task 2 Event update:** the update transaction inserts one `EVENT_UPDATED` job. The worker fans it out in a single
  `INSERT ... SELECT` into one `EVENT_UPDATE_EMAIL` job per distinct booked customer, so each recipient is retried independently.
- `JobService.enqueue` uses `Propagation.MANDATORY`: it _cannot_ run outside the business transaction.
  If the booking rolls back, the job disappears; if it commits, the job is guaranteed to exist.
- Worker: `SELECT ... FOR UPDATE SKIP LOCKED` batch claim, bounded thread pool, `max-in-flight` back-pressure.
- Failures: exponential backoff 5s, 10s, 20s ... up to 5 attempts, then `FAILED` with `last_error` stored.
- Crash recovery: a reaper returns jobs stuck in `PROCESSING` > 5 min to `PENDING` (**at-least-once**).
- Idempotency: `bookings.email_sent` stops duplicate confirmation e-mails on retry.
- Observability: `SELECT status, count(*) FROM jobs GROUP BY 1;`

E-mail provider: Resend. **Note:** without a verified domain Resend only delivers to the account owner's address
(sender `onboarding@resend.dev`), so register demo customers with your own e-mail (or verify a domain).
For stress tests `MAIL_PROVIDER=simulated` (sleeps `MAIL_SIM_LATENCY_MS`) is used, because Resend rate-limits
(~2 req/s on free) and would hide the app's own limits. This is a deliberate, disclosed decision.

## 7. Performance

### Test setup

- Hardware: `<e.g. Render free: 0.1 CPU / 512 MB>`; DB: `<e.g. Render Postgres free / Neon>`; load generator: `<laptop, region>`
- Seed: `k6/seed.sh $BASE $SEED_SECRET 1000 1 100000` (1000 customers, 1 event with 100k seats, JWTs pre-generated)
- Breaking point = first stage where p95 > 1s **or** errors/timeouts/dropped iterations > 1%.

### Results (fill in with real k6 output)

| #   | Version / change                           | Max stable req/s | p95 at that rate | Errors / dropped | Notes               |
| --- | ------------------------------------------ | ---------------- | ---------------- | ---------------- | ------------------- |
| 0   | v1 naive (`APP_MODE=naive`, pool 10)       |                  |                  |                  | oversold? Y/N, 503s |
| 1   | + atomic UPDATE (`APP_MODE=optimized`)     |                  |                  |                  |                     |
| 2   | + e-mail moved to outbox/worker            |                  |                  |                  |                     |
| 3   | + Hikari pool tuned (`DB_POOL_SIZE=`)      |                  |                  |                  |                     |
| 4   | + virtual threads (`VIRTUAL_THREADS=true`) |                  |                  |                  |                     |
| 5   | + JVM flags / other                        |                  |                  |                  |                     |

Event-update scenario (1000 booked customers): request latency v1 = `__ s`, optimized = `__ ms`;
time until all notifications delivered = `__ s`:

```sql
SELECT status, count(*) FROM jobs WHERE type='EVENT_UPDATE_EMAIL' GROUP BY status;
SELECT min(created_at) AS started, max(finished_at) AS finished, max(finished_at)-min(created_at) AS total
FROM jobs WHERE type='EVENT_UPDATE_EMAIL';
```

Oversell check: `SELECT total_seats - available_seats AS decremented, (SELECT coalesce(sum(quantity),0) FROM bookings WHERE event_id=1) AS booked FROM events WHERE id=1;` (they must match; in naive mode they will not).

### Optimization ideas and trade-offs

1. Atomic UPDATE instead of read-modify-write: fixes overselling, shortens lock time.
2. E-mail off the request path: removes the slow call from the transaction.
3. Pool sizing: bigger is not better; capped by Postgres connection limit. Measured, not guessed.
4. Virtual threads: requests blocked on I/O no longer pin platform threads.
5. Idempotency keys, JWT-only auth, indexed + paginated reads.
6. **Not done / future work:** hot-row sharding (split seats into N buckets), Redis `DECR` admission gate,
   `202 Accepted` queue-based booking, Bucket4j rate limiting (graceful 429), read cache for `GET /events`,
   separate worker deployment, Kafka/RabbitMQ at larger scale.

## 8. Run locally

```bash
docker run -d --name pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eventbooking -p 5432:5432 postgres:16
export SEED_ENABLED=true SEED_SECRET=devsecret APP_MODE=naive
mvn spring-boot:run
# Swagger: http://localhost:8080/swagger-ui.html
```

### Env vars

`DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET (>=32 chars), APP_MODE (naive|optimized), DB_POOL_SIZE, VIRTUAL_THREADS,
MAIL_PROVIDER (resend|simulated), RESEND_API_KEY, MAIL_FROM, MAIL_SIM_LATENCY_MS, WORKER_THREADS, SEED_ENABLED, SEED_SECRET`

### Load test

```bash
./k6/seed.sh http://localhost:8080 devsecret 1000 1 100000
k6 run -e BASE=http://localhost:8080 k6/booking-hot.js
./k6/seed.sh http://localhost:8080 devsecret 1000 50 1000 && k6 run -e BASE=http://localhost:8080 k6/booking-multi.js
./k6/seed.sh http://localhost:8080 devsecret 1000 1 100000 && k6 run -e BASE=http://localhost:8080 k6/event-update.js
```

Tag the baseline so reviewers can reproduce it: `git tag v1-naive` (run with `APP_MODE=naive`) and `git tag v2-optimized`.

## 9. Known limitations

- Seed endpoint is for testing only and is disabled by default (env flag + secret header).
- Single hot row still serialises writers; fine into the hundreds of bookings/s, beyond that see "future work".
- At-least-once e-mail delivery: a crash between "sent" and "marked done" can duplicate one e-mail.
- Free-tier hardware means absolute numbers are small; the _relative_ delta is the point.

## 10. AI usage

Claude was used for planning and scaffolding. I reviewed the code, deliberately kept a naive v1 as a baseline,
measured it, and then applied and measured each optimization. `<add what you changed yourself>`
