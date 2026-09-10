---
id: tic-7a32
status: in_progress
type: story
priority: 1
delivery: code
created: 2026-09-10
owner: root
branch: ticket/tic-7a32-shared-preparation
base-commit: a4567c01b73db452e5ceb0ecda448ca390231284
worktree: /private/tmp/river-shared-preparation
---
# Share repeated statement preparation within a session

The current four-worker full-mix profile attributes 16.9% of request/commit CPU
samples to PREPARE. The external harness retains connection-bound Go statements;
Go's transaction binding prepares those statements again. River currently parses,
captures and validates an identical template for every independent handle.
See the latest [benchmark investigation](../benchmark-log.md).

## Scope

Share exact-SQL immutable prepared templates between live handles in one session.
Keep handle close and transaction-program references independent. Charge retained
SQL, template and lookup storage to the existing budget; release shared ownership
on final close and all ownership on session cleanup. Preserve authorization,
current catalog and private DDL visibility, active-query exclusion, and existing
execution-time schema validation. No process-wide cache, cold template retention,
new setting, workload change, transaction change or weaker durability/isolation.

## Acceptance

Focused tests demonstrate one compilation for repeated eligible preparation,
independent handles and program references, final-close/reopen, bounded pressure
and cleanup, and correct DDL/authorization behavior. Independent review checks
ownership and visibility. Run affected tests, slopmark before/after and a clean
full check. Capture two matched external full-mix JVM controls and candidates;
repeat the profile to establish which preparation work disappeared. Keep profiling
separate from throughput measurements and investigate repeated regressions.
Deliver through merge, annotated performance checkpoint and push, with evidence
in the performance ledger and current local runnable artifacts.
