---
id: tic-1c4d
status: closed
type: story
priority: 1
assignee: blater
parent: tic-bf0b
delivery: code
branch: ticket/tic-ec50-riverd-launcher
tags:
    - riverd
    - security
    - performance
links:
    - tic-ec50
created: 2026-09-08T00:00:00Z
---
# Remove SQL/security audit from the riverd prerequisite path

Remove the mandatory SQL/security audit requirement and audit archive command
from the installed `riverd` contract and its active prerequisite graph. Keep
TLS, authentication, authorization, instance ownership, filesystem safety,
database/WAL durability, recovery, and resource cleanup required.

## Scope

Delete the audit log, coordinator, queues, durable records, and their tests;
remove audit-only request metadata and instance bootstrap/recovery state. Keep
permission and credential-validity checks at every SQL execution boundary.

Update ADR 0014, the standalone-server plan, CLI contract, roadmap, and
River-owned tickets so the former `tic-a221`/`tic-72ea` design and measurements
are clearly historical and superseded. Remove audit archive grammar and active
dependencies; do not add a placeholder implementation or near-term audit study.

Any future audit proposal requires a concrete architecture that supports neutral
TPS, latency, and resource impact before reconsideration. That prerequisite is
architectural and measured; it is not satisfied by the historical candidate.

## Acceptance criteria

- The installed CLI has no `riverd audit archive` command.
- `riverd` readiness and acceptance require authentication, authorization,
  database/WAL durability, and cleanup without requiring SQL/security audit.
- The active roadmap has no audit implementation or archive prerequisite.
- Historical audit tickets and measurements remain intact and are labelled
  superseded; credential renewal remains available as its own lifecycle work.

## Validation (2026-09-08)

Two baseline samples without overlapping builds or workloads from the existing audited JARs measured
8.2 and 8.2 TPS (`/private/tmp/riverd-noaudit-before-2` and `before-3`). Both
used GraalVM 25, tiny/standard, four terminals, one warehouse, seed 42, two
seconds of warmup and ten measured seconds. The earlier `before-1` sample is
excluded because an agent built in the wrong checkout concurrently. Those
unintended source edits were restored and all later builds were serialized.

The affected server/app/client/JDBC/CLI/benchmark test tasks passed in
`/private/tmp/riverd-noaudit-focused-1.log`. A mixed expiry/audit test was
retained as an expiry-denial test after review; the full clean run includes it.
Independent review found no lost credential, authorization, identity ordering,
owner-lock, or cleanup guarantees. Slopmark improved for the instance owner
(245.744 to 221.793), identity (988.965 to 935.340), and server (122.729 to
113.333); the audit implementation and coordinator responsibilities are gone.
Raw rankings: `/private/tmp/riverd-noaudit-slopmark-before.txt` and
`/private/tmp/riverd-noaudit-slopmark-after.txt`.

## Candidate results

The clean `--no-daemon --no-parallel clean test :river-bench:installTps
:river-server-app:installDist verifyModuleGraph --continue` build passed.
JUnit reports 1,881 tests: 1,863 passed, 18 skipped, no failures or errors.
Unchanged Gradle tasks used cached results. The count fell by 16 audit-only
tests (14 across four deleted audit classes, two JDBC audit tests); positive
permission, authentication, expiry, cancellation and lifecycle checks remain.
The 18 skips remain two opt-in benchmarks, eleven Linux-only tests and five
Windows-only tests in this macOS run. Evidence:
`/private/tmp/riverd-noaudit-clean-tests.log`.

Audit-free samples measured 71.9 and 71.9 TPS with zero errors and passing
reconciliation/capture (`/private/tmp/riverd-noaudit-after-1` and `after-2`).
The user then reported battery power. Power conditions were not independently
controlled throughout these samples; these are diagnostics, not an AC
performance-neutrality claim or a comparison with the historical 163 TPS.
A subsequent master control timed out during warmup and produced no TPS
(`/private/tmp/riverd-noaudit-control-1`); it is excluded from comparison.
No further performance runs are required for this removal while on battery.

Installed macOS create, generated-client-file authentication, JDBC commit,
shutdown, restart and read passed with empty stderr and cleaned runtime state:
`/private/tmp/riverd-delivery-evidence/launcher-noaudit-installed-macos/summary.json`.

Installed Linux JDBC create/commit/shutdown/restart/read passed on both ext4
and XFS with the rebuilt distribution. Evidence:
`/private/tmp/riverd-noaudit-installed-linux.log`. The task-owned VM was stopped
after validation. Windows execution remains part of the standalone milestone,
not a prerequisite for this audit removal's completion.
