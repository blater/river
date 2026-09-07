---
id: tic-867d
status: closed
type: story
priority: 2
assignee: blater
parent: tic-bf0b
delivery: code
delivered-commit: 5170df2
base-commit: 9ef72534f347728e76bb5a38e93c13a246cda7e6
branch: ticket/tic-867d-riverd-linux
tags:
    - riverd
    - platform
created: 2026-09-07T19:51:40.363286Z
deps:
    - tic-485d
---
# Implement the riverd filesystem adapter on Linux/ext4 and XFS

## Outcome

Linux/ext4 and XFS passes the portable instance filesystem contract delivered
by `tic-485d`, so the installed launcher can use the same instance owner.

## Scope and acceptance

Implement only that contract's platform operations and run its shared fixtures
plus real filesystem permission, identity, locking, publication, cleanup, and
process-interruption cases. Cover effective permissions and ACLs, aliases, exclusive locking, and
publication/flush behavior on both required Linux filesystems.
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

## Implementation and validation (2026-09-08)

The Linux adapter uses descriptor-relative `openat2(RESOLVE_NO_SYMLINKS)`,
`statx` identity, `renameat2`, `getdents64`, nonblocking `flock`, and file and
directory `fsync`. ARM64 and x86-64 syscall numbers and flags are distinct;
other architectures return `FEATURE_NOT_SUPPORTED` before filesystem access.
No credential or database lifecycle policy enters this adapter.

Linux POSIX ACL masks are reflected in group mode bits. Checking owner and
mode therefore bounds named-user/group access too; creation modes restrict
inherited ACLs. Safe masked ACLs are accepted, effective access beyond the
private-file contract is denied. This removes an unnecessary libacl dependency
and rejection of harmless ACLs. This rule is Linux-specific; it does not replace
APFS or NTFS ACL checks. See the Linux [ACL contract](https://man7.org/linux/man-pages/man5/acl.5.html).

Validation used an isolated Ubuntu 24.04.4 VM, Linux 6.8.0-117 aarch64,
Temurin 25.0.4, and actual ext4 and XFS mounts. All 11 focused tests passed on
each filesystem as UID 1000, with zero failures or skips. They cover positional
I/O, identity, alias/hard-link rejection, permissions, lock lifetime, exclusive
and replacement publication, cross-parent moves, owned cleanup, and forced
process termination followed by lock reacquisition and reading forced data.
The duplicated lock assertions were removed from the alias test.

Separate real ACL probes on both filesystems accepted a masked named ACL,
rejected an effective named read grant, and proved that file creation under a
permissive default ACL restricts its effective mask. `:river-platform:check`
passed on macOS using `--no-daemon`; Linux-only tests were run in the VM rather
than counted as passing from macOS skips. Root reviewed native ABI and ownership;
the Linux author independently reviewed the ACL simplification.

Evidence is under `/private/tmp/riverd-delivery-evidence`: final
`linux-ext4-tests-final.log`, `linux-xfs-tests-final.log`,
`linux-ext4-acl-final.log`, `linux-xfs-acl-final.log`,
`linux-inherited-acl-final.log`, `linux-module-check-final-2.log`, and the two
Java probe sources. Earlier failed compile and preliminary runs are retained.
Slopmark bridge score fell from 120.29 to 94.47 after removing redundant ACL
machinery. The directory owner is 137.83; review found directory/native
operation ownership only, with no audit or launcher policy.

The adapter has not changed production database I/O and makes no TPS or
power-loss claim. x86-64 runtime testing, installed lifecycle validation and
power-loss qualification remain separate evidence; this run proves ARM64
ext4/XFS adapter behavior.
