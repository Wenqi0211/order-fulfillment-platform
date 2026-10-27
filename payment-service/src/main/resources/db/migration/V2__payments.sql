CREATE TABLE payments (
    order_id uuid PRIMARY KEY,
    request_fingerprint char(64) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    currency char(3) NOT NULL CHECK (currency = 'USD'),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DECLINED')),
    provider_reference varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'PENDING' AND provider_reference IS NULL)
       OR (status IN ('APPROVED','DECLINED') AND provider_reference IS NOT NULL AND length(provider_reference) > 0))
);
CREATE INDEX payments_created_idx ON payments(created_at);
CREATE INDEX payments_pending_idx ON payments(created_at) WHERE status = 'PENDING';
