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
packaging, readiness, and ordered shutdown. Follow the
[CLI contract](../riverd-cli.md) for user-facing behavior and
[ADR 0014](../adr/0014-riverd-instance-security.md) for security and recovery.
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

## User experience acceptance (2026-09-07)

- `riverd start --port=9192` selects a port; the default is 9191
  and port zero selects an available port. Optional `--ip=::1` selects IPv6
  loopback; the default IP is `127.0.0.1`. These replace `--listen` and `-L`.
  Help shows these examples and the
  data-directory option, defaults, foreground behavior and shutdown method.
- Bare `riverd` prints useful brief usage: short command descriptions, a start
  example, defaults and a pointer to full help. It does not list instances.
  `riverd help` and `riverd --help` print identical full help;
  `riverd start --help` explains startup in plain language. `riverd ps` owns
  instance listing. Invalid options give a concise error and point to help.
- First start creates the credentials automatically. Startup identifies the
  generated client configuration path without displaying secrets. Explain that
  TLS authenticates the server and the instance token authenticates the client.
- Ship one short, copyable JDBC example using the generated client settings.
  Users should not need to understand certificate generation, trust stores or
  protocol handshakes to connect. State how to obtain the River JDBC driver.
- Validate the documented sequence from a fresh installed distribution: help,
  start on a chosen port, connect, commit, stop and restart to read the data.
  Use the existing lifecycle tests; do not add a separate usability framework.

Delivery priority is this usable end-to-end path. Resolve routine reversible
choices locally. Cut off unrelated investigations; only a concrete blocker to
correctness, security, required platform support or this user flow may expand
work. Do not add speculative features, metadata, review gates or documentation
ceremony. Keep reviews scoped to changed behavior and reuse existing evidence.

## Stop boundary

No filesystem adapter implementation, new credential/audit mechanism, PostgreSQL
wire protocol, remote binding, service-manager integration, installer, stop/ps
command, renewal/archive command, or benchmark comparison policy. Missing
prerequisites return to their existing owners. Stop when the installed start,
JDBC, shutdown, and restart path works; no additional admin commands join it.
