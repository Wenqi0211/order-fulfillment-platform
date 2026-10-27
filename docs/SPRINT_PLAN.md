# Proposed two-week iterations

This is a plan for future work and review, not a record of an existing engineering team.

| Iteration | Deliverable | Acceptance evidence |
|---|---|---|
| 1 | Inventory invariants, order idempotency, durable payment adapter boundary | PostgreSQL concurrency/replay tests, request/response examples, reviewed state machines |
| 2 | Saga recovery, outbox, Kafka warehouse consumer, poison/DLT handling | Crash-after-effect and timeout scenarios, duplicate/stale cases, recovery runbook |
| 3 | Cache benchmark, container startup, observability | Raw A/B observations, errors/drops, DB/cache metrics, full-stack smoke report |
| 4 | Staging rollout, failure rollback exercise, external payment integration design | Actual workflow run links, rollout failure evidence, reviewed integration threat model |

For each PR, a real reviewer should examine the transaction boundary, retry semantics, failure behavior, schema compatibility and test evidence. Track unresolved issues explicitly. Retrospectives should record actual bottlenecks and changes in direction rather than claims of completed meetings.
