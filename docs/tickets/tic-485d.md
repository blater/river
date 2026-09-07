---
id: tic-485d
status: open
type: story
priority: 2
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - platform
created: 2026-09-07T19:51:40.349907Z
deps:
    - tic-11a5
---
# Implement the riverd filesystem contract on macOS and APFS

## Outcome

The instance/credential work in `tic-615d` can use a tested filesystem boundary
on macOS/APFS. This is the first concrete implementation of the portable
[ADR 0014 contract](../adr/0014-riverd-instance-security.md#required-platforms-and-filesystem-guarantees).

## Scope

Define only the operations the instance owner needs: effective permissions,
verified object access, exclusive locking, staging/publication/replacement,
durable namespace changes, and exact-owned cleanup. Reuse River's existing
file/directory contracts. Supply the APFS implementation and shared contract
fixtures; use native calls only for a proved gap in the supported Java APIs.
This ticket owns the shared operation contract. Later platform adapters consume
it rather than introducing another filesystem or lifecycle abstraction.

## Acceptance

Real APFS tests cover permissions, aliases/redirection, competing lock owners,
exclusive versus replacement publication, failed flush/cleanup, and interrupted
stages. Deterministic fixtures check the required failure ordering. Record the
actual OS/JDK/provider and operations. API success is not power-loss evidence;
`tic-9640` owns that qualification. No full TPS campaign is required unless a
production database I/O path changes; then measure the affected path.

## Stop boundary

No certificate/token logic, launcher, registry, new control-file format,
general-purpose VFS, installer, or benchmark tooling. Stop when `tic-615d`'s
filesystem calls are supported. A discovered unrelated storage/recovery defect
gets a separate ticket and blocks only the work that needs it.
