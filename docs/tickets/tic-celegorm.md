---
id: tic-celegorm
status: in_progress
type: feature
priority: 1
assignee: blater
delivery: code
base-commit: 1753ff1d
branch: ticket/tic-celegorm-order-status-program
base-commit: 1753ff1db3bff4b650a085ab5febc68f6bd00f78
branch: ticket/tic-celegorm-order-status-program
tags:
    - performance
    - protocol
created: 2026-09-28T00:18:33.270386Z
---
# Run full Order Status in one transaction-program request

Use the existing transaction-program execution boundary to execute the full Order Status read transaction with one client request, including the median customer-by-last-name path. Extend only a generic row-selection capability if the current program graph cannot express it. The Go adapter must consume the public protocol without benchmark semantics in River engine or protocol internals. Base: perf-checkpoint-20260928-order-status-batches. Evidence: docs/performance-checkpoints.md residual Order Status request timing.

## Acceptance Criteria

Both ID and last-name paths preserve customer selection, output values, snapshot and failure/rollback semantics; bounded result memory and cancellation work; no legacy duplicate executor remains. Focused Java and Go tests, clean checkpoint, and matched ABBA one-worker Order Status runs pass invariants and show fewer requests and a repeatable benefit. Commit, merge, tag and push accepted River changes; publish harness changes when a remote is configured.
