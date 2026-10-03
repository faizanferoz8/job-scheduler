CREATE TABLE schedules (
    id            uuid PRIMARY KEY,
    name          text NOT NULL UNIQUE,
    cron          text NOT NULL,
    queue         text NOT NULL,
    type          text NOT NULL,
    payload       jsonb NOT NULL DEFAULT '{}',
    enabled       boolean NOT NULL DEFAULT true,
    next_fire_at  timestamptz NOT NULL,
    last_fired_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE jobs (
    id               uuid PRIMARY KEY,
    queue            text NOT NULL,
    type             text NOT NULL,
    payload          jsonb NOT NULL DEFAULT '{}',
    status           text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
    priority         int NOT NULL DEFAULT 0,
    -- Not before this moment. Also how a retry is delayed.
    run_at           timestamptz NOT NULL DEFAULT now(),
    -- Counts attempts started. It doubles as the fencing token: a worker may only finish
    -- the attempt it was given.
    attempts         int NOT NULL DEFAULT 0,
    max_attempts     int NOT NULL CHECK (max_attempts >= 1),
    locked_by        text,
    lease_expires_at timestamptz,
    last_error       text,
    idempotency_key  text UNIQUE,
    schedule_id      uuid REFERENCES schedules (id) ON DELETE SET NULL,
    scheduled_for    timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    -- One job per schedule per tick, however many instances notice the tick is due.
    UNIQUE (schedule_id, scheduled_for),
    -- A running job always has an owner and a lease; nothing else does.
    CHECK ((status = 'RUNNING') = (locked_by IS NOT NULL AND lease_expires_at IS NOT NULL))
);

-- What a worker's claim scans: only queued rows, already in the order it wants them.
CREATE INDEX jobs_claim_idx ON jobs (queue, priority DESC, run_at, id) WHERE status = 'QUEUED';
-- What the reaper scans.
CREATE INDEX jobs_lease_idx ON jobs (lease_expires_at) WHERE status = 'RUNNING';
CREATE INDEX jobs_recent_idx ON jobs (created_at DESC);

CREATE TABLE job_attempts (
    job_id      uuid NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt     int NOT NULL,
    worker      text NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    outcome     text CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'LEASE_EXPIRED', 'RELEASED')),
    error       text,
    PRIMARY KEY (job_id, attempt)
);

CREATE TABLE workers (
    id           text PRIMARY KEY,
    queue        text NOT NULL,
    concurrency  int NOT NULL,
    started_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
