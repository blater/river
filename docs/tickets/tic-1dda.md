---
id: tic-1dda
status: in_progress
type: investigation
assignee: blater
parent: tic-5db4
delivery: evidence
base-commit: 10acfa58664f715c0023b31280d75eabdcbfa5cd
branch: ticket/tic-1dda-p0-promotion-matrix
tags:
    - performance
    - tpcc
    - p0
    - evidence
deps:
    - tic-638b
    - tic-6afb
created: 2026-09-04T15:10:06.990273Z
---
# Revalidate the P0 promotion matrix on stable master

Run the required serializable 50/50 discriminator and standard-mix terminal sweeps from a pushed clean source checkpoint.

## Design

Use the exact P0 correctness, failure-mode displacement, correlation, cleanup, and performance-regression criteria in docs/perf_review.md. Preserve every anomalous sample.

## Acceptance Criteria

Every run has matching effective isolation, zero unexplained outcomes and cleanup residue, passing invariants, reconciled victim/retry accounting, and a statistically stated scaling conclusion; evidence references and the source tag are recorded.
