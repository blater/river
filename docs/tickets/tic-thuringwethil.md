---
id: tic-thuringwethil
status: in_progress
type: story
priority: 2
assignee: blater
delivery: code
base-commit: 3636c7b3e62717a9854d6e1b2e0548bd9152d8f1
branch: ticket/tic-thuringwethil-localwal-complexity
created: 2026-09-16T09:56:53.601056Z
---
# Reduce code complexity below Slopmark and NPATH limits

User-directed behavior-preserving simplification: every code file Slopmark below 100 and every routine NPATH at most 100. Start with LocalWal; deliver cohesive reviewed slices without metric suppression or allocation regressions.

## Acceptance Criteria

Complete Slopmark scan satisfies requested limits; affected tests pass; durable and concurrency changes independently reviewed.

## Notes

### 2026-09-16T09:57:29Z

Owner: Codex integrator with Luna/high implementation and review. Base: 3636c7b3. Worktree: /private/tmp/river-localwal-complexity. Baseline: /private/tmp/river-slopmark-baseline.json (2180 production sources, 16 scores >=100, 195 NPATH routines >100 in 177 files). First slice: LocalWal.
