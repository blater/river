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
    - tic-45a7
created: 2026-09-04T14:59:38.586402Z
---
# Interim gate: sustain 500 committed TPS

Define and pass a reproducible 500 committed TPS interim engineering milestone
through river-harness and the accepted riverd lifecycle, without mislabelling
it as tpmC, 50x, Alpha3, or MariaDB parity.

## Design

River-harness is the stress/workload runner and riverd is its River lifecycle
boundary. The gate must declare workload, scale, isolation, durability, host,
sample count, transaction count, confidence method, correctness, and provenance
before measuring candidates. River's TPS scripts remain diagnostic producers,
not this promotion denominator.

## Acceptance Criteria

River's predeclared 500 TPS lower-bound criterion passes with reconciled
outcomes and invariants on pushed, tagged River, riverd, and river-harness
revisions; the existing 1,000 TPS Alpha3 requirement remains unchanged.
