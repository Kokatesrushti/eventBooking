CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    organizer_id    BIGINT        NOT NULL REFERENCES users(id),
    title           VARCHAR(200)  NOT NULL,
    description     TEXT,
    venue           VARCHAR(200)  NOT NULL,
    start_time      TIMESTAMPTZ   NOT NULL,
    total_seats     INT           NOT NULL,
    available_seats INT           NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- last line of defence: DB refuses overselling even if app code is buggy
    CONSTRAINT chk_seats CHECK (available_seats >= 0 AND available_seats <= total_seats)
);
CREATE INDEX idx_events_start_time ON events(start_time);
CREATE INDEX idx_events_organizer  ON events(organizer_id);
CREATE INDEX idx_events_status_start ON events(status, start_time);

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT      NOT NULL REFERENCES events(id),
    customer_id     BIGINT      NOT NULL REFERENCES users(id),
    quantity        INT         NOT NULL CHECK (quantity > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    idempotency_key VARCHAR(100) UNIQUE,
    email_sent      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bookings_event    ON bookings(event_id);
CREATE INDEX idx_bookings_customer ON bookings(customer_id);

CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(50) NOT NULL,
    payload     TEXT        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    run_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error  TEXT,
    locked_at   TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- partial index: the worker only ever scans PENDING jobs, keeps the index tiny
CREATE INDEX idx_jobs_pending ON jobs(run_at, id) WHERE status = 'PENDING';
