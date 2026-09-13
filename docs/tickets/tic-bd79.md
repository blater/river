---
id: tic-bd79
status: open
type: investigation
assignee: blater
parent: tic-c7bb
delivery: evidence
tags:
    - performance
    - tpcc
    - 500tps
    - benchmark
deps:
    - tic-8561
    - tic-e305
created: 2026-09-04T15:10:08.162434Z
---
# Verify external artifact production and independent 500-TPS evaluation

### Outcome and owners

Verify the accepted tic-8561 interim contract through two independently versioned
processes. River-harness executes the declared target workload and emits immutable
versioned artifacts with workload configuration, outcomes, invariants and owned
lifecycle cleanup. The separate sidecar consumes artifacts and owns semantic/
configuration eligibility, pairing, confidence/statistics and gate evaluation.
Neither River nor the harness owns the comparator or comparison thresholds.

### Scope and dependencies

Consume the independently delivered sidecar boundary/extraction in tic-e305 and
the promotion contract in tic-8561. Link each external repository's actual ticket,
commit/release and process/file contract. This is an evidence-only verification;
missing producer or comparator behavior becomes a ticket in its owning repository.
No source/workspace fingerprint, host lease, terminal receipt or duplicate
provenance validator is introduced to admit diagnostic evidence.

### Acceptance

Demonstrate artifact production through the public installed-server lifecycle,
then separately demonstrate sidecar evaluation from those artifact paths without
starting databases. Producer tests cover phase failures, outcome accounting and
cleanup; sidecar tests reject mismatched/incomplete/ineligible artifacts and verify
statistics against known fixtures. Record a reproducible invocation for each
process, supported artifact versions, immutable samples and the gate result.
No external commit is considered delivered until its repository reference is
available; no workload or database contract changes are hidden in this verification.

### 2026-09-13 disposition

Retained under tic-c7bb, with the stale harness-owned confidence/gate assignment
replaced by the current independent-sidecar boundary. The additional tic-e305
dependency is real: this ticket cannot verify a comparator that is not delivered.
