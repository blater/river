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

## Design

Report eligibility, successful cohorts, cohort distribution, direct reasons, force cause/bytes/time, queue and commit stages, lock holdings/waits, CPU, allocation, latency, TPS, failures, and provenance.

## Acceptance Criteria

Correctness and recovery gates pass; every path and force reconciles; repeated candidate/control samples support the conclusion; the accepted source, evidence ledger entry, and perf checkpoint tag are pushed.
