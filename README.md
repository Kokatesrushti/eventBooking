Event Booking System (Backend)

Backend APIs for an event booking platform with two roles: Event Organizers (create and manage events) and Customers (browse events and book tickets). Access is controlled by role. Booking confirmation and event-update emails are sent by a background job queue.

Live (optimized): https://<optimized-url>.onrender.com
Live (naive baseline): https://eventbooking-naive.onrender.com
API docs (Swagger): https://<optimized-url>.onrender.com/swagger-ui.html
Demo video: <video-link>
Tech stack
Area	Choice
Language / framework	Java, Spring Boot
Auth	Spring Security + JWT, role-based access (ORGANIZER, CUSTOMER)
Persistence	Spring Data JPA / Hibernate, PostgreSQL (Neon)
Background tasks	Database-backed job queue (outbox pattern) with a worker
Email	Resend (HTTP API); a simulated provider is used for load tests
Deployment	Render (two services: naive and optimized), Neon Postgres
Load testing	k6
Roles and access
Action	Organizer	Customer
Create / update / delete own events	Yes	403
Browse events (paginated)	Yes	Yes
Book tickets	403	Yes
View / cancel own bookings	No	Yes

Authentication uses a JWT in the Authorization: Bearer <token> header. The role is checked on every protected endpoint.

Main APIs

Adjust paths to match the controllers in this repo.

Method	Path	Role	Description
POST	/api/auth/register	Public	Register a user with a role
POST	/api/auth/login	Public	Log in, returns a JWT
POST	/api/events	Organizer	Create an event
PUT	/api/events/{id}	Organizer	Update an event (triggers notifications)
GET	/api/events	Any	List events (pagination)
POST	/api/events/{id}/bookings	Customer	Book tickets (optional Idempotency-Key header)
GET	/api/bookings/mine	Customer	List my bookings
DELETE / POST	cancel booking	Customer	Cancel a booking, seats are returned
Background tasks
Booking confirmation: when a booking succeeds, a BOOKING_CONFIRMATION job is inserted. A worker sends a real email through Resend.
Event update notification: when an event is updated, jobs are created to email every customer who booked that event.

Design decisions:

Outbox pattern. The job row is inserted in the same database transaction as the booking, so a job exists if and only if the booking was committed. A crash can't lose an email or send one for a booking that rolled back.
Worker with retries. Jobs track status, attempts and last_error, so failures are visible and retried.
No external call inside the booking transaction. The request only writes to the database. The slow email call happens in the worker.
Provider switch. MAIL_PROVIDER=resend|simulated. Resend is used for the real demo. The simulated provider (with fixed latency) is used for load tests so they don't use up the email quota or send mail to fake addresses.
Key design decisions
Atomic seat decrement. The optimized booking uses one SQL statement that checks and decrements in the same step: UPDATE events SET available_seats = available_seats - :qty WHERE id = :id AND available_seats >= :qty. If 0 rows are updated, the event is sold out (or not found / inactive). This prevents overselling.
Idempotency key. A client retry with the same Idempotency-Key returns the original booking instead of booking twice. Reusing a key for a different customer or event returns a conflict.
Naive vs optimized in one codebase. app.mode=naive|optimized selects bookNaive or bookOptimized, so the same code base is deployed twice and compared under the same test.
Pagination on event listing, and indexes on booking lookups.
Seed endpoint for load tests (POST /api/admin/seed). It is protected by SEED_ENABLED and a secret header. It creates 1 organizer, 1,000 customers with one shared pre-computed BCrypt hash, an event with 100,000 seats, and a JWT for each customer. This way the load test measures booking only, not password hashing at login. It is disabled after testing.
Performance testing

Scenario: 1,000 customers book tickets for the same event while the request rate ramps up (k6 ramping-arrival-rate, up to about 80 requests/second at SCALE=0.1). The event has 100,000 seats so the test measures speed, not sell-outs. Thresholds: error rate under 1%, p95 latency under 1s, no dropped iterations.

Run:

copy seed-<naive|opt>.json k6\seed.json
k6 run -e BASE=https://<service-url> -e SCALE=0.1 k6/booking-hot.js
1. Initial (naive) implementation
Metric (80 req/s ramp)	Result
Successful bookings/sec	~8
Failed requests	~46% (30s timeouts)
Dropped iterations	666
p95 latency	30s

Correctness: 2,781 tickets were booked but the event's seat count dropped by only 363. This is a lost update: concurrent requests read the same seat count and overwrite each other, so the system would oversell.

Problems found in bookNaive:

The confirmation email was sent inside the database transaction, so each request held a DB connection during a slow external call and the connection pool was exhausted.
Seats were updated with read, modify, write, causing lost updates.
About 6 database operations per booking.
2. Optimizations
Atomic UPDATE for the seat decrement (check and decrement in one statement).
Email moved out of the request into the job queue (outbox pattern).
Booking reduced to 3 database operations: UPDATE seats, INSERT booking, INSERT job.
3. Final result
	Naive	Optimized
Seat count correct after load	No (363 decremented vs 2,781 booked)	Yes (2,745 = 2,745)
Email inside the request	Yes	No (background worker)
DB operations per booking	~6	3
Highest load passing thresholds	___ req/s	___ req/s

Fill in the last row from your lower-load runs (SCALE=0.02, 0.05). At SCALE=0.1 (about 80 req/s) both versions fail the thresholds on the free tier, and the throughput of the two was close (roughly 5-8 successful bookings/sec for naive, about 6 for optimized). The clear, proven improvement is correctness and removing the email from the request path.

Limits and next steps
Every booking for one event updates the same database row, so a single event has a throughput ceiling, and the row lock is held for several database round trips.
Both services run on free tiers (limited CPU, cold starts, network latency between Render and Neon), so absolute numbers are modest. The comparison matters, not the absolute value.
Next steps:
Take the row lock as late as possible in the transaction.
Run the app and the database in the same region.
Split seats into buckets, or use a Redis counter with asynchronous persistence.
Add a queue-based booking flow (accept, then confirm) for very hot events.
Environment variables
Variable	Purpose
DATABASE_URL / datasource settings	Neon Postgres connection
APP_MODE	naive or optimized (maps to app.mode)
JWT_SECRET	JWT signing key
MAIL_PROVIDER	resend or simulated (default simulated)
RESEND_API_KEY	Resend API key (never commit this)
MAIL_FROM	Sender, default onboarding@resend.dev
MAIL_SIM_LATENCY_MS	Latency of the simulated provider
SEED_ENABLED	true only while load testing
SEED_SECRET	Secret for the seed endpoint

Without a verified domain, Resend only delivers from onboarding@resend.dev to the email address used to sign up for Resend.

Run locally
git clone <repo-url>
cd event-booking
# set the environment variables above, then:
./mvnw spring-boot:run

Health check: GET /actuator/health.

Security notes
Secrets are provided only through environment variables, never committed.
k6/seed.json, seed-naive.json and seed-opt.json contain valid tokens and are git-ignored.
SEED_ENABLED must be set to false after testing.
AI tools

This project was built with AI assistance (initial implementation, load-test scripts and optimization analysis). The design decisions above were reviewed and validated against measured results.
