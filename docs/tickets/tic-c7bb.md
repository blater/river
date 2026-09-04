---
id: tic-c7bb
status: open
type: epic
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - 500tps
    - promotion
deps:
    - tic-723f
created: 2026-09-04T14:59:38.586402Z
---
# Interim gate: sustain 500 committed TPS

Define and pass a reproducible 500 committed TPS interim engineering milestone without mislabelling it as tpmC, 50x, Alpha3, or MariaDB parity.

## Design

The gate must declare workload, scale, isolation, durability, host, sample count, transaction count, confidence method, correctness, and provenance before measuring candidates.

## Acceptance Criteria

River's predeclared 500 TPS lower-bound criterion passes with reconciled outcomes and invariants on a pushed tagged source checkpoint; the existing 1,000 TPS Alpha3 requirement remains unchanged.
