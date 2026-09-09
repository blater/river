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

Run River as the standalone `river server` process via its public protocol
client. Use the same logical workload against the MariaDB target; neither
PostgreSQL wire support nor a PostgreSQL target is required. This comparison
campaign does not block the functional harness migration in tic-bfca.

Use identical runner manifests and multiple longer interleaved samples. The
sidecar consumes only versioned artifacts and reports throughput, latency,
retries, failures, CPU where available, requests, bytes, and build/version
labels and workload configuration; do not compare against tools/tps-test.sh
figures or import river-harness internals.

## Acceptance Criteria

The baseline quantifies confidence and per-family gaps, proves artifact and
configuration eligibility, identifies the largest evidenced River mechanism,
and creates concrete owner-scoped tickets in the relevant repositories rather
than a generic parity rewrite.

## Functional prerequisite delivered

Harness commit `15ca297` produces valid, matching River/MariaDB all-family
artifacts through the installed server. See tic-bfca for diagnostic results.
This ticket remains open for the longer family-level comparison campaign and
independent comparator; a short smoke pair does not close that acceptance.
