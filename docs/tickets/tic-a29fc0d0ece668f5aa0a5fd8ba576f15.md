---
id: tic-a29fc0d0ece668f5aa0a5fd8ba576f15
status: closed
type: feature
priority: 1
assignee: blater
delivery: code
base-commit: c9c216d3c214bec8e51bad9b4e650c42ae4a48af
branch: ticket/tic-a29fc0d0ece668f5aa0a5fd8ba576f15-order-status-batches
delivered-commit: e128e066555291dbf06ace440d51a954f2454d86
evidence:
    - docs/performance-checkpoints.md
tags:
    - performance
    - protocol
created: 2026-09-27T23:13:17.545851Z
---
# Batch SQL result rows for Order Status

Outcome: eliminate per-row request/reply for bounded multirow SQL results through River server and supported clients, including the external Go harness consumer. Evidence: one-worker Order Status spends about 266 microseconds per transaction in repeated Rows.Next calls (docs/performance-checkpoints.md). Canonical mechanism: byte-bounded row-frame batches owned by the protocol and client cursor. Non-goals: SQL join planning, transaction programs, write/WAL changes, unrelated protocol pipelining. Stop if correctness boundaries cannot be maintained or matched Order Status samples do not show fewer exchanges and a repeatable benefit. Maximum shape: one result-streaming protocol mechanism across protocol/server/Java client and the separately owned Go consumer; no second executor, queue, value representation, or compatibility path.

2026-09-28 scope correction: retain the established row-frame encoding and concatenate complete frames in one byte-bounded server write. The first frame identifies whether another frame is queued. This avoids a second value decoder and preserves existing wide-row continuation semantics.

## Acceptance Criteria

Server, Java client/JDBC, and Go harness consume bounded batches with correct empty/singleton/multirow/wide-row, end-of-stream, early-close, cancellation, error, and backpressure behavior. No per-row allocation in steady fetch. Same-workload paired Order Status runs pass invariants and reduce exchanges and latency without an unexplained regression. Focused affected tests and clean checkpoint pass; accepted result is recorded and committed/pushed with checkpoint evidence.
