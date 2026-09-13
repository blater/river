---
id: tic-nimloth
status: open
type: bug
assignee: blater
parent: tic-primula
delivery: code
tags:
    - safety
    - protocol
    - deadline
created: 2026-09-13T16:13:56.814975Z
---
# Enforce whole-request responsiveness deadlines without idle-workload false positives

Extend the existing transport owner with an explicit maximum 15-second pending-request no-progress contract covering writes, partial reads and cancellation; preserve legitimate idle time and never claim kernel lock release. The user has reauthorized sequential runtime diagnostics with an enforced stop deadline.

### Owning boundary and admission

Keep the existing client exchange/transport owner and retry ownership. Current
socket timeout is per blocking read and does not bound an entire request, blocked
writes, partial response trickles or close. Specify one explicit operation-progress
contract before coding. A silent checkpoint has no progress frames; after 15 seconds
without response it is timed out, not proven deadlocked. No automatic replay of
uncertain commits, hidden fallback, extra executor or benchmark-family policy.

Do not equate 15 seconds without stdout, aggregate commits or process exit with
unresponsiveness. Standard scheduling permits intentional 18-second keying and
longer bounded think time. Tests must distinguish healthy 30-second measured runs,
intentional idle time, a stalled terminal masked by active peers, stalled load,
checkpoint silence, partial responses, blocked writes and cancellation/close.
Use simulated transport tests before the explicitly authorized guarded runtime
diagnostics; do not retry automatically after a deadline failure. tic-treebeard owns
bounded process shutdown; neither ticket promises release of kernel resources.

### End-to-end admission gate

Before permitting workload validation, also account for synchronous runtime
discovery and readiness waits in the invoking runner. The current Java version
probe and 30-second readiness loop are not covered by the shutdown deadline.
Keep startup supervision in the runner and request progress in the transport;
record one end-to-end deadline contract without duplicating retry ownership.
Acceptance must name every remaining blocking boundary and prove that a timed-out
request with an uncertain outcome cannot be replayed automatically.
