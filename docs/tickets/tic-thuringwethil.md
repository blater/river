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

### Decimal arithmetic slice — 2026-09-16

Independent Luna/high review approved the unsigned carry/high-product equivalence,
scale-reduction rounding bounds, and unchanged scratch publication behavior.
`ExactDecimal128WidePower.multiply` selects all limbs once (NPATH 274 → 36);
`ExactDecimalQuantize.apply` removes unreachable quotient-overflow branches
(132 → 42); `ExactDecimal128WideProduct.multiply` shares unsigned carry calculation
(128 → 1) and uses the JDK unsigned high-product primitive.

Validation: `:river-base:check` passed 111 tests with no failures, errors, or skips.
The 625-case limb-boundary test compares the full product with BigInteger.
Module scan including tests: 114 files, maximum score 87.7803, maximum NPATH 75.
Artifacts: `/private/tmp/river-decimal-check.log` and
`/private/tmp/river-decimal-final-slopmark.json`.
This is a structural refactor; no throughput improvement is claimed. The overall
ticket remains open. Subsequent WAL/query slices receive occasional workload checks.

### Transaction value API slice — 2026-09-16

Directory growth and memory charging now belong to the existing
TransactionValueArenaSizing owner. The borrowed CharSequence view has its own
package-private implementation, retaining its arena-owned reuse lifetime.
Comparison operand validation is named alongside the existing numeric rule.
Independent Luna/high review approved the behavior and ownership boundaries.
`:river-engine-api:check` passed 32 tests, no failures/errors/skips. Full module
scan including tests: 51 files, maximum Slopmark 96.0974 and NPATH 54.
Evidence: `/private/tmp/river-value-api-check.log` and
`/private/tmp/river-value-api-final-slopmark.json`. No performance claim.

### Protocol codec slice — 2026-09-16

Six codec routines now separate request validation, payload sizing, and frame
header/body emission. The query-open path groups row-layout checks and header
emission rather than retaining trivial conditional wrappers. Wire layouts,
status precedence, buffer ownership, and hot-path allocation remain unchanged;
independent Luna/high review approved the final revision.
`:river-protocol:check` passed 62 tests with no failures/errors/skips, including
an integrated rerun against the accepted value API checkpoint. Full module scan
including tests: 88 files, maximum Slopmark 38.6733 and NPATH 96.
Evidence: `/private/tmp/river-protocol-check-integrated.log` and
`/private/tmp/river-protocol-final-slopmark.json`. No performance claim.

### Offline backup and inspection slice — 2026-09-16

Separated control/manifest reads and verified payload copying from their file
cleanup lifecycle. Directory transfer and page inspection stop on the first
failure before later I/O. Existing close order, failure precedence, and result
publication remain unchanged; independent Luna/high review approved these paths.
`:river-backup:check :river-inspect:check` passed all five integration tests,
with no failures/errors/skips. Scan including tests: 14 files, maximum score
62.3839 and NPATH 65. Evidence: `/private/tmp/river-backup-inspect-check.log`
and `/private/tmp/river-offline-final-slopmark.json`. No performance claim.

### Durable format validation slice — 2026-09-16

Simplified build-intent key validation, vacuum progress validation, and indexed
page root classification. Existing ranges, state constraints, short-circuit
ordering, and format/status behavior remain unchanged. Independent Luna/high
review and integrator review approved the predicates.
`:river-format:check` passed 83 tests, no failures/errors/skips. Full module
scan including tests: 108 files, maximum score 45.1567 and NPATH 92.
Evidence: `/private/tmp/river-format-check.log` and
`/private/tmp/river-format-final-slopmark.json`. No performance claim.

### WAL ownership slice — accepted checkpoint, 2026-09-16

LocalWal delegates to cohesive append, recovery, force, lifecycle, reservation,
stream, and quorum owners. Removed superseded forwarding methods, duplicate
recovery state, and unused extraction remnants. LocalWal score 176.0825 → 96.2091;
quorum admission 117.4298 → 22.9301; record-batch append NPATH 335 → 67.
The entire WAL module including tests has maximum score 96.2091 and NPATH 89.

Two independent Luna/high reviews covered append/recovery and force/lifecycle/
streams. Review caught and corrected failure fencing, startup recovery-window
lifetime, and a repeated payload-size query. Four focused regression tests now
cover these boundaries. Engine fault-injection fixtures target the new file owner.

Clean full check passed 2034 tests, zero failures/errors, 19 skips (3m4s).
Final removal of unused methods/state passed another WAL module check (4s).
Logs: `/private/tmp/river-wal-clean-check-final.log`,
`/private/tmp/river-wal-dead-code-check.log`, and
`/private/tmp/river-wal-final-slopmark.json`.
Four interleaved control/candidate performance samples passed with no regression
signal: candidate throughput, server CPU per commit, and p99 all fall within
the two controls' ranges. Independent review agrees; no speedup is claimed.
Configuration and individual results are in `docs/performance-checkpoints.md`;
captures are under `/private/tmp/river-complexity-perf/`.
Promote with `perf-checkpoint-20260916-localwal-complexity`; ticket remains open.

### Tuple B-tree structure slice — 2026-09-16

Split admission/output and leaf mutation preparation now have coherent local
owners; cursor admission retains its pin/release and retry ordering. Independent
Luna/high review approved page boundaries, status propagation, proofs, and
allocation behavior. Existing child routing is unchanged.
`:river-storage:check` passed; full module scan including tests covers 76 files,
maximum score 41.4655 and NPATH 100. Evidence:
`/private/tmp/river-btree-check.log` and
`/private/tmp/river-btree-final-slopmark.json`. No performance claim.

### Client/server lifecycle slice — 2026-09-16

Separated server-query close exchange from local completion, and named session
state admission before request decoding. Integrator review confirmed unchanged
status precedence and cleanup. Both affected module checks passed. Scan including
tests: 51 files, maximum score 91.7783 and NPATH 48. Evidence:
`/private/tmp/river-session-check.log` and
`/private/tmp/river-session-final-slopmark.json`. No performance claim.

### JDBC savepoint ownership slice — 2026-09-16

Savepoint capacity, identity allocation, lookup, and invalidation now belong to
one connection-owned registry. Admission and SQL publication order are unchanged.
Independent Luna/high review approved the lifecycle; `:river-jdbc:check` passed.
Full module scan including tests: maximum score 97.2898 and NPATH 32. Evidence:
`/private/tmp/river-jdbc-check.log` and
`/private/tmp/river-jdbc-final-slopmark.json`. No performance claim.

### Hybrid logical/WAL sizing slice — 2026-09-16

Separated logical shape/payload accounting from ordered WAL chunk packing into
a reusable owner. Independent Luna/high review approved emission order, overflow,
and reset behavior; no per-measure allocation is introduced. Focused WAL commit,
recovery, and group tests passed, followed by the affected engine module check.
An initial full-module run failed the read-only API allocation assertion at
1576 bytes versus 512. Adjacent accepted-code and candidate runs both passed
unchanged, then full engine validation passed; the initial failure is retained.
Evidence: `/private/tmp/river-logical-sizing-test.log`,
`/private/tmp/river-logical-sizing-check.log`,
`/private/tmp/river-sizing-allocation-control.log`,
`/private/tmp/river-sizing-allocation-candidate.log`,
`/private/tmp/river-logical-sizing-check-final.log`, and
`/private/tmp/river-logical-sizing-after.json`. No performance claim.

### SQL parser and query-state slice — 2026-09-16

Thirteen files separate parsing, command-copy, and materialization phases.
Integrator review restored missing-BY error precedence, keyword consumption
order, and unconditional whitespace handling. Subquery comparison remains local
across right-expression parsing. All 89 SQL module tests passed; full scan
including tests: 159 files, maximum score 96.9714 and NPATH 96. Evidence:
`/private/tmp/river-sql-check-final.log` and
`/private/tmp/river-sql-final-slopmark.json`. No performance claim.

### Commit queue and durability ownership slice — 2026-09-16

The coordinator delegates intrusive queue selection and published-cohort durability
completion to their own local owners. Admission, rejection, and parking predicates
have explicit phases under the original monitor boundaries. Independent Luna/high
review approved wakeup behavior, active-count failures, force ordering/fencing,
and selection telemetry. Coordinator score 131.93 → 95.15; all three files
are below 100, maximum NPATH 76.
Focused fault/force-overlap tests passed. Full engine check exposed a race in
DeferredSessionCleanupTest: the fake incremented its attempt before reading its
result, allowing the test thread to change RETRY to OK on the first attempt.
The fake now captures its result before publishing the attempt; production cleanup
is unchanged. Focused cleanup tests and the complete 1044-test engine module
check then passed. Logs: `/private/tmp/river-commit-queue-test.log`,
`/private/tmp/river-commit-queue-check.log`,
`/private/tmp/river-deferred-test-ordering.log`,
`/private/tmp/river-commit-queue-check-final.log`; metrics:
`/private/tmp/river-commit-queue-after.json`. No performance claim.
