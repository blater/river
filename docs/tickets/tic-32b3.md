---
id: tic-32b3
status: closed
type: story
assignee: blater
parent: tic-30c3
delivery: documentation
base-commit: 5b120a179055a9ec1c640152b4e2bf057d23f5ac
branch: ticket/tic-32b3-reconcile-performance-workflow
delivered-commit: 0e6fcd90e92b5e7a1235baf519a191c43068b8df
tags:
    - performance
    - workflow
    - recovery
    - evidence
links:
    - tic-da4e
    - tic-b368
    - tic-f1bb
created: 2026-09-04T18:13:27.657788Z
---
# Reconcile performance workflow and recovery carry-over

Reconcile AGENTS.md with the current harness and future riverd boundary, correct the recovery checkpoint ledger to match master, and classify post-snapshot billion-row-capacity changes without importing unproved code.

## Design

Use origin/master and the dirty worktree only as read-only evidence. Preserve current diagnostic commands with an explicit removal condition, keep riverd and the independent comparator as promotion boundaries, and accept carry-over only when a measured mechanism, exact source delta, test gap, and owning ticket are named.

## Acceptance Criteria

AGENTS.md has one non-contradictory current/future performance loop and restores feature-checkpoint rules; the ledger distinguishes recoverable source from accepted performance checkpoints; a carry-over review identifies already-integrated, candidate, deferred, and rejected work with no speculative implementation claim; ticket and link validation pass.
