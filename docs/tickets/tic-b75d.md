---
id: tic-b75d
status: in_progress
type: story
priority: 2
assignee: blater
parent: tic-bf0b
delivery: code
base-commit: 9ef72534f347728e76bb5a38e93c13a246cda7e6
branch: ticket/tic-b75d-riverd-ntfs
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

## Implementation checkpoint

The adapter uses `NtCreateFile` with a verified `RootDirectory`,
`OBJ_DONT_REPARSE`, protected owner-rights security descriptors for private
creation, `FILE_ID_INFO` identity, `NtSetInformationFile` for handle-based
rename/disposition, and `LockFileEx` on an independently duplicated handle.
Directory enumeration uses a fresh relative handle and `NtQueryDirectoryFile`.
No Windows runtime is available in this worktree. Microsoft documents the
data-sync flags of `NtFlushBuffersFileEx` as invalid for directory handles;
the adapter attempts direct `NtFlushBuffersFile` and reports unknown durability
when that operation fails. Directory namespace flush behavior requires Windows
runtime qualification and is not a power-loss claim.

The 2026-09-08 source checkpoint compiles with JDK 25 using
`./gradlew --no-daemon :river-platform:compileTestJava`. Evidence is
`/private/tmp/riverd-delivery-evidence/ntfs-compile-5.log`. Independent review
covered native-call signatures, ACL ownership and effective rights, and the
lock marker outside the readable owner record. Five runtime tests cover private
creation, locking, reparse refusal, repeated enumeration and publication.
They have not run on Windows; compilation does not qualify this adapter.

Windows-hosted execution and report upload await authorization. The prepared
workflow remains outside the repository at
`/private/tmp/riverd-delivery-evidence/ntfs-workflow.yml`; no GitHub workflow was
added or run. Keep this ticket open and do not promote this source checkpoint
until real NTFS tests pass. No TPS or durability claim follows from it.
