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

## Design

Replace the stale convenience-cap contract with savepoint retention admitted by
an existing or explicitly owned database/session resource budget. Preserve
growable savepoints within that budget, return `RESOURCE_EXHAUSTED` before
mutation at the declared boundary, and reclaim accounting on release, rollback,
commit, abort, and close. Do not reintroduce a fixed low cardinality cap.

## Acceptance Criteria

Focused tests prove more than three named savepoints succeed when budget permits;
deterministic budget exhaustion returns `RESOURCE_EXHAUSTED` without corrupting
transaction state; release, rollback, commit, abort, and close reclaim retained
state; the exact formerly failing `SqlSessionTest` method and affected engine
module suite pass. Record clean-master failure XML SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab` and
accepted candidate evidence.

## Notes

### 2026-09-04T22:01:00Z

Clean-baseline discriminator: the exact focused test failed at
`9f756561f79d1ad0952c0ff4d38c07f670badd31`, expecting
`RESOURCE_EXHAUSTED` when the fourth `SAVEPOINT` returned `OK`. The XML is
retained at
`/private/tmp/river-tic-af29-evidence-20260904/allocation/baseline-9f756561-focused-SqlSessionTest-namedSavepoint.xml`
with SHA-256
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab`.
This was discovered during `tic-af29` affected-module verification;
`tic-af29` source was excluded by exact baseline reproduction.
