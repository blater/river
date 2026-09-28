---
id: tic-celegorm
status: closed
type: feature
priority: 1
assignee: blater
delivery: code
base-commit: 1753ff1db3bff4b650a085ab5febc68f6bd00f78
branch: ticket/tic-celegorm-order-status-program
delivered-commit: 8a37147f7bb33f71df1a6ccaef3b459af086196f
evidence:
    - docs/performance-checkpoints.md
tags:
    - performance
    - protocol
created: 2026-09-28T00:18:33.270386Z
---
# Run full Order Status in one transaction-program request

Use the existing transaction-program execution boundary to execute the full Order Status read transaction with one client request, including the median customer-by-last-name path. Extend only a generic row-selection capability if the current program graph cannot express it. The Go adapter must consume the public protocol without benchmark semantics in River engine or protocol internals. Base: perf-checkpoint-20260928-order-status-batches. Evidence: docs/performance-checkpoints.md residual Order Status request timing.

## Acceptance Criteria

Both ID and last-name paths preserve customer selection, output values, snapshot and failure/rollback semantics; bounded result memory and cancellation work; no legacy duplicate executor remains. Focused Java and Go tests, clean checkpoint, and matched ABBA one-worker Order Status runs pass invariants and show fewer requests and a repeatable benefit. Commit, merge, tag and push accepted River changes; publish harness changes when a remote is configured.

## Delivered

The generic program action selects one row by zero-based ordinal and permits
that row to supply later steps. The final row set enforces its count before
commit. The Go harness binding uses the public program protocol for both
customer ID and median-by-last-name inputs; the common SQL catalogue remains
the single SQL owner. River code is `8a37147f`; the harness consumer is
`f8e615a`. Focused tests and the clean full test build passed. Eligible
interleaved same-server binding samples measured 3,485–3,604 TPS for batched
SQL and 12,799–12,826 TPS for one program request. Matched interleaved
MariaDB and River samples measured 6,701–6,777 and 12,881–12,914 TPS,
respectively, with zero retries or failed/unknown outcomes. Exact artifacts,
configuration, validity limits and the excluded shutdown-failed run are in
the checkpoint record.
