---
id: tic-da4e
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
tags:
    - performance
    - tpcc
    - p3
    - profiling
deps: []
links:
  - tic-32b3
created: 2026-09-04T15:10:07.987949Z
---
# Establish current-master mechanism baselines for the three performance epics

### Outcome

Give the lead a current, reproducible decision on repeated execution setup,
commit-force serialization and protocol work before selecting production changes.
This replaces the stale requirement to finish Payment collapse before profiling.
It consumes accepted source `5f7e6eb0` or the latest pushed stable checkpoint at
execution time, recording the exact revision; no old profile is current proof.

### Scope and owner

Runtime/performance lead with relational, WAL and protocol owners reviewing their
respective traces. Reuse the installed-server harness and existing JFR/profiling
facilities; do not build a new telemetry framework. Preserve earlier failed P0
criteria and the separately owned `tic-1dda` gate. Profiling does not certify P0.

### Deliverables and acceptance

- Reproduce sample/all at four workers, one warehouse, seed42, retries20,
  5s warmup/30s measurement as the short diagnostic anchor. Use two matched
  uninstrumented samples; lengthen and interleave controls to explain variation.
- Separately capture measured-window inclusive CPU/allocation and blocking/force,
  lock, queue, socket-write and exchange evidence. Exclude startup/load; measure
  profiler overhead; disclose missing unmounted virtual-thread waits. Report
  per-attempt and per-commit denominators and retries; do not add overlapping
  percentages or equate CPU share with an expected TPS gain.
- Trace current preparation sharing, binding-view construction, FK discovery and
  its existing unchanged-key check, scratch reset, physical writer/force handoff,
  and transport barriers. Name exact owners and distinguish already delivered work.
- Give each candidate child an implement/defer/reject decision, expected cost
  movement, workload category and focused correctness probe. Absence of a cost
  is valid evidence to reject that candidate; no speculative code requirement.
- Record actual target isolation, commit-flush contract, transport/TLS, JDK,
  resource settings and report eligibility. The old MariaDB Unix-socket versus
  River TCP/TLS run is a diagnostic, not an engine-only or parity conclusion.
  A fresh MariaDB run is needed only if this investigation uses its timing.

One evidence commit, immutable artifact paths, correctness/cleanup outcomes and
reviewed candidate decisions complete this ticket. It does not implement fixes,
produce formal cross-database claims, or depend on lifecycle certification.
Follow [the shared contract](../plans/performance-three-epics-handover.md).
