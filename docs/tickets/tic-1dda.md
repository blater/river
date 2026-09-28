---
id: tic-1dda
status: parked
priority: 3
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
    - tic-b1b7
links:
    - tic-0636
    - tic-1fe7
    - tic-b368
    - tic-7352
created: 2026-09-04T15:10:06.990273Z
---
# Revalidate the P0 promotion matrix on stable master

## Maximum Change Shape

Run exactly the declared mixed-isolation reproducer and serializable 50/50 and
standard matrices, then update the existing evidence record and, only after an
all-green result, the accepted checkpoint ledger. No production, test-harness,
build-tool, or instrumentation source may change under this ticket, and no
additional workload family or post-hoc acceptance rule may be added.

## Design

Use the exact P0 correctness, failure-mode displacement, correlation, cleanup, and performance-regression criteria in docs/perf_review.md. Preserve every anomalous sample.

The accepted historical `tic-0636` implementation and its later `tic-ed12` and
`tic-d7c2` follow-on work remain linked for context, but their artifact,
lease, host-observation, and receipt machinery is not an acceptance dependency
for this investigation. For each matrix cell, record the effective workload
configuration, isolation, branch name, and a meaningful variation label when
multiple scenarios are tested on the same branch. Preserve every raw result
and use the workload artifact and run metadata to reconcile correctness and
scaling outcomes.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and
cleanup residue, passing invariants, reconciled victim/retry accounting, and a
statistically stated scaling conclusion. The evidence records the source
branch, variation label, effective workload configuration, and the retained
run metadata and acceptance artifacts needed to reproduce the comparison.

### User-authorized reproducer prerequisite, 2026-09-14

The user approved a general SQL concurrency refinement after the readiness audit.
[tic-b1b7](tic-b1b7.md) now owns the deterministic fixture and causal proofs for
lock paths, deadlocks, Repeatable Read and Serializable, with Payment/New Order
retained as an integration regression case. It is an explicit prerequisite here.
That ticket owns test implementation; this ticket remains evidence-only and keeps
its existing claim, historical failures and unchanged promotion criteria.
Completion of the fixture permits P0 revalidation; it does not certify P0 or
admit WAL overlap before the remaining gate passes.

### Accepted general concurrency fixture, 2026-09-14

The user-authorized [tic-b1b7](tic-b1b7.md) supplies the controlled general SQL
lock/deadlock/isolation proofs and real Payment/New Order integration regression.
Its [declared matrix, proof limits and reproducible command](../plans/p0-general-concurrency-reproducers.md)
replace the missing-fixture readiness finding above. The fixture is an explicit
prerequisite; its completion permits this evidence-only campaign to resume.
It does not certify P0, erase the historical failed 10:2 scaling result, change
this ticket's claim or promotion criteria, or admit WAL overlap implementation.

### User-directed deferral, 2026-09-14

The user explicitly deferred scaling-regression revalidation and the missing
warmup client accounting, and removed this ticket as a WAL prerequisite.
Return to open rather than retain an active campaign claim. Historical branch
and base metadata describe the prior claim, not ongoing work. P0 is not passed.

The resumed run on production 5dcee338 stopped after two of 40 planned cells.
Both completed invariants and cleanup; standard b1-s2 had three reconciled
measured DEADLOCK retries and an epoch-1 victim with attempt tag 340 whose
warmup client disposition was not emitted. Exemplar capacity also saturated.
No scaling inference follows; 38 cells were not run. Preserve raw artifacts at
/Users/blater/src/river/benchmark-results/p0-20260914/. No replacement campaign
or accounting code is part of the WAL delivery.

WAL proceeds through its remaining source-backed resource, durability,
concurrency, recovery and performance checks. Resumption of this independent
campaign requires a later scheduling decision; its original scope is unchanged.
