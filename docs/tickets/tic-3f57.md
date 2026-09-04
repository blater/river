---
id: tic-3f57
status: open
type: story
assignee: blater
parent: tic-761e
delivery: code
tags:
    - riverd
    - benchmark
    - diagnostics
    - security
deps:
    - tic-95e8
    - tic-72ea
created: 2026-09-04T15:23:11.909416Z
---
# Preserve River diagnostics behind the authenticated lifecycle

Keep tools/tps-test.sh and trace tooling secure and reproducible while they still require River-specific JFR, resource, deadlock, commit, and terminal evidence.

## Design

Either consume riverd through a generic diagnostics boundary or retain a narrow benchmark orchestrator secured by the same TLS/auth contract. Do not move TPC-C flags into riverd or delete evidence producers prematurely.

## Acceptance Criteria

No-argument TPS and trace scripts work through authenticated transport, preserve every accepted diagnostic and managed shutdown behavior, and show matched performance without synchronous per-statement audit-force regression.
