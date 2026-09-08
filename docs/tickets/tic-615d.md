---
id: tic-615d
status: in_progress
type: story
assignee: blater
parent: tic-bf0b
delivery: code
base-commit: 9ef72534f347728e76bb5a38e93c13a246cda7e6
branch: ticket/tic-615d-riverd-credentials
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

## Validation in progress (2026-09-08)

The credential generation, persistence/reload and generated client configuration
tests pass on APFS: six tests, no failures or skips. The affected client and
protocol suites also pass: 16 and 62 tests respectively, no failures or skips.
All Gradle invocations used `--no-daemon` and ran serially.

Evidence: `/private/tmp/riverd-delivery-evidence/credentials-tests-7.log` and
`/private/tmp/riverd-delivery-evidence/credentials-affected-tests-1.log`.
The combined credential and identity suite now passes 15 tests, including eight
APFS recovery cases and strict record encoding checks. Evidence is
`/private/tmp/riverd-delivery-evidence/credentials-tests-10.log`. Recovery
preserves unexpected state, refuses a live prior owner, and delays repair until
the component owners have validated their contents. The record codec is separate
from lifecycle handling; no generic recovery or record framework was added.

Slopmark flagged the growing lifecycle owner (655.753 before the codec extraction,
622.968 afterward; codec 21.3352). Review focused on mutation ordering and handle
ownership, not reducing the score mechanically. Strict-restart owner handoff,
pre-bootstrap recovery and committed bootstrap residue cleanup remain open.
This checkpoint does not close the ticket or establish the installed lifecycle.

## Restart recovery checkpoint (2026-09-08)

Strict restart now retains the prior owner record and publishes a new owner
nonce only after caller component validation. Creation handles an empty or sole
lock directory and a bound pre-authority bootstrap stage. A canonical stage and
prior lock must agree before removal. A fresh proposed incarnation never
overrides a committed bootstrap identity.

Instance publication forces the data directory before cleanup. Committed
residue cleanup validates every matching object before mutation, removes and
forces stage residue first, then removes bootstrap evidence last. Directory
listing failures propagate rather than masquerading as absence. Record-safe
absolute path/command validation is shared by producers and parsers.

The combined APFS suite passes 23 tests (six credential, 17 identity), with no
failures or skips: `/private/tmp/riverd-delivery-evidence/credentials-tests-16.log`.
Independent review and integrator review covered ownership, retryable cleanup
and durable ordering. Slopmark rose with the required recovery paths: identity
988.965, credentials 385.589, record codec 114.59 (`credentials-slopmark-5.txt`).
Review kept one identity/lock lifecycle owner and one canonical record codec;
no second recovery authority, registry owner or generic recovery framework was
introduced. Further launcher responsibilities must remain outside this owner.

This feature checkpoint is not installed lifecycle acceptance. The staged
component owners still need to supply validated create/reopen or bounded
partial-child recovery before launcher composition can complete.
