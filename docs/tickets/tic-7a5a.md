---
id: tic-7a5a
status: open
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
tags:
    - performance
    - tpcc
    - p1
    - promotion
deps:
    - tic-f1bb
    - tic-845d
created: 2026-09-04T15:10:07.727217Z
---
# Run the P1 promotion matrix and publish a checkpoint

Evaluate the composed P1 mechanisms on the exact merged source using the durability funnel, terminal/warehouse sweeps, server profiling, and clean checkpoint build.

## Outcome

One reproducible evidence package makes a promote, reject, or blocked decision
for the exact composed P1 source. Promotion occurs only when correctness gates
pass and every direct mechanism moves its declared denominator without an
unexplained regression.

## In Scope / Owning Mechanism

This ticket owns only execution and interpretation of the accepted promotion
matrix, publication of its immutable evidence and ledger entry, and creation of
the checkpoint tag when the source qualifies. The already merged P1 mechanisms
remain owned by their implementation tickets.

## Non-goals

- Any production, test, instrumentation, harness, configuration-default, or
  build-logic fix.
- Retuning workloads or acceptance criteria after seeing results.
- Completing missing cleanup or evidence for a child ticket.
- Explaining a regression by silently changing the candidate source.

## Stop Conditions

Stop promotion on a failed invariant or recovery gate, unreconciled path or
force, missing provenance, invalid comparison, or repeated unexplained
regression. Record the blocker and open or return to the separately owned
ticket; never repair it here. Any source change after sampling starts
invalidates the candidate evidence and requires a fresh matrix.

## Maximum Change Shape

One promotion-only evidence package for one immutable source identity, plus its
ledger/checkpoint documentation and tag if accepted. No runtime behavior or
evidence-producing implementation may be changed under this ticket.

## Design

Report eligibility, successful cohorts, cohort distribution, direct reasons, force cause/bytes/time, queue and commit stages, lock holdings/waits, CPU, allocation, latency, TPS, failures, and provenance.

## Acceptance Criteria

Correctness and recovery gates pass; every path and force reconciles; repeated candidate/control samples support the conclusion; the accepted source, evidence ledger entry, and perf checkpoint tag are pushed.

## Notes

### 2026-09-04 ten-terminal architecture priority review

Classify each P1 child before promotion. Logical sealing, resource admission,
and WAL chunking may be accepted as correctness or scalability enablers with
neutral TPS and proved non-regression. Lock removal must move its declared lock
denominator. Durability overlap must reduce force-per-write or increase useful
cohorting by its declared mechanism. A throughput story that moves neither its
mechanism denominator nor end-to-end behavior is ineffective and blocks the P1
checkpoint even when all tests pass.
