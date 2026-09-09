---
id: tic-d2e9
status: closed
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

`river ps` supplies empty guidance and supports two-instance start/list/stop;
port collision, same-directory lock contention, stale-registry replacement,
warning-without-delete, and matching-record removal by shutdown/stop tests pass
without signalling unrelated processes.

## Required platforms (2026-09-07)

This delivery must work on macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
Use the amended ADR 0014 portable contract and platform adapters; do not require
POSIX permissions, Unix signals, or SecureDirectoryStream on every platform.
Platform-specific tests must preserve the same ownership, security, durability,
and recovery outcomes. The portable adapters are implemented; execution evidence is still required
for Linux and Windows.

## Stop boundary

Own only registry validation, ps, and multi-instance composition over the existing start/stop path. No process-table discovery, service manager, remote administration, or new registry format without a demonstrated contract defect.

## Current delivery — 2026-09-09

Coordinate with tic-0803 on `ticket/tic-0803-river-stop` in
`/private/tmp/river-0803` (lead integrator, stable base `b15b0a6b`).
`river ps` and `river server ps` list verified local current-user instances.
SERVER contains the exact HOST:PORT accepted by `river stop`, followed by DEFAULT
and DATA DIRECTORY columns. Empty output gives start guidance; stale or invalid
records warn and remain untouched. The same verified record selection serves
stop, with ambiguous endpoint matches rejected. No new registry format or
persistent instance-name catalog.

## Delivered — 2026-09-09

Implemented root and server aliases, host:port selection, default-instance stop,
and readable local listing. Full `check` and the standalone O3/PGO lifecycle
smoke pass on macOS/APFS. Tests cover concurrent stop, timeout cleanup, owner
exit before acceptance, stale-request recovery, wrong-owner rejection, and
committed data surviving stop/restart.

The adjacent native TPS pair was 143.383 candidate versus 143.767 stable; both
passed without failed or exhausted transactions. Earlier controls were faster,
so they are retained as evidence of host variation, not used to claim a gain.
See `docs/performance-checkpoints.md` for commands and artifacts. Linux/Windows
execution remains unclaimed and tracked by the existing platform validation
work; this change reuses their portable adapters.
