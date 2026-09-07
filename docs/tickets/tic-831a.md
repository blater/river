---
id: tic-831a
status: in_progress
type: task
priority: 2
assignee: blater
delivery: documentation
base-commit: 0704daff7990ae0d0aa32930d749910efca8a86e
branch: ticket/tic-831a-riverd-platform-requirements
tags:
    - riverd
    - documentation
    - platform
created: 2026-09-07T19:43:43.380834Z
---
# Require portable riverd support and constrain its delivery tickets

Replace the Linux-only riverd filesystem contract with required macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS support. Preserve security and durability outcomes with platform-specific implementations and proportionate validation.

## Acceptance Criteria

ADR, server plan, README, and affected open tickets agree on mandatory platforms; no universal POSIX/SDS restriction or per-JAR power-loss retest remains; implementation and validation are not claimed.

## Delivery scope and validation

The user's required platforms are macOS and Linux plus at least one modern
Windows filesystem. The initial concrete matrix is APFS, ext4/XFS, and NTFS.
ADR 0014 now defines one portable security/durability contract, with platform
adapters where needed. ADR 0003 permits native operations when correctness
requires them. The server plan, open implementation/validation tickets, README,
and delivery queue carry the same requirement.

Removed the Linux-only POSIX/SDS mandate and the per-launcher-JAR qualification
format. Retained exclusive ownership, effective credential permissions, stable
object identity, atomic publication, ordered durability, safe stage recovery,
and real process-crash/power-loss validation. Evidence reuse depends on the
changed mechanism and platform assumptions. Windows paths, permissions, and
normal shutdown are explicit implementation/test requirements. This delivers
requirements only; no platform implementation or qualification is claimed.

Ticket validation, new local-link targets, and git diff checks pass. No
production files changed, so no build, TPS sample, or slopmark run was needed.

## Ticket scope reconciliation

The user also required streamlined tickets with explicit limits. Filesystem
work is separated into `tic-485d` (shared contract and APFS), `tic-867d` (Linux),
and `tic-b75d` (Windows). Credentials consume the first implementation; the
installed launcher requires all three. Existing credential/launcher/evidence
tickets now link to the ADR rather than copying its full specification. Audit,
stop, ps, and maintenance tickets have explicit stop boundaries. No top-level
epic or speculative platform framework was added.

One bounded independent architecture/security review found a false present-tense
implementation claim and remaining Unix-only shutdown wording. Both were
corrected in the owning contract and lifecycle references. The lead checked the
subsequent ticket split for one contract owner, disjoint adapter scope, correct
dependencies, and unchanged end-to-end acceptance. No further broad review ran.
