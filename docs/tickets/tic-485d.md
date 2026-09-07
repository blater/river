---
id: tic-485d
status: closed
type: story
priority: 2
assignee: blater
parent: tic-bf0b
delivery: code
base-commit: cda33bf2dc464f54b8935eb5b6c5fdef71103c9f
branch: ticket/tic-485d-riverd-filesystem
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

## Implementation notes

The APFS adapter is descriptor-relative and uses Java 25 Foreign Function and
Memory calls to `openat`, `mkdirat`, `fstat`/`fstatat`, `fdopendir`/`readdir`,
`renameatx_np`, `unlinkat`, `fsync`, and `flock`. It verifies the effective UID,
owner-only writable modes, and ACL allow entries on every opened object. A
deny-only extended ACL is accepted; an ACL with an allow entry is rejected
until its principal can be proved owner-only. Directory publication across
staging and data parents forces both parent descriptors before returning
durable success. Exact file-key fencing for unlink is unavailable in Darwin's
unlink API; cleanup revalidates through the verified parent descriptor and
does not claim atomic protection from same-account namespace writers, which
are outside the accepted threat boundary.

## Validation and review (2026-09-07)

Delivered the shared capability API and APFS adapter. Private directory opens
are distinct from trusted ancestor opens. File descriptors retain stable
identities; lock handles own duplicated descriptors and survive file close.
Existing insecure files and hard links are refused without deletion. Rename
publication has no hard-link residue; directory moves force both parents.
macOS namespace force uses fsync then F_FULLFSYNC. Native errno is captured
with the downcall into GC-owned thread-local storage, so later calls or virtual
thread migration cannot replace the reported error.

`GRADLE_USER_HOME=/private/tmp/riverd-gradle-filesystem ./gradlew --no-daemon
--project-cache-dir /private/tmp/riverd-project-cache-filesystem
:river-platform:check` passed: 14 tests, no failures or skips. Coverage includes
11 actual APFS cases for private/ancestor access, alias and hard-link refusal,
lock lifetime, exact-owned cleanup, exclusive/replacement publication, staged
restart, cross-parent publication, and positional/short I/O. The tested host
is macOS 26.5.2, arm64, local APFS, GraalVM/JDK 25. Intel symbol selection was
checked against the installed SDK; Intel runtime execution was not performed.

Root reviewed native signatures, field widths, ownership, first-failure
propagation and shared API fit; Luna independently reviewed security and the
final native error/listing changes. Review corrections were tested; no
production database I/O path changed. No TPS claim or power-loss qualification
is made. Installed cross-platform tests and storage qualification remain with
`tic-95e8` and `tic-9640`.

Evidence: `/private/tmp/riverd-delivery-evidence/apfs-check-final-2.log`,
`apfs-module-tests-4.log`, the platform JUnit XML in the feature worktree, and
`apfs-slopmark-final.txt`. Slopmark review: native bridge about121, directory
owner64.91; these remain native I/O and directory ownership respectively,
without credential, audit or lifecycle policy. The original compact baseline
is `slopmark-baseline.txt` in the same evidence directory.
