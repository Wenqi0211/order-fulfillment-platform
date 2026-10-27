# Payment service

This service is an explicit **payment simulator**, with no connection to a bank or real payment network. It accepts `tok_approved` and `tok_declined`, uses integer USD cents, and rejects raw payment-card input. It does not claim PCI compliance or real charge processing.

## Durable request identity

`PUT /internal/payments/{orderId}` uses the order UUID as its idempotency identity. A SHA-256 request fingerprint covers amount, currency, and token; the raw token is not persisted or published.

1. A short independent transaction acquires the per-order PostgreSQL advisory lock, inserts an immutable `PENDING` intent if necessary, verifies the fingerprint, and commits.
2. A second independent transaction reacquires that lock, checks the saved intent, and returns a saved terminal decision without calling the provider again. For a pending intent it calls the provider with the stable order UUID.
3. The terminal payment decision and its outbox event commit atomically. A provider timeout, invalid decision, lost response, or failed final database transaction leaves the durable intent pending. An identical request can retry; a changed request receives `409 IDEMPOTENCY_CONFLICT` before another provider call.

Only `APPROVED` or `DECLINED` is returned as a successful HTTP response. An unknown outcome returns `503 PAYMENT_OUTCOME_UNKNOWN`; it is not converted to a decline. This preserves the order workflow's ability to recover safely.

The simulator returns a deterministic `sim_` provider reference derived from the UUID, including after process restart. It has no external side effect. A future real provider adapter **must** use durable provider-side idempotency for that same UUID, enforce network deadlines, and support reconciliation for uncertain outcomes. A local database transaction alone cannot guarantee exactly-once external charges. The simulated tokens and validation would also need an intentional contract change before a real integration.

## Configuration and verification

PostgreSQL uses `DB_URL` (JDBC), `DB_USER`, and `DB_PASSWORD`. `PAYMENT_PROVIDER=simulated` selects the supplied provider. Unsupported provider configuration fails startup because no provider bean exists.

`PaymentRulesTest` checks amount/currency/token validation and deterministic simulator behavior. `PaymentIT` uses a fresh schema on real PostgreSQL. It covers concurrent replays, conflicts, replay after service recreation, a lost provider response, invalid provider replies, and injected outbox failure. The lost-response test verifies that a changed request is rejected while the original intent is pending, before the provider is called again.

Run the root Maven wrapper with `-Pintegration` and set `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, and `TEST_DATABASE_PASSWORD` for a disposable PostgreSQL database whose user may create schemas. Missing database configuration fails the integration profile explicitly.
