---
id: tic-288d
status: open
type: bug
assignee: blater
parent: tic-5db4
delivery: code
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
