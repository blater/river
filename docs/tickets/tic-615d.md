---
id: tic-615d
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - security
    - identity
    - filesystem
created: 2026-09-04T15:23:11.178601Z
deps:
    - tic-485d
---
# Implement instance credentials and identity

## Outcome

Create one persistent database identity and credential generation, write the
client settings, and reopen them safely through the portable filesystem owner.
The format, security, and recovery authority is
[ADR 0014](../adr/0014-riverd-instance-security.md).

## Scope

Own first creation, strict restart, bound stage recovery, certificate/token
construction and destruction, and the single `river-client` configuration
parser. Create `river-server-app` only with this real instance consumer.
Consume `tic-485d`'s filesystem contract; platform adapters are owned by
`tic-485d`, `tic-867d`, and `tic-b75d`. Do not implement their operations here.
Keep the ADR's local explicit cryptographic provider and secret-lifetime rules.

## Acceptance

Prove first creation/reopen, correct client configuration, owner-only secret
handling, corruption/mismatch refusal, expiry, interrupted publication recovery,
and cleanup on failure. Use shared filesystem fixtures and the available APFS
adapter. The installed all-platform lifecycle gate remains with `tic-95e8`.
All required formats and failure outcomes follow the ADR; this ticket does not
invent additional metadata or duplicate its full specification.

## Stop boundary

No installed command/distribution, audit engine, credential-renewal command,
registry, service manager, new filesystem framework, or performance tooling.
Do not regenerate accepted missing credentials or add compatibility paths.
A missing platform operation goes to its adapter owner. Stop when creation and
restart can supply the validated instance and client settings to `tic-ec50`.
