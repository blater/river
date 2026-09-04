---
id: tic-50e8
status: in_progress
type: bug
assignee: blater
delivery: code
base-commit: 36782a95925b7f29f4d6808080270391362d678a
branch: ticket/tic-50e8-union-baseline
tags:
    - p0
    - sql
    - relational
    - correctness
created: 2026-09-04T23:03:21.406097Z
---
# Restore UNION execution acceptance on clean master

Clean pre-ticket source 9f756561f79d1ad0952c0ff4d38c07f670badd31 fails SqlUnionExecutionTest: existing UNION and UNION ALL cases return INVALID_EXTERNAL_INPUT because the parsed query does not satisfy the set-expression execution contract. The same failures reproduce on tic-5cc0 and are not caused by savepoint resource accounting.

## Design

Diagnose the parser-to-SqlUnionExecution contract and replace the stale side of that contract without weakening assertions. Keep the change within UNION parsing/binding/execution ownership; preserve existing resource-budget behavior and do not introduce a second executor or compatibility path.

## Acceptance Criteria

Focused tests prove simple and parenthesized UNION/UNION ALL set expressions, distinct/all boundaries, ordering/limit, type reconciliation, null and Unicode equality, incompatible-schema rejection, and retained-output accounting. All nine SqlUnionExecutionTest cases and the affected river-engine module suite pass. Record clean-baseline XML SHA-256 68182236ce7d125085d25edcd2b1b6167dcd21dcfd9dcdf4c8c94a260c609083 and accepted candidate evidence.

## Notes

### 2026-09-04T23:03:28Z

Clean-baseline discriminator used /private/tmp/river-af29-baseline-9f75656 with isolated caches. Exact SqlUnionExecutionTest run failed 3, skipped 1, stopped after 5 under fail-fast; expected OK but received INVALID_EXTERNAL_INPUT at lines 42, 60, and 101. XML SHA-256 68182236ce7d125085d25edcd2b1b6167dcd21dcfd9dcdf4c8c94a260c609083. The tic-5cc0 full engine suite ran 749 tests with 7 UNION failures and 1 skip; all savepoint-focused tests passed.

### 2026-09-04T23:25:44Z

Review candidate restores all nine UNION cases through the required
runtime-backed materialized-store path. The exact class passed 9 tests with no
failures, errors, or skips; its focused XML SHA-256 is
`b719dede156b882358366cea11e3724b4584496829195f6cf65074a21df1c2d8`.
The affected `river-engine` run reached 666 tests before fail-fast and stopped
only on the previously recorded `tic-5cc0`
`SqlSessionTest.namedSavepointCoexistsWithStatementRollback` discriminator
(expected `RESOURCE_EXHAUSTED`, received `OK`), with one failure and one skip.
That run's `SqlSessionTest` XML SHA-256 is
`07ae206c55692c4bf455f0d443fe2db21b59c99f581743392cf945b7872fc363`;
the clean exact-method baseline SHA-256 is
`92c4d946d7345afc377ffd1b0eb43120b26e8af1587348ca86fda4bcc32bbab4`.
The candidate remains `in_progress` and cannot claim a green affected-module
gate until combined with `tic-5cc0`. Full commands, scope, and comparison are
recorded in
`docs/delivery/evidence/2026-09-05-tic-50e8-union-execution.md`.
