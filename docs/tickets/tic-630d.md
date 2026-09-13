---
id: tic-630d
status: open
type: investigation
assignee: blater
parent: tic-c7bb
delivery: evidence
tags:
    - performance
    - tpcc
    - 500tps
    - promotion
deps:
    - tic-bd79
    - tic-miriel
    - tic-7a5a
    - tic-rian
    - tic-45a7
created: 2026-09-04T15:10:08.245373Z
---
# Run the interleaved 500 TPS promotion campaign

Execute the accepted river-harness 500 TPS gate through riverd against a clean
merged candidate and interleaved stable control.

### Design

Serialize host workloads, preserve River, riverd, and river-harness identities
with all artifacts, investigate repeatable regressions at the nearest feature
checkpoint, and report observed confidence rather than extrapolation.

### Acceptance Criteria

The predeclared lower-bound target passes with zero failed or unexplained outcomes and passing invariants; source, evidence, merge, checkpoint tag, and rollback point are pushed.

### 2026-09-13 promotion dependency correction

Contract definition tic-8561 may proceed before optimization. This actual
campaign explicitly retains all three integrated evidence gates and lifecycle
certification. Before execution inspect their disposition records: a rejected
candidate must not be reported as an implemented optimization, and a superseded
container cannot stand in for a passed performance gate. Sidecar delivery remains
required through tic-bd79. No changed source or acceptance rule during sampling.
