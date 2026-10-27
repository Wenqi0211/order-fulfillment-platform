# Inventory service

The service owns catalog prices, physical stock, and stock reservations in its own PostgreSQL database. It exposes the HTTP endpoints in `InventoryController`; every endpoint uses the common API-key boundary.

## Transaction and retry behavior

* Reserve validates a distinct set of 1–100 SKUs and normalizes their order before hashing the request. The order UUID is the idempotency identity. Repeating the same request returns the saved reservation; changing its quantities or SKUs returns `409 IDEMPOTENCY_CONFLICT`.
* A PostgreSQL transaction advisory lock serializes operations on one order, including the initial request before a row exists. Stock rows are locked in database SKU order, so overlapping multi-SKU requests cannot acquire those locks in opposite orders.
* All requested SKUs are checked before stock changes. Shortage returns `409 INSUFFICIENT_STOCK`; the whole request rolls back. PostgreSQL constraints independently enforce `0 <= reserved <= on_hand`.
* Commit reduces both physical and reserved stock; release reduces reserved stock only. Either terminal transition can be repeated safely. A conflicting terminal transition returns `409 RESERVATION_STATE_CONFLICT`.
* Reservation state, audit entries, and the outbox event commit together. Kafka availability cannot cause an unrecorded stock change; the shared outbox worker publishes after commit and may deliver duplicates.

Reservations do not expire automatically. Releasing stock while a payment outcome is unknown could permit a paid order to lose its stock. The order workflow decides whether to commit, release, retry, or request manual review.

## Redis boundary

Only `GET /v1/items/{sku}` uses Redis cache-aside metadata (`catalog:v1:<sku>`). Stock checks and quotes always read PostgreSQL. A failed Redis read, write, eviction, or malformed cache value falls back to the authoritative database; cache errors are counted through Micrometer. Redis connect/read timeouts are bounded to 200/250 milliseconds.

Configuration: `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`, `INVENTORY_CACHE_ENABLED`, and `INVENTORY_CACHE_TTL_SECONDS` (default 30, accepted range 1–3600). PostgreSQL uses `DB_URL` (JDBC), `DB_USER`, and `DB_PASSWORD`.

Concurrent misses for the same SKU share one in-flight load per process, bounded by `INVENTORY_CACHE_MAX_IN_FLIGHT` (default 256, range 1–4096). Followers wait at most four seconds; finished values are removed rather than retained as an L1 cache. Capacity exhaustion falls back to a direct database load. A Redis RPC failure opens a cooldown controlled by `INVENTORY_CACHE_FAILURE_COOLDOWN_MS` (default 1000, range 100–10000); reads and writes bypass Redis until a single recovery probe succeeds. Disabled-cache mode bypasses both Redis and coalescing. See the [measured diagnosis and tradeoffs](../docs/CACHE_OPTIMIZATION.md); these protections do not demonstrate faster normal HTTP responses.

## Verification

`InventoryRulesTest` and `CatalogCacheTest` cover validation, exact money arithmetic, typed cache data, and cache failure fallback. `InventoryIT` uses real PostgreSQL in a fresh randomly named schema and drops only that schema afterward. It exercises competing orders, repeated identical requests, multi-SKU ordering and rollback, commit/release races, database constraints, authoritative quotes, and an injected outbox-write failure.

`CatalogCacheRecoveryTest` additionally covers same-SKU coalescing, bounded admission, distinct-key concurrency, failure propagation and cleanup, follower interruption, disabled mode, cooldown bypass, one recovery probe, and an older successful request not clearing a newer failure cooldown.

Run the root Maven wrapper with `-Pintegration` and supply `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, and `TEST_DATABASE_PASSWORD` for a disposable PostgreSQL database. The database user must be allowed to create schemas. Selecting this profile without the database configuration fails explicitly; it does not count skipped database tests as verification.

Catalog creation is intentionally insert-only; this version does not expose stock adjustments, price changes, or deletion endpoints. Any future mutation must preserve the same database ownership, audit, and post-commit cache invalidation rules.
