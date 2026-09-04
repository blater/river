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
    - tic-61c2
    - tic-e305
created: 2026-09-04T15:10:08.524194Z
---
# Establish the matched family-level MariaDB gap baseline

Use river-harness to produce eligible River and MariaDB stress artifacts by
transaction family and standard mix, then compare them with the external
sidecar.

## Design

Use identical runner manifests and multiple longer interleaved samples. The
sidecar consumes only versioned artifacts and reports throughput, latency,
retries, failures, CPU where available, requests, bytes, and setup/runtime
provenance; do not compare against tools/tps-test.sh figures or import
river-harness internals.

## Acceptance Criteria

The baseline quantifies confidence and per-family gaps, proves artifact and
configuration eligibility, identifies the largest evidenced River mechanism,
and creates concrete owner-scoped tickets in the relevant repositories rather
than a generic parity rewrite.
