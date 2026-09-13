---
id: tic-rian
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-primula
delivery: evidence
tags:
    - performance
created: 2026-09-13T11:46:48.689601Z
deps:
  - tic-gwindor
  - tic-morgoth
---
# Validate transport interoperability and workload benefit

Evaluate the exact integrated source after `tic-gwindor` and
`tic-morgoth` have accepted or explicitly rejected outcomes. Inspect the
actual dispositions, not just closed status. Use the selected contract from
`tic-edoras` and both Java and separately delivered Go consumers.

This evidence-only gate cannot fix protocol or harness code. Require end-to-end
ordering, parameter/result equivalence, cancellation/pressure/streaming and
uncertain-commit fault tests from the implementation; verify server-owned cleanup
and bounded retained handles/bytes. Pin independently versioned consumer artifacts
and record publication status; an unavailable Go delivery blocks cross-consumer
acceptance, not unrelated River diagnostics.

Measure same-family and sample/all matched controls/candidates, using identical
SQL, transaction boundaries, transport/TLS, isolation, retry policy and data.
Report writes/exchanges/bytes per attempt and commit, waits/lock residence,
throughput/p99/retries and truthful failures. Apply the
[shared promotion contract](../plans/performance-three-epics-handover.md); no
speedup based only on request counts, no generic MariaDB parity claim.
