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
    - tic-8e74
    - tic-0636
    - tic-d7c2
created: 2026-09-04T15:10:06.990273Z
---
# Revalidate the P0 promotion matrix on stable master

Run the required serializable 50/50 discriminator and standard-mix terminal sweeps from a pushed clean source checkpoint.

## Outcome

One immutable stable-master evidence bundle either passes every P0 correctness,
cleanup, provenance, exclusion, and scaling criterion or records the first
failed criterion without making a performance claim.

## In Scope / Owning Mechanism

This investigation owns only the predeclared workload execution,
reconciliation, statistical evaluation, and evidence record. It consumes the
diagnostics and guards delivered by `tic-af29`, `tic-8e74`, `tic-0636`, and
`tic-d7c2`.

## Non-goals

- Implement instrumentation, provenance, host exclusion, transaction cleanup,
  lock policy, or any database optimization.
- Change workload mix, isolation, durability, terminal counts, retry policy, or
  the statistical rule after observing results.
- Discard anomalous samples, repair a failed run in place, or issue a checkpoint
  tag from partial or incomparable evidence.

## Stop Conditions

Do not start until every declared prerequisite closes. Stop the promotion run
and preserve the evidence on source/configuration drift, ownership loss,
unreconciled output, cleanup residue, a correctness failure, or an invalid
comparison cell. A discovered database defect becomes a separate blocking
ticket; it is never fixed inside this investigation.

## Maximum Change Shape

Run exactly the declared mixed-isolation reproducer and serializable 50/50 and
standard matrices, then update the existing evidence record and, only after an
all-green result, the accepted checkpoint ledger. No production, test-harness,
build-tool, or instrumentation source may change under this ticket, and no
additional workload family or post-hoc acceptance rule may be added.

## Design

Use the exact P0 correctness, failure-mode displacement, correlation, cleanup, and performance-regression criteria in docs/perf_review.md. Preserve every anomalous sample.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and cleanup residue, passing invariants, reconciled victim/retry accounting, and a statistically stated scaling conclusion; evidence references and the source tag are recorded.

## Notes

### 2026-09-04 P0 revalidation blocked

The independently reviewed 40-run evidence records internally correct
serializable runs but a detected standard-mix 10:2 scaling regression. The
disjoint P0 prerequisites `tic-af29`, `tic-8e74`, `tic-0636`, and `tic-d7c2`
must close before the full rerun. Keep this ticket `in_progress`; no clean gate,
P0 certification, or performance checkpoint tag is accepted.

### 2026-09-05 scope-lock split

The prior combined prerequisites were narrowed without adding behavior to this
investigation. `tic-af29` now owns only scheduler-derived successful-block
classification and `tic-8e74` owns the independent terminal snapshot gauge;
`tic-0636` now owns only exact built-byte provenance and `tic-d7c2` owns
exclusive-host evidence. This ticket consumes all four and remains prohibited
from implementing or repairing any of them.
