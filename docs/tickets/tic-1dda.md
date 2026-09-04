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

The clean-source 40-run serializable matrix and blocked gate decision are
recorded in
`docs/delivery/evidence/2026-09-04-tic-1dda-p0-revalidation.md`. All runs were
internally correct with zero retries, but the standard-mix 10:2-terminal ratio
has an individual 95% interval wholly below 1.0. Current diagnostics also lack
the required successful-block causal classification, retained-snapshot gauge,
mixed-isolation artifact, and promotion-grade build/classpath/host-exclusion
provenance. Keep this ticket `in_progress`; no clean gate or performance tag is
accepted. The evidence proposes two disjoint P0 prerequisites for the
integrator to create and link before the full rerun.
