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

Preserve `tools/tps-test.sh` and trace evidence after `tic-ec50` has already
migrated `TpccServerMain` to authenticated transport and deleted every plain
server/client API.

## Design

Keep the existing authenticated benchmark orchestrator only for the accepted
JFR, resource, deadlock, commit, terminal, and workspace-fingerprint producers
that `riverd` deliberately does not own. Move a producer only when the same
evidence is available through a named generic diagnostics boundary. Do not move
TPC-C flags into riverd or recreate a plain/optional-authentication path.

## Acceptance Criteria

No-argument TPS and trace scripts work through authenticated transport, preserve every accepted diagnostic and managed shutdown behavior, and show matched performance without synchronous per-statement audit-force regression.
