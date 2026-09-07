---
id: tic-b75d
status: open
type: story
priority: 2
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - platform
created: 2026-09-07T19:51:40.376424Z
deps:
    - tic-485d
---
# Implement the riverd filesystem adapter on Windows/NTFS

## Outcome

Windows/NTFS passes the portable instance filesystem contract delivered
by `tic-485d`, so the installed launcher can use the same instance owner.

## Scope and acceptance

Implement only that contract's platform operations and run its shared fixtures
plus real filesystem permission, identity, locking, publication, cleanup, and
process-interruption cases. Cover drive paths, case aliases, reparse points, effective inherited ACLs,
file-sharing/deletion behavior, and native lock/flush requirements.
Record the tested platform/runtime. Native code belongs inside this adapter
where a proved Java API gap requires it. Power-loss qualification remains with
`tic-9640`; a passing probe is not a durability claim.

## Stop boundary

No credential, launcher, SQL, protocol, service-manager, installer, or benchmark
policy. Do not redefine the shared contract or copy lifecycle state machines.
If the contract cannot express a required guarantee, report the concrete missing
operation to its owner before changing it. An unrelated database I/O defect
gets a separate ticket. This ticket closes when this adapter passes; it does
not absorb the full operational qualification campaign.
