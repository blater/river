---
id: tic-1fe7
status: in_progress
base-commit: aa9fb062a3adb6ed64159c7038b7991084a896ae
branch: ticket/tic-1fe7-tps-evidence-contracts
type: story
priority: 2
assignee: blater
parent: tic-5db4
delivery: documentation
tags:
    - performance
    - tpcc
    - p0
    - architecture
    - provenance
created: 2026-09-07T11:31:44.369339Z
links:
    - tic-d7c2
    - tic-1dda
---
# Reconcile separate-build TPS provenance and host contracts

Reconcile the historical tic-0636 delivery with the later intentional make.sh/TPS separation and host/classpath gate removals. Name the smallest current prerequisite owners without changing database or workload behavior.

## Outcome and decision

Keep `./make.sh` and `tools/tps-test.sh` separate. Build artifact validity and
measurement host ownership have different lifetimes; prove each directly,
without transferring a lease between commands or rebuilding inside TPS.

The accepted historical `tic-0636` delivery remains closed. Commits `866f1f4`
(separate make command) and `8609d49` (remove host/classpath gates) superseded
its wired behavior. Current v2 receipts can succeed with empty host ledgers,
a synthetic owner and no launched-byte proof; their success does not satisfy
the P0 gate. Preserve that history rather than treating the old closed ticket
as proof of current behavior or silently restoring the removed workflow.

## Current owners and order

1. `tic-ed12` binds the separately produced runtime artifact to the source,
   build command/result/toolchain and launched bytes, validates that binding
   before use and at final publication, and makes missing guarantees explicit
   in the one canonical receipt/consumer contract. It does not enforce host
   exclusion. The real immediate consumers are `tps-test` and `tps-p4`.
2. `tic-d7c2` wires one existing cooperative lease protocol into separate
   build and measurement invocations, plus bounded lifecycle-boundary process
   observations. It owns the host capability and actual release outcome.
3. `tic-1dda` consumes both current capabilities with the closed lock-block and
   snapshot diagnostics, then runs its unchanged P0 matrix. It implements none
   of the prerequisite mechanisms.

The build lease covers its own source capture, build and artifact publication.
The measurement lease starts before preflight/source capture and covers server,
client, cleanup and immutable evidence publication. No lease spans idle time
between the separate commands. A matching sealed build record and runtime-byte
validation establish artifact identity across that gap. Participating builds
and measurements use the same exclusion domain, preventing a cooperative build
from modifying a measured runtime. Direct Gradle invocations and other
nonparticipants remain subject only to bounded observation and the stated
trust limit. No continuous measured-phase sampler is added.

A diagnostic can report workload completion without promotion eligibility.
The canonical validator must distinguish recorded facts from required
guarantees, and `tps-p4`/P0 must reject absent provenance or host capability.
Do not emit a synthetic acquired/released lease or infer ownership from empty
ledgers. Replace superseded live receipt/consumer behavior together; historical
artifacts remain evidence of their own revisions, not a compatibility path.

## Scope and validation

Documentation and dependency reconciliation only. Do not implement a build,
lease, sampler, receipt schema, database change or workload here. Independent
architecture/operations review must accept the ownership and trust boundary.
Validate ticket dependencies, ensure no cyclic prerequisite or new parallel
owner of a technical responsibility, and retain the existing P0 criteria.
No new performance claim, baseline workload or checkpoint tag is required for
this documentation delivery.

## Review evidence

Independent architecture/operations review (`review_read_durability_scope`,
2026-09-07) accepted the documentation and dependency graph with no blockers.
The review checked both new tickets, the current synthetic-owner/empty-ledger
wiring, invocation lifetimes, endpoint trust limits, one shared receipt owner,
superseded-schema rejection, and unchanged P0 workload/statistical/correctness
criteria. `tk validate` and `git diff --check` pass. This delivery provides no
implemented provenance/exclusion guarantee or performance claim.
