---
id: tic-761e
status: open
type: epic
assignee: blater
parent: tic-e1c9
delivery: none
tags:
    - riverd
    - benchmark
    - harness
    - integration
deps:
    - tic-2109
created: 2026-09-04T15:22:14.870989Z
---
# R3: make riverd the benchmark lifecycle boundary

Make accepted riverd lifecycle and client configuration the prerequisite boundary for River stress runs and later external comparison.

## Design

river-harness remains the independent stress/workload runner. Cross-database comparison is a separate sidecar consuming stable artifacts, not River or river-harness implementation APIs.

## Acceptance Criteria

River diagnostics remain secure and reproducible; external harness migration is evidenced in its own repository; River benchmark promotion tickets depend on this epic's final lifecycle gate.
