---
id: tic-288d
status: in_progress
type: bug
assignee: blater
parent: tic-5db4
delivery: code
base-commit: 56ccd9d317e135a9f06cd2ec022bba2160c43a71
branch: ticket/tic-288d-group-commit-fencing
tags:
    - p0
    - transactions
    - recovery
    - durability
    - correctness
created: 2026-09-05T00:11:39.206611Z
---
# Restore group-commit fencing after durable write and force faults

Clean pre-ticket source 9f756561f79d1ad0952c0ff4d38c07f670badd31 fails both IndexedGroupCommitFaultTest grouped-facade file-write and file-force cases: cohort commits return IO_FAILURE and remain unpublished, but subsequent store admission returns OK instead of FENCED.

## Design

Trace the one canonical group-commit terminal-failure path from injected durable-directory write or force failure through coordinator completion and IndexedTableStore admission. Restore fencing without adding a second commit, fallback, or diagnostic control path. Keep failure cleanup and fault-fixture resource ownership bounded.

## Acceptance Criteria

Both groupedFacadeCommitFailureDoesNotPublishAndFencesAdmission parameter cases return IO_FAILURE to accepted members, publish no rows or commit sequence, leave outcomes indeterminate, clear active transactions and locks, fence store admission, and reject a new session with FENCED. Focused class and affected river-engine tests pass without OOM. Record clean-baseline XML SHA-256 6c89fb7238a7cbc0314b4079f63fb4656d0a2a8350f1c3098fd9b715a2d9ed64 and independently reviewed candidate evidence.

## Notes

### 2026-09-05T00:11:39Z

Discovered after the `tic-50e8` non-fail-fast module discriminator exposed
tests beyond the known savepoint failure. A focused rerun in the clean detached
`9f756561f79d1ad0952c0ff4d38c07f670badd31` worktree executed both parameter
cases with two failures and no skip or OOM. Both failures were at the existing
fencing assertion with expected `FENCED` and actual `OK`; the XML SHA-256 is
`6c89fb7238a7cbc0314b4079f63fb4656d0a2a8350f1c3098fd9b715a2d9ed64`.

### 2026-09-05T01:16:00Z

Static tracing showed that the canonical durable-failure owner already fences
the live WAL and store before transaction outcomes, locks, and request
completion become observable. `IndexedHybridCommitGroup.append` and `force`
mark the store failed when durability is uncertain, and synchronized group
cancellation reasserts that fence before `IndexedGroupCommitBatch.failGroup`
terminalizes the cohort. The regression was the transaction-admission adapter:
`IndexedTableStore.transactionAdmissionStatus()` consulted only durable-version
pressure and bypassed the store's existing full admission status.

The candidate composes full store admission first and delegates to durable
version admission only while the store remains healthy. The existing
parameterized test now also proves direct store fencing and zero active and
waiting locks before attempting a new session. The exact two-case gate passed
2 tests with no failure, error, or skip; XML SHA-256
`58006b613a1cde4317b0788c1467f9f32a0b573cb64ed644e2f25ce5d3a4ad26`.
The full `IndexedGroupCommitFaultTest` then passed 5 tests with no failure,
error, or skip; XML SHA-256
`adf21c0b079be9db767561f61cc660db9d8febac68573d917e259cfb8f0cd2ae`.

The fence remains deliberately live-instance state. Reopen constructs a fresh
store and publishes it only after WAL recovery and flush succeed, so recovery
continues to own durable-suffix resolution rather than persisting a permanent
admission fence. Compact slopmark changed only `IndexedTableStore`, from
`156.284` to `156.331`; coordinator `71.3503`, hybrid group `42.6842`, batch
`31.2329`, and table facade `19.3068` were unchanged. The ticket remains
`in_progress` pending the joint affected-engine integration gate and independent
review.
