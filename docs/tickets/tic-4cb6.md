---
id: tic-4cb6
status: open
type: story
assignee: blater
parent: tic-761e
delivery: documentation
tags:
    - riverd
    - benchmark
    - harness
    - contract
deps:
    - tic-9640
created: 2026-09-04T15:23:11.814707Z
---
# Publish a stable riverd consumer contract

Publish the executable, readiness, client-configuration, shutdown, version, and evidence identity contract consumed by independent stress tooling.

## Design

Expose files and stable records, not River implementation types. Include executable content fingerprint, child PID, instance path, endpoint, TLS certificate and token file paths, protocol version, status/exit semantics, and compatibility policy without secret values.

## Acceptance Criteria

The contract is sufficient for a process-level consumer to start, authenticate, execute, stop, and attribute one instance without Gradle, Java class names, source layout, process scans, or imports from River.
