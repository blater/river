---
id: tic-d2e9
status: open
type: story
priority: 1
assignee: blater
parent: tic-2109
delivery: code
tags:
    - riverd
    - operations
    - registry
deps:
    - tic-0803
created: 2026-09-04T15:23:11.538879Z
---
# Complete bounded registry validation, ps, and multiple instances

Consume the final records published by `tic-ec50`, complete their validation
and stale-replacement behavior, add `ps`, and support independent instances
without scanning the process table.

## Design

Use the normalized-datadir digest filename and canonical
`riverd-registry-v1` record. Start may replace only a same-instance stale record
under its instance lock after proving the process absent. List only verified
live records in deterministic order; `ps` warns but never deletes stale or
invalid records.

## Acceptance Criteria

No-argument and ps behavior match; empty guidance, two-instance start/list/stop,
port collision, same-directory lock contention, stale-registry replacement,
warning-without-delete, and matching-record removal by shutdown/stop tests pass
without signalling unrelated processes.

## Required platforms (2026-09-07)

This delivery must work on macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
Use the amended ADR 0014 portable contract and platform adapters; do not require
POSIX permissions, Unix signals, or SecureDirectoryStream on every platform.
Platform-specific tests must preserve the same ownership, security, durability,
and recovery outcomes. The platform support is required, not yet implemented.

## Stop boundary

Own only registry validation, ps, and multi-instance composition over the existing start/stop path. No process-table discovery, service manager, remote administration, or new registry format without a demonstrated contract defect.
