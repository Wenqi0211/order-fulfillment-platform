-- Keep scheduled Saga snapshots on the active workflow subset as terminal history grows.
CREATE INDEX orders_saga_backlog ON orders(status,workflow_step,next_attempt_at)
 INCLUDE (attempts,created_at) WHERE status IN ('PENDING','MANUAL_REVIEW');
