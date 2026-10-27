CREATE TABLE outbox (
    event_id uuid PRIMARY KEY,
    topic varchar(120) NOT NULL,
    partition_key varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(200)
);
CREATE INDEX outbox_pending ON outbox(next_attempt_at,created_at) WHERE published_at IS NULL;
