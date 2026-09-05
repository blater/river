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

For every matrix cell, the lease and bounded observations must begin before
source capture. Background observations continue through provisional metadata;
a final synchronous observation seals the ledger immediately before its
immutable publication under the still-held lease. The lease remains valid
through all evidence publication. Base run metadata is provisional, not a completion result. The
only consumable success is a canonical no-replace terminal receipt, published
after verified lease release, that binds the run/artifact identity, exact
metadata bytes, lease owner commitment, final host/checkpoint ledgers, and
release outcome. The receipt step is outside the exclusion interval. Missing,
failure, malformed, mutated, or colliding receipts invalidate the cell. All
River-owned consumers, including `tools/tps-p4.sh`, require v2 evidence and the
shared receipt validator; v1 evidence has no compatibility path.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and cleanup residue, passing invariants, reconciled victim/retry accounting, and a statistically stated scaling conclusion; evidence references, source tag, cooperative lease record, bounded host observations, operator no-uncoordinated-work attestation, and a shared-validator-accepted v2 success receipt are recorded. Every raw collector and retained ledger remains within its declared time/byte budget. Evidence does not claim that periodic observation proves unconditional host-wide absence between samples.

## Notes

### 2026-09-04 P0 revalidation blocked

The independently reviewed 40-run evidence records internally correct
serializable runs but a detected standard-mix 10:2 scaling regression. The
disjoint P0 prerequisites `tic-af29` and `tic-0636` must close before the full
rerun. Keep this ticket `in_progress`; no clean gate, P0 certification, or
performance checkpoint tag is accepted.
