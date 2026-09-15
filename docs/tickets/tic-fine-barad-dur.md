---
id: tic-fine-barad-dur
status: in_progress
type: feature
priority: 2
assignee: blater
delivery: code
base-commit: eede02521803a148abf09a8df8155c33e8d9affe
branch: ticket/tic-fine-barad-dur-read-validation
tags:
    - performance
    - format
links:
    - tic-gothmog
created: 2026-09-15T20:32:42.958815Z
---
# Replace repeated read validation and page payload checksums

Deliver the user-directed descriptor admission cleanup, header-only page CRC, checksum-free tuple-root records, bulk heap CRC helper, and required live/replay registry cleanup ordering fix. Retain structural and identity checks; document the changed integrity contract. Comparison findings are in tic-gothmog.

## Acceptance Criteria

Independent review; focused corruption/buffer/rollback/recovery tests; clean full check; documented diagnostic measurements without a claimed isolated speedup; merge commit, annotated checkpoint tag and push.

## Notes

### 2026-09-15T20:37:25Z

Delivery validation: clean `./gradlew --no-daemon clean check` passed in the isolated feature worktree (3m10s): 2,027 tests reported, zero failures/errors, 19 platform/opt-in skips. Root source/module/dependency/invocation policies passed. Log: /private/tmp/river-read-validation-clean-check.log. Earlier 1,300 affected-module tests and final rollback/checkpoint/WAL replay checks also passed.

Independent descriptor review and strategy_adversary durable/recovery review accepted the implementation. The final comparison is accepted diagnostic evidence; it does not establish an isolated speedup. Page/root v4 reject previous formats and deliberately reduce payload-integrity detection. Binary routing and sorted stock acquisition remain recommendations only.

This delivery includes descriptor admission cleanup, page-header-only CRC, removal of repeated tuple-root CRC, bulk heap CRC, required live/replay registry allocation ordering, controlled lock-order coverage, and the reviewed comparison evidence. It excludes unrelated backlog/checkpoint investigation edits. Integration checkpoint: perf-checkpoint-20260915-read-validation.

