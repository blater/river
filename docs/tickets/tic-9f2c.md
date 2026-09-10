---
id: tic-9f2c
status: closed
type: story
priority: 1
assignee: blater
delivery: code
base-commit: 84e31c80147051ae2f567f40878a46e2b5f25c0d
branch: ticket/tic-9f2c-wal-atomic-sync
created: 2026-09-10
---
# Publish WAL commit groups with one durability sync

User-directed follow-up to tic-6a91. Replace the separately synchronized logical
end slots with append-only, validated commit-group framing. Acknowledgement follows
one durability barrier for the complete group. Atomicity means recovery accepts a
complete group or none of it; it does not assume arbitrary-size atomic disk writes.

Owner: lead integrator; worktree `/private/tmp/river-wal-atomic-sync`.

## Acceptance

- Normal commit appends records and their group boundary in one WAL stream and
  performs one provider force before publishing the captured durable frontier.
- Recovery admits only complete validated groups in order. Cover partial writes,
  missing/torn markers, corruption, stale bytes after tail repair/reuse, mapping
  boundaries, force failure and acknowledged-commit restart.
- Keep forced readers, quorum byte comparison and recovery replay on one framing
  contract. Remove the old end-slot code, tests and current-design documentation;
  no compatibility path for unreleased WAL formats.
- Reuse record encoding and checksums, bounded buffers and existing force-target
  ownership. No new executor, durability mode, benchmark changes or speculative
  framework. Mapping growth/eviction and bootstrap barriers remain explicit.
- Independent recovery review, focused and affected tests, clean full check,
  actual server crash/restart, slopmark and paired before/after TPS samples pass.

## Evidence and decision

Baseline source is the pushed `perf-checkpoint-20260910-mapped-wal` tag.
One-worker elapsed diagnostics in `/private/tmp/insert-elapsed-20260910` measured
about two River msync calls per commit. This story removes the second publication
barrier; it does not also optimize dirty ranges or claim filesystem-independent
power-loss behavior beyond River's platform durability contract.

Implementation uses WAL header v3 and an 80-byte footer per forced group. It
removes the end-slot implementation completely. A read result distinguishes
record coverage from the next-record offset, so page references exclude the
footer while replay skips it. Existing databases need a fresh data directory.

Independent recovery review accepted the final-group crash policy, captured
force ownership, digest chain, decisionless suffix repair, error propagation,
quorum comparison and record-offset migration. Slopmark's LocalWal increase
from 148.722 to 159.756 was reviewed: the methods belong to the existing WAL
owner; the separate framing state is in LocalWalCommitGroup. No additional
commit path or unrelated responsibility was introduced.

Clean full check and O3/PGO native build passed. Actual native SIGKILL/restart
preserved all 100 acknowledged rows and values; public stop passed. The INSERT
syscall probe confirms one msync per commit. Matched TPS samples were 211.4/202.9
before and 206.6/212.3 after, all passing with zero retries/errors. No TPS gain
is claimed. Commands and artifact paths are recorded in
[`performance-checkpoints.md`](../performance-checkpoints.md).
Checkpoint: `perf-checkpoint-20260910-atomic-wal-sync`.
