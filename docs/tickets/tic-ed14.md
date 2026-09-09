---
id: tic-ed14
status: in_progress
type: story
priority: 1
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - cli
    - server
    - distribution
created: 2026-09-09T09:34:26.662545Z
---
# Unify River client and server entry points

## Outcome and scope

One public `river` command runs the existing SQL client by default and the
existing server commands under `river server`. Port the current behavior and
help onto those names; do not redesign help organization, parsing policy or
option definitions in this ticket. tic-9cfd owns that subsequent refactor and
completeness pass; tic-a51d then delivers the native executable.

- `river CLIENT_PROPERTIES < script.sql` preserves the current client's
  arguments, input, output and exit behavior. Bare `river` selects client mode
  and reports missing required configuration using the existing client usage.
- `river server <arguments>` passes the remaining arguments to the existing
  server command owner. Preserve its command validation, help forms, defaults,
  availability labels and exit behavior. Bare `river server` shows its existing
  brief usage. Rename examples and usage prefixes to the new invocation.
- Add only the minimal root usage needed to explain the two modes and point to
  the existing server help. Root `help`, `-h` and `--help` show this overview;
  new nested help aliases and content expansion belong to tic-9cfd.
- Expose `river version` using the existing distribution version owner; retain
  the server version operation as `river server version`. Reserve these root
  command words; an identically named client file remains usable by explicit
  path, such as `./server`.

## Implementation boundary

Use one application entry point and in-process dispatch into existing client
and server owners. Only the outer main exits the process; callable runners
return exit codes. Reuse the existing parser and help implementations. Do not
introduce a shared command-tree framework, duplicate parser or help renderer.

Replace separate public launchers and migrate River-owned scripts, distribution
tests and examples together. Update the command names in docs/riverd-cli.md and
its references. Preserve process/readiness records and JDBC connection semantics;
renaming machine-readable fields is not part of renaming the executable.
Coordinate external consumers through their existing migration tickets.

Existing operations tickets retain stop, listing and credential renewal. No
new operations, interactive shell, connection discovery, SQL, authentication,
durability, service installation or remote-listening changes belong here.

## Acceptance and validation

- Focused tests prove client/server routing, argument forwarding, exit codes,
  existing help access, root usage and version. Port existing tests rather than
  duplicating the entire parser/help suite. Retain current unavailable outcomes.
- Exercise the assembled JVM distribution for client SQL through stdin,
  selected-port server start, authenticated commit, graceful shutdown and
  persistent restart. Confirm help/version require no instance mutation.
- Run affected module tests with --no-daemon. Native compatibility and TPS
  comparison belong to tic-a51d; complete help coverage belongs to tic-9cfd.

## Delivery

Use branch `ticket/tic-ed14-unified-river-command` and the normal ticket commit
trailer. Deliver only the executable entry-point migration on the JVM.
