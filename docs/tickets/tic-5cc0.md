---
id: tic-5cc0
status: open
type: bug
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - p0
    - sql
    - transactions
    - resources
created: 2026-09-04T22:00:53.349124Z
---
# Restore resource-accounted SQL savepoint admission

Clean master at `9f756561f79d1ad0952c0ff4d38c07f670badd31`
deterministically fails
`SqlSessionTest.namedSavepointCoexistsWithStatementRollback`: the fourth
savepoint now returns `OK` after the arbitrary three-savepoint cap was removed,
while retained savepoint arrays have no configured resource-admission boundary.

## Outcome

Named SQL savepoints grow beyond three only while their retained high-water
storage is admitted by the existing session-shape resource budget; rejection
occurs before relational savepoint state changes, and lifecycle cleanup reuses
or returns the admitted capacity exactly once.

## In Scope / Owning Mechanism

The SQL session's existing session-shape lease owns both savepoint capacity
admission and the reusable retained savepoint representation. All River-owned
savepoint callers use that single path.

## Non-goals

- Add a savepoint-specific quota, a replacement runtime budget, or another
  resource-admission service.
- Restore a fixed savepoint-count cap or tune TPC-C throughput.
- Redesign transaction savepoint semantics, statement rollback, or unrelated
  session-retained structures.

## Stop Conditions

Stop and raise a prerequisite rather than broadening this ticket if the
existing session-shape lease cannot charge every retained savepoint byte, or if
admission cannot be ordered before relational mutation. An unrelated failing
test is evidence for a separate ticket, not additional scope here.

## Maximum Change Shape

One admission/accounting path and one retained savepoint representation may
change, together with their River-owned callers and focused tests. Do not add a
feature flag, compatibility wrapper, alternate quota, or second storage path.

## Design

Replace the stale convenience-cap contract with savepoint retention admitted by
the existing session-shape resource budget. Preserve growable, reusable
high-water storage within that budget and return `RESOURCE_EXHAUSTED` before
relational savepoint mutation at the declared boundary. Release, rollback,
commit, and abort clear logical savepoints and reusable name contents while the
retained capacity remains honestly charged; session close returns its runtime
lease. Do not introduce a second quota system or reintroduce a fixed low
cardinality cap.

## Acceptance Criteria

Focused tests prove more than three named savepoints succeed when budget permits;
deterministic budget exhaustion returns `RESOURCE_EXHAUSTED` without corrupting
transaction state; release, rollback, commit, and abort clear logical state and
reuse admitted high-water capacity without double charging; session close
returns the runtime lease; the exact formerly failing `SqlSessionTest` method
and affected engine module suite pass. Record clean-master failure XML SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4` and
accepted candidate evidence.

## Notes

### 2026-09-04T22:01:00Z

Clean-baseline discriminator: the exact focused test failed at
`9f756561f79d1ad0952c0ff4d38c07f670badd31`, expecting
`RESOURCE_EXHAUSTED` when the fourth `SAVEPOINT` returned `OK`. The XML is
retained at
`/private/tmp/river-tic-af29-evidence-20260904/allocation/baseline-9f756561-focused-SqlSessionTest-namedSavepoint.xml`
with SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4`.
This was discovered during `tic-af29` affected-module verification;
`tic-af29` source was excluded by exact baseline reproduction.
