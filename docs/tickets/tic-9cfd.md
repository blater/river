---
id: tic-9cfd
status: in_progress
type: story
priority: 1
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - cli
    - help
deps:
    - tic-ed14
created: 2026-09-09T09:45:02.361228Z
---
# Organise complete hierarchical help for the unified River command

## Outcome

After tic-ed14 ports the existing client/server interface to `river`, consolidate
help routing and rendering into a complete hierarchy. Cover every command and
group, with detail appropriate to its level. This ticket owns the help refactor,
organization, alias normalization and completeness pass, not command migration
or native packaging.

## Help contract

- `river help`, `river -h`, and `river --help` produce identical top-level help:
  purpose, default client/server modes, basic argument information, copyable
  examples and directions to detailed help. Do not dump every server option.
- For every topic below, `river help <topic>`, `river -h <topic>`,
  `river --help <topic>`, and `river <topic> --help`/`-h` are equivalent.
  `cli` is a help topic for the default client, not an additional execution mode.
  Thus `river cli --help` is valid help, but `river cli ...` does not introduce
  a second way to execute client SQL.
- Detail follows command depth, not the spelling of the help alias. Group help
  lists all immediate commands, their purpose and availability, basic usage,
  and how to request each command's full help. Leaf help lists every accepted
  positional argument and option, aliases, value syntax, defaults, constraints,
  relevant effects/errors and a copyable example. Commands without options say
  so, apart from their help switches.

| Help topic | Required coverage |
| --- | --- |
| `cli` | Client configuration path, stdin SQL framing, output, stop-on-error behavior, current arguments and limits; how to obtain client.properties |
| `server` | All server commands and groups below, foreground workflow, basic defaults, connection configuration and command-specific help pointers |
| `server start` | `-D PATH`/`--datadir=PATH`, `--port=PORT`, `--ip=ADDRESS`, `--maximum-connections=N`, `--ready-file=PATH`; defaults, ranges, port zero, relative paths, readiness, authentication and foreground shutdown |
| `server stop` | Data-directory aliases, `--timeout=DURATION`, default timeout and units, cooperative stop behavior, timeout outcome and availability |
| `server ps` | No operation options; current-user instance listing, fields, empty output and stale-record behavior, availability |
| `server credentials` | Group purpose, stopped-instance requirement, renewal command and detailed help pointer |
| `server credentials renew` | Data-directory aliases, stopped-instance requirement, replacement credentials and reconnection workflow, availability |
| `version`, `server version` | No operation options; explain reported version information |
| `help` | Topic syntax, equivalent help forms, hierarchy navigation and examples beyond start, including credentials renew |

Unknown topics, commands, misplaced options, duplicate/conflicting options and
invalid values return exit 2 with a concise error and relevant usage. Help and
version exit 0 without connecting, opening a listener, reading credentials or
changing instance files. Reserved root words are commands/help; a client file
with such a name can be selected using an explicit path such as `./server`.

The table is the complete current inventory, not a start-only checklist. Any
command present at implementation time must receive equivalent coverage.
Unavailable operations must be clearly marked as unavailable in both group and
leaf help; their documented options must not imply working implementation.
Their execution continues to return the named unsupported outcome.

## Implementation boundary

Consolidate help and routing around a small concrete command tree. Share option
names, value syntax and defaults with the owning parser so help cannot drift;
keep command-specific validation with its existing owner. Delete superseded
help renderers, alias branches and duplicate option definitions in this slice.
Do not add a plugin framework or rewrite server lifecycle behavior.

Update docs/riverd-cli.md into the unified user command contract and fix inbound
links. Replace the old distinction between brief -h and full --help with equal
aliases and detail determined by topic depth. Replace incomplete help content,
not the underlying availability of operations. Existing operations tickets
still own stop, listing and renewal; no audit command is introduced.

## Acceptance and validation

- Table-driven tests cover every topic in the inventory and every specified
  alias, including credentials/renew, stop, ps, version and help itself. Assert
  useful semantic content and option/default coverage, not whole-help snapshots
  or exact line wrapping.
- Verify every implemented option appears in its own leaf help, unrelated
  options are rejected and group help lists every immediate child. Unavailable
  commands have accurate availability labels and do not become executable here.
- Exercise assembled help from root through nested leaves without credentials,
  a database or listener. Invalid topics return concise relevant guidance.
- Run affected module tests with --no-daemon and a focused client/server smoke
  to confirm help refactoring did not change normal dispatch. No TPS campaign
  for presentation-only changes; native measurements belong to tic-a51d.

## Delivery

Use branch `ticket/tic-9cfd-hierarchical-help` and the normal ticket commit
trailer. Follow tic-ed14 and precede native packaging tic-a51d. No interactive
shell, new lifecycle commands, authentication changes or distribution redesign.
