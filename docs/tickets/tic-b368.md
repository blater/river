---
id: tic-b368
status: closed
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
base-commit: 2a5e3ed
branch: ticket/tic-b368-durability-reconciliation
delivered-commit: 98baa4d336d84926b611bf530a061fb02b56e4bc
tags:
    - performance
    - tpcc
    - p1
    - wal
    - correctness
deps:
    - tic-f539
    - tic-f8dd
    - tic-e544
links:
    - tic-1dda
    - tic-32b3
created: 2026-09-04T15:10:07.080647Z
---
# Reconcile force-prefix ownership and safe commit overlap

Specify the remaining ownership and execution changes required to process a
successor cohort while an earlier WAL force is outstanding. Preserve current
pre-force visibility, lock handoff, observed-read dependencies and synchronous
acknowledgement. Do not recreate those already-delivered behaviors.

## Outcome and scope

One reviewed source/state-machine and failure matrix identifies the serial
architecture checkpoint (`tic-7352`) and the atomic overlap implementation
(`tic-f1bb`). Existing transaction, WAL, publication and budget owners retain
semantic authority. This ticket delivers evidence only.

The canonical reconciliation is
[2026-09-07 durability reconciliation](../delivery/evidence/2026-09-07-tic-b368-durability-reconciliation.md).
It supersedes this ticket's 2026-09-04 post-force implementation assumptions.
The outcome mapping remains owned by [tic-e5ff](tic-e5ff.md).

## Non-goals

No production, tests, build, benchmark or runtime changes; no batching delay,
lock-policy change, workload collapse, weaker durability or new WAL format.
No duplicate commit queue, physical writer, outcome model or dependency policy.
A force-I/O handoff is an explicit architecture decision within the WAL owner,
not permission to create a second transaction executor.

## Acceptance and stop conditions

Independent concurrency/recovery review accepts the current-source trace,
force-target identity, retained ownership, state transitions and failure matrix.
Every missing prerequisite names an owner. Authorize only the delivery whose
contract is complete; do not infer dynamic overlap safety or a TPS gain from
serial tests or the optimistic timing model.

Source/design reconciliation consumes accepted f539/f8dd/e544 evidence; it may
proceed before P0 workload revalidation. Both production outcomes retain their
explicit `tic-1dda` gate. The new architecture story has no independent TPS gain
requirement; repeated unexplained regression blocks acceptance. Overlap must
prove its declared mechanism and repeatable workload benefit.

## Delivery

Accepted evidence merged and pushed at `98baa4d`; source behavior is unchanged.
This closes the design reconciliation, not the architecture or overlap code gates.
