---
id: tic-7ed6
status: closed
type: story
priority: 1
assignee: blater
delivery: code
base-commit: d2f5041050d365960eecf8cb74f69799324940b2
branch: ticket/tic-7ed6-single-runtime
delivered-commit: 70c12782890b7988b4065d670f6614d3df621ca6
created: 2026-09-09T18:46:37.896305Z
---
# Use one runtime record for server discovery

## Validation

- Affected server module tests/check passed with `--no-daemon`. Independent review
  confirmed lock/file-identity checks and cleanup remain coherent.
- Rebuilt native `bin/river` with O3 and the approved PGO profile. Ran it by its
  relative path using the real home: default/custom instances, one runtime file
  per running server, both aliases, SQL, endpoint/default stop, crash/restart
  persistence and cleanup all passed without unexpected errors.
- Updated the external harness readiness consumer in commit `3c5643b`; focused
  Go tests and an installed-executable workload passed: 195 commits, zero failed
  outcomes, graceful shutdown and inactive final state (65.26 TPS, diagnostic
  smoke only). Artifact: `river_harness_20260909_185910_a5e2f9d7`.
- Logs: `/private/tmp/river-7ed6-check.log`, `/private/tmp/river-7ed6-native.log`,
  `/private/tmp/river-7ed6-live.log`, `/private/tmp/river-7ed6-harness-live.log`.
- Runtime-record Slopmark decreased from 550.199 to 384.232. No database hot path
  changed.

## Acceptance Criteria

Real native default and custom-data-dir start, listing, endpoint/default stop, restart and stale-process handling work using a single runtime record.

