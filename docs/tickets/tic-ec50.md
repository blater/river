---
id: tic-ec50
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - server
    - security
    - distribution
created: 2026-09-04T15:23:11.26945Z
deps:
    - tic-72ea
    - tic-615d
    - tic-867d
    - tic-b75d
---
# Deliver the installed authenticated riverd start and restart path

## Outcome

An installed foreground `riverd` starts a persistent database, accepts River
JDBC connections, shuts down cleanly, and reopens committed data on macOS/APFS,
Linux/ext4 and XFS, and Windows/NTFS.

## Scope

Compose the delivered instance, filesystem, audit, database, and transport
owners in `river-server-app`. Own argument/resource configuration, installed
packaging, readiness, and ordered shutdown. Follow
[ADR 0014](../adr/0014-riverd-instance-security.md) for exact behavior.
Migrate every River-owned plain listener/client caller, including JDBC, CLI,
benchmarks and tests, to authenticated configuration and delete the superseded
APIs in this delivery. Migration changes connection setup, not workload or SQL
semantics. Do not introduce temporary wrappers or optional authentication.

## Acceptance

Run installed help/version, first start, authenticated SQL, commit/restart,
port-zero readiness, conflicting starts, wrong credentials, startup failure,
and shutdown on each OS. Unix signals and Windows console shutdown enter the
same owner; forced termination follows crash recovery. Test the ADR's readiness
visibility/failure boundaries and resource/secret cleanup. Distribution and
source/compiled checks prove that no plain path remains. `tic-95e8` independently
checks the assembled delivery; `tic-9640` owns power-loss qualification.

## Stop boundary

No filesystem adapter implementation, new credential/audit mechanism, PostgreSQL
wire protocol, remote binding, service-manager integration, installer, stop/ps
command, renewal/archive command, or benchmark comparison policy. Missing
prerequisites return to their existing owners. Stop when the installed start,
JDBC, shutdown, and restart path works; no additional admin commands join it.
