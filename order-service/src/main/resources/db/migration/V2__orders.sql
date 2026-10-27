CREATE TABLE orders (
 id uuid PRIMARY KEY,
 idempotency_key varchar(128) NOT NULL UNIQUE,
 request_hash char(64) NOT NULL,
 payment_token varchar(32) NOT NULL CHECK (payment_token IN ('tok_approved','tok_declined')),
 items_json jsonb NOT NULL CHECK (jsonb_typeof(items_json)='array' AND jsonb_array_length(items_json) BETWEEN 1 AND 100),
 total_minor bigint NOT NULL CHECK (total_minor BETWEEN 1 AND 1000000000000),
 currency char(3) NOT NULL CHECK (currency='USD'),
 status varchar(32) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','MANUAL_REVIEW','PACKED','SHIPPED','DELIVERED')),
 workflow_step varchar(24) NOT NULL CHECK (workflow_step IN ('RESERVE','PAYMENT','COMMIT','COMPENSATE','DONE')),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
 failure_code varchar(64),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 warehouse_sequence bigint NOT NULL DEFAULT 0 CHECK (warehouse_sequence BETWEEN 0 AND 9007199254740991),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (status NOT IN ('CONFIRMED','CANCELLED','PACKED','SHIPPED','DELIVERED') OR workflow_step='DONE')
);
CREATE INDEX orders_due ON orders(next_attempt_at,created_at,id) WHERE status='PENDING';
CREATE INDEX orders_recent ON orders(created_at DESC,id);
CREATE TABLE order_workflow_audit (
 id bigserial PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id),
 step varchar(24) NOT NULL, outcome varchar(64) NOT NULL, attempt integer NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE warehouse_ingress (
 event_id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id),
 request_hash char(64) NOT NULL, payload_json jsonb NOT NULL,
 replay_count integer NOT NULL DEFAULT 0 CHECK (replay_count>=0),
 last_replayed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE warehouse_receipts (
 event_id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id), request_hash char(64) NOT NULL,
 outcome varchar(24) NOT NULL CHECK (outcome IN ('APPLIED','IGNORED_STALE','IGNORED_BACKWARD')),
 processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE consumer_failures (
 message_hash char(64) PRIMARY KEY, attempts integer NOT NULL CHECK (attempts>0),
 failure_code varchar(64) NOT NULL, first_failed_at timestamptz NOT NULL DEFAULT now(),
 last_failed_at timestamptz NOT NULL DEFAULT now(), dead_lettered_at timestamptz
);
