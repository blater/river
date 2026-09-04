---
id: tic-7ec5
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
tags:
    - performance
    - tpcc
    - mariadb
    - parity
deps:
    - tic-e6c5
created: 2026-09-04T15:10:08.524194Z
---
# Establish the matched family-level MariaDB gap baseline

Measure eligible River/MariaDB controls by transaction family and standard mix using identical shared-harness manifests.

## Design

Use multiple longer interleaved samples and report throughput, latency, retries, failures, CPU where available, requests, bytes, and setup/runtime provenance; do not compare against tools/tps-test.sh figures.

## Acceptance Criteria

The baseline quantifies confidence and per-family gaps, identifies the largest evidenced River mechanism, and creates concrete owner-scoped optimization stories rather than a generic parity rewrite.
