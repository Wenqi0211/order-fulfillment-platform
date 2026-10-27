CREATE TABLE catalog_items (
    sku varchar(64) PRIMARY KEY,
    name varchar(200) NOT NULL,
    unit_price_minor bigint NOT NULL CHECK (unit_price_minor > 0 AND unit_price_minor <= 1000000000000),
    currency char(3) NOT NULL CHECK (currency = 'USD'),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE stock (
    sku varchar(64) PRIMARY KEY REFERENCES catalog_items(sku),
    on_hand integer NOT NULL CHECK (on_hand >= 0),
    reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    CHECK (reserved <= on_hand)
);
CREATE TABLE reservations (
    order_id uuid PRIMARY KEY,
    request_fingerprint char(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RESERVED','COMMITTED','RELEASED')),
    items jsonb NOT NULL CHECK (jsonb_typeof(items) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reservations_open_created_idx ON reservations(created_at) WHERE status = 'RESERVED';
CREATE TABLE reservation_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES reservations(order_id),
    from_status varchar(16),
    to_status varchar(16) NOT NULL CHECK (to_status IN ('RESERVED','COMMITTED','RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((from_status IS NULL AND to_status = 'RESERVED')
       OR (from_status IS NOT NULL AND from_status = 'RESERVED' AND to_status IN ('COMMITTED','RELEASED'))),
    UNIQUE (order_id,to_status)
);
CREATE INDEX reservation_audit_order_idx ON reservation_audit(order_id,id);
