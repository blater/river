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

Promotion evidence uses the cooperative host boundary owned by `tic-0636`.
The shared River performance lease proves full-interval exclusion among
lease-participating River workflows. Bounded periodic process observations
reject nonparticipants that are observed, but cannot prove absence between
samples. Each promoted interval therefore requires an operator attestation
that no uncoordinated River build, test, profile, client/server, harness, or
database workload ran on the host. Idle Gradle daemons are allowed.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and cleanup residue, passing invariants, reconciled victim/retry accounting, and a statistically stated scaling conclusion; evidence references, source tag, cooperative lease record, bounded host observations, and operator no-uncoordinated-work attestation are recorded. Evidence does not claim that periodic observation proves unconditional host-wide absence between samples.

## Notes

### 2026-09-04 P0 revalidation blocked

The independently reviewed 40-run evidence records internally correct
serializable runs but a detected standard-mix 10:2 scaling regression. The
disjoint P0 prerequisites `tic-af29` and `tic-0636` must close before the full
rerun. Keep this ticket `in_progress`; no clean gate, P0 certification, or
performance checkpoint tag is accepted.
