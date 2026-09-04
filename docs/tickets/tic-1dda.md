---
id: tic-1dda
status: in_progress
type: investigation
assignee: blater
parent: tic-5db4
delivery: evidence
base-commit: 10acfa58664f715c0023b31280d75eabdcbfa5cd
branch: ticket/tic-1dda-p0-revalidation
evidence:
    - docs/delivery/evidence/2026-09-04-tic-1dda-p0-revalidation.md
tags:
    - performance
    - tpcc
    - p0
    - evidence
deps:
    - tic-af29
    - tic-0636
created: 2026-09-04T15:10:06.990273Z
---
# Revalidate the P0 promotion matrix on stable master

Run the required serializable 50/50 discriminator and standard-mix terminal sweeps from a pushed clean source checkpoint.

## Design

Use the exact P0 correctness, failure-mode displacement, correlation, cleanup, and performance-regression criteria in docs/perf_review.md. Preserve every anomalous sample.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and cleanup residue, passing invariants, reconciled victim/retry accounting, and a statistically stated scaling conclusion; evidence references and the source tag are recorded.

## Notes

### 2026-09-04 P0 revalidation blocked

The independently reviewed 40-run evidence records internally correct
serializable runs but a detected standard-mix 10:2 scaling regression. The
disjoint P0 prerequisites `tic-af29` and `tic-0636` must close before the full
rerun. Keep this ticket `in_progress`; no clean gate, P0 certification, or
performance checkpoint tag is accepted.
