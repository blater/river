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
created: 2026-09-04T15:10:08.162434Z
---
# Verify river-harness automation of the 500 TPS gate

Verify that river-harness implements the accepted interim contract as a
mechanically runnable, machine-verifiable stress gate without hiding phase
failures or emitting misleading zero-TPS results.

## Design

Implementation belongs to the river-harness repository and carries its own
ticket and commit. It must preserve semantic validation and persist
source/configuration fingerprints, riverd identity, sample identities, raw
outcomes, confidence calculation, invariants, and artifact paths. No gate code
is added to River core.

## Acceptance Criteria

A linked external commit has focused invalid-run and statistics tests; the
runner rejects mismatched or incomplete samples; a documented command
reproduces the gate through riverd without changing database behavior.
