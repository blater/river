---
id: tic-thuringwethil
status: closed
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

### Periodic performance checkpoint 2 — investigation open

Cumulative runtime `fa6f069a` versus accepted WAL checkpoint `ff6ed6f9` showed
repeated lower throughput and higher p99 in 30-second and 60-second A/B/B/A
samples. All runs used READ_COMMITTED with identical workload, durability, JVM,
and warmup settings; all invariants and outcome accounting passed. The signal
is retained, not dismissed. SQL-only and old-engine probes did not establish a
cause. A four-run sizing-only versus full-engine probe favored the full candidate
throughput in both pairs, providing no support for blaming the coordinator.

Independent Luna/high audit verified artifact hashes, eligibility, accounting,
and cleanup; all 32 owned checkpoint-2 client/server PIDs had exited. Evidence:
`/private/tmp/river-complexity-perf-2`,
`/private/tmp/river-complexity-perf-2-long`,
`/private/tmp/river-complexity-sql-probe`,
`/private/tmp/river-complexity-engine-probe`,
`/private/tmp/river-complexity-prequeue-probe`,
`/private/tmp/river-complexity-fullcandidate-probe`, and
`/private/tmp/river-complexity-prequeue-check`. No performance claim. Next
cumulative diagnostic should inspect mechanism telemetry rather than add more
timing-only variants. Daemon-specific acceptance must use immediate master as
its control and cannot clear this cumulative concern.

### Engine catalog, drop, expression, and telemetry checkpoint — 2026-09-16

Four independently reviewed slices separate pending-drop ownership, catalog
decoding/statistics phases, SQL point/expression execution, and the existing
saturating telemetry sum. No per-row allocation is introduced. Fourteen touched
production files now have maximum score 99.7913 and maximum NPATH 96.
Focused catalog, drop, expression, projection, and telemetry tests passed, then
the complete engine module check passed. Evidence:
`/private/tmp/river-engine-complexity-batch-focused.log`,
`/private/tmp/river-engine-complexity-batch-check.log`, and
`/private/tmp/river-engine-complexity-batch-after.json`. This is a correctness
and maintainability checkpoint; it does not resolve or claim improvement in
the cumulative performance investigation above.

### Transaction diagnostics and blocker traversal checkpoint — 2026-09-16

Snapshot counters, signatures, events, exemplars, and edges now have concrete
owners; cycle capture scratch has one reusable owner. No inheritance ladder or
compatibility facade remains. Engine production/test consumers use the new API.
Independent review checked admission order, lock grant predicates, cycle
traversal, snapshot copy, budget admission, and exemplar stride ownership.

A new acyclic interval-conversion test exposed an existing infinite DFS loop:
interval fairness repeatedly returned its single predecessor without advancing
the active request. Thread evidence is retained at
`/private/tmp/river-tx-complexity-stall-threads.txt`. Minimal fix `8ea3b7a1` clears
the active request after that one-shot edge, matching exact-resource traversal.
The separate regression checks active owner, one FIFO predecessor, termination,
and cleanup. This correctness fix is separate from the refactor commit.

The initial draft had incomplete API migration/imports; validation also caught
an invalid deadlocking test setup. Both were corrected. A second focused test
preserves the distinction between public budget admission and internal
dimension-compatible copying. Complete transaction check: 151 tests passed;
three engine deadlock integration tests passed. The phase-refactored cursor then
passed the complete transaction check again. Full tx scan including tests:
98 files, maximum score 95.2102, NPATH 64. Logs:
`/private/tmp/river-interval-fairness-fix-test.log`,
`/private/tmp/river-tx-complexity-check-final.log`,
`/private/tmp/river-tx-complexity-refactor-check.log`; metrics:
`/private/tmp/river-tx-bench-final-slopmark.json`. No performance claim.

### Benchmark configuration and reporting checkpoint — 2026-09-16

Seven files separate configuration admission, server argument parsing, attempt
completion/failure accounting, and report/manifest validation phases. Integrator
review preserved option defaults, validation precedence, measured/drain boundaries,
and output schema; removed an unnecessary boolean failure carrier. Complete
benchmark check passed: 109 tests, zero failures/errors, two skips, including
TPC-C load/checkpoint/reopen recovery validation. Full module scan including
tests: 152 files, maximum score 78.7607 and NPATH 96. Evidence:
`/private/tmp/river-bench-complexity-check.log` and
`/private/tmp/river-tx-bench-final-slopmark.json`. No performance claim.

### Engine lifecycle, catalog, and descriptor checkpoint — 2026-09-16

Twenty-seven files simplify existing admission, construction, terminal cleanup,
catalog publication, scan, tuple preparation, and test fault-mapping phases.
Early returns replace redundant success guards where no cleanup is skipped.
Correlated tuple array growth uses one branch while retaining atomic publication;
cleanup counts stay local. Manifest authority/version/logical-ID validation has
separate predicates without changing format or checksum policy.

Independent Luna/high reviews approved all three constituent slices. Integrator
removed unnecessary forwarding helpers and avoided repeating tuple key lookup.
Complete engine module check passed 1044 tests with no failures/errors/skips.
Touched files all meet both limits. Evidence:
`/private/tmp/river-engine-boundaries-check.log` and
`/private/tmp/river-engine-boundaries-integrated-after.json`. No performance claim.

### SQL execution phases checkpoint — 2026-09-16

Twenty-seven files separate point dispatch, scan preparation, join binding,
aggregate materialization, sorting/spill, statement preparation, and resource
cleanup phases. Independent Luna/high reviews plus integrator review preserve
status precedence, terminal cleanup, and reusable row ownership. Integrator fixed
two compile omissions and restored subquery bound rejection handling: CONFLICT
must not increment the admitted range-part count. The final range writer keeps
those decisions local and shares only bounds publication.

Initial full check stopped at the allocation test (1528 bytes against 512).
Adjacent unchanged-control and candidate focused allocation checks both passed;
the initial failure remains recorded. No allocation threshold was changed.
Validation logs: `/private/tmp/river-sql-execution-integrated-check.log`,
`/private/tmp/river-sql-execution-integrated-check-final.log`,
`/private/tmp/river-sql-execution-allocation-control.log`, and
`/private/tmp/river-sql-execution-allocation-candidate.log`.
Final independently reviewed source passed the complete engine check: 1044 tests,
no failures/errors/skips. Touched-file maximum score 85.7341, maximum NPATH 99.
Final evidence: `/private/tmp/river-sql-execution-integrated-check-reviewed.log`
and `/private/tmp/river-sql-execution-integrated-after.json`. No performance claim.

### Remaining SQL descriptors and comparison ownership — 2026-09-16

Forty-seven files simplify descriptor admission, update/scan/materialization,
aggregate/join execution, and test diagnostics. Ordered comparison reversal now
belongs to SqlComparison; all equivalent parser/engine switches are removed.
Unsupported predicate admission remains with its existing caller. SQL concurrency
assertions have a concrete diagnostics helper; test assertions and fixture
cleanup are preserved. Sort-key comparison retains single reads and original
cursor advances, without helper-carrier state or extra allocations.

Independent Luna/high reviews approved each constituent slice and the integrator
corrections. All touched files meet both limits: maximum score 74.2456 and
maximum NPATH 100. Metrics: `/private/tmp/river-sql-final-integrated-after.json`.
Complete SQL and engine checks passed: 89 SQL tests and 1044 engine tests,
no failures/errors/skips. Log: `/private/tmp/river-sql-final-integrated-check.log`.
No performance claim; the cumulative diagnostic concern remains open.

### Table mutation, recovery, and fault-test checkpoint — 2026-09-16

Twenty production files simplify cohort preflight, mutation compilation, replay,
scan admission, checkpoint repair, tuple publication, and version-page counting.
Independent Luna/high review plus integrator review checked durable ordering,
status precedence, pin/release, lock/snapshot, and cleanup boundaries. Transient
phase counters remain locals/parameters. A reviewer's proposed cancellation on
admission failure was rejected after checking original source: admission failures
return before group creation, preserving rejection accounting.

Fault/pressure tests share concrete fixture and cleanup owners; assertions and
fault scenarios remain intact. Integration compilation found missing test helper
imports/throws and auxiliary-class warnings, fixed without suppressions. The
26 touched source files meet both limits (maximum score 62.5208, NPATH 99).
Metrics: `/private/tmp/river-table-integrated-after.json`; initial compilation
logs: `/private/tmp/river-table-integrated-check.log` and
`/private/tmp/river-table-integrated-check-final.log`.
The full engine run caught a real draft pressure-split regression: the extracted
member phase returned a split but its caller then cleared it via rejectAll.
Restored the original immediate return on member failure, preserving accepted
prefix/deferred suffix accounting. All group fault and pressure tests then passed
(`/private/tmp/river-table-pressure-correction.log`); failing evidence remains at
`/private/tmp/river-table-integrated-check-reviewed.log`.
Independent review approved the correction; the final complete engine check
passed 1044 tests with no failures/errors/skips. Accepted-source log:
`/private/tmp/river-table-integrated-check-accepted.log`. No performance claim.

### Daemon lifecycle slice — reviewed candidate, 2026-09-16

Fourteen files separate identity recovery, publication, cleanup, and command
routing phases. Integrator review corrected opened-stage cleanup, corruption
precedence, complete restart finally ownership, and CLOSED normalization.
All 75 daemon module tests passed, including a rerun against accepted coordinator
changes. Standalone version smoke passed. Full module including tests: 80 files,
maximum score 85.2311 and NPATH 96. Logs:
`/private/tmp/river-server-app-check.log`,
`/private/tmp/river-server-app-check-final.log`, and
`/private/tmp/river-server-app-final-slopmark.json`.
Final independent lifecycle review approved identity, stage ownership, cleanup,
and failure precedence against the original source. The complete integration check and isolated daemon comparisons are recorded
in the completion section below.
The cumulative adverse samples and component probes remain retained in
`docs/performance-checkpoints.md`; they have not been dismissed or attributed.

### Completion — 2026-09-16

All requested source limits are met across the whole repository, including tests:
2693 code files, maximum Slopmark 99.7913, maximum routine NPATH 100, zero
score or NPATH offenders. LocalWal: 176.0825 to 96.2091. No suppressions,
threshold changes, inheritance ladders, or source exclusions were introduced.
Independent Luna/high authors/reviewers and integrator review covered each slice.

Final clean full check passed: 2036 reported tests, zero failures/errors, 19 skips;
2017 tests passed. Some Gradle task results came from cache. Daemon version smoke
passed (`riverd-v1`, `river-v5`). Evidence:
`/private/tmp/river-complexity-final-all.json`,
`/private/tmp/river-complexity-final-clean-check.log`, and
`/private/tmp/river-complexity-final-test-counts.json`.

Delivered through successive merge commits and annotated, pushed checkpoints,
starting with the LocalWal ownership work and finishing with daemon lifecycle.
The 65 pre-existing unrelated local files remain byte-for-byte unchanged.

Periodic performance checks retained adverse results and triggered component,
profile, and identical-binary controls. Final daemon-only short samples showed
+5.8% average CPU/commit; an identical candidate binary later varied +6.05%
without source changes. The longer plain pair was also adverse; the JFR pair was
much closer. No causal performance conclusion or neutrality claim is justified.
All ten final runs passed correctness, accounting, eligibility, and cleanup;
independent audit confirmed no owned servers/data directories remain.

The code is accepted as behavior-preserving maintainability work, not an
optimization. [tic-voronwe](tic-voronwe.md) stays open for both the earlier
cumulative signal and final daemon-only observations. Full individual samples,
configuration, profile limits, and analysis caveats are in
[performance-checkpoints.md](../performance-checkpoints.md), section
"Complexity completion and periodic performance checkpoint 3". Closing this
complexity ticket does not close or dismiss that investigation.
