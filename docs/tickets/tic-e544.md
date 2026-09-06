---
id: tic-e544
status: closed
type: story
assignee: blater
delivery: code
base-commit: 7bcc11ea4624f3e7276cdb562cc33dd310a27fbd
branch: ticket/tic-e544-read-durability-dependencies
delivered-commit: 23484fca398b9219b7ceba6a6ac06a53da04d044
checkpoint-tag: perf-checkpoint-20260906-read-durability-dependencies
evidence:
    - /private/tmp/river-tic-e544-evidence
tags:
    - performance
    - transactions
    - durability
created: 2026-09-06T13:49:29.590843Z
---
# Bind read durability to observed versions and index roots

Result delivery currently waits on the global snapshot sequence, coupling reads of durable data to unrelated pending commits. Remove that unnecessary dependency while preserving every actual read/write and negative-result durability dependency. The SQL client and workload remain unchanged.

## Design

Scope lock: one session-owned maximum observed durability sequence, populated from existing scalar MVCC version identities and conservative tuple-index root version identities. Cover found/missing/tombstone/range decisions and current/write probes together. Preserve isolation, locks, commit acknowledgment, force failure fencing, and recovery. No client/benchmark changes, batching waits, WAL format, new history/cache, or new commit path. Stop and rescope if existing visibility metadata cannot cover a read path safely. Maximum shape: existing engine visibility/result carriers and session read/write/probe owners, focused failure tests and evidence; no cross-module storage-format redesign.

## Acceptance Criteria

Held-force tests prove durable independent reads complete while dependent row, deletion/absence, range/index and write observations wait and fence on force failure. Exercise the real SQL path. No new hot-path allocation; independent concurrency/recovery review; focused and full engine tests, clean full test checkpoint, matched repeated tps-test baseline/candidate with unchanged configuration, and slopmark before/after. Retain conservative per-index waits explicitly where root granularity requires them.

## Implementation and review

The implementation remains in the existing engine visibility, cursor and session
owners. Scalar reads retain the selected MVCC version sequence even for tombstones;
scans retain skipped tombstone observations. Tuple probes and scans use the MVCC
sequence of the registry row, conservatively retaining a dependency on any visible
mutation within that index. Current-row and write preparation decisions contribute
to the same monotonic session dependency. Session reuse resets it; savepoint rollback
does not. Actual write publication still contributes its own committed sequence.

The independent concurrency/recovery review caught and resolved caller row-buffer
aliasing and finite-keyspace validation in the initial implementation. Version results
now contain metadata only; bytes are retained directly in the caller-owned row buffer.
Held-force SQL tests also exposed the existing fenced read-only commit cleanup gap.
The existing abort path now terminalizes that read-only transaction on FENCED;
CANCELLED remains active and retryable. No SQL, client, protocol, workload, lock,
WAL format or batching policy changed.

Slopmark triggered a stop at visibility 55.454 and session 53.5602. Simplifying
visibility control flow and separating the existing read-only commit body within
its owner brought the final scores to 44.8766 (baseline 44.249) and 38.2417
(baseline 39.1925). Store metadata forwarding scores 157.04 versus 156.656;
all other touched production scores are unchanged. No extra steady-state
allocation or row copy was introduced; a tuple root snapshot owns one reusable
version metadata object.

Public result getter documentation also preserves the existing CSN meaning:
read-only results report their snapshot position, which can include unrelated
pending publications. They certify the observed dependencies, not the whole
snapshot prefix. The independent review checked ADR 0007 and API/JDBC/protocol
consumers; no consumer treats this field as a durable-prefix watermark. No
numeric result semantics or transaction-module implementation changed.

## Measurement evidence

Evidence root: `/private/tmp/river-tic-e544-evidence`.
All TPS runs use `tools/tps-test.sh --seed=42 --warmup-seconds=1
--measured-seconds=10 --output-dir=<artifact>` unless explicitly noted otherwise.
The fixed configuration is tiny/standard, one warehouse, ten terminals, serializable,
no-wait stress, 32 maximum attempts, normal synchronous durability, GraalVM 25.0.4.

Initial control samples `baseline-1` and `baseline-2` recorded 73.4 and 62.0 TPS.
At the user's request, unchanged stable master was rebuilt and measured again:
`baseline-repeat-1` and `baseline-repeat-2` recorded 147.3 and 149.9 TPS. All four
remain retained; the low pair is not a basis for an improvement claim.
Candidate samples `candidate-1` and `candidate-2` recorded 149.6 and 148.5 TPS.
All six have zero retries/errors and successful terminal receipts, invariants,
reconciliation and performance capture. Order Status p95 remains 16.777 ms in
both repeat controls and both candidates. Short results show no throughput or
p95 benefit. Longer validation below establishes a repeated benefit.

Full engine validation passed all 994 tests in 7m26s, including all 19 group fault
cases and the cancellation/interrupt-preservation/retry regression. The SQL allocation test reported 608 bytes against its 512-byte
allowance; unchanged stable master reproduced the identical failure in
`control-allocation.log`. No allocation threshold or policy allowlist was changed.

The complete engine run also passed both SQL allocation tests. The isolated
608-byte result is retained as an intermittent baseline finding, not dismissed
by changing thresholds. Independent source review approves the final propagation,
FENCED cleanup and unchanged read-only result semantics; the added cancellation
test proves the remaining review condition.

Clean full test gate passed all 1,775 tests (zero failures, two skips) in
`clean-tests-repeat.log`. The first clean attempt's sole failure was unchanged
`LockExactAllocationTest` (7,024 bytes); an isolated attempt also failed before
the unchanged clean repeat passed. Both attempts and the failure XML remain
retained. No source or threshold changed between clean attempts. Indexed-table
reference verification passes; source/bytecode policy comparison is recorded
with final evidence below.

`control-policy-check.log` reproduces every source and hot-path bytecode violation
from `policy-and-lock-check.log`; normalized violation sets have no additions or
removals (`policy-comparison.txt`). No allowlist was changed. The public API
changes are documentation only. Production implementation remains engine-only.

## Final acceptance

Implementation commit: `046dd432ab2402ff085932af973d58faf5fc699a`.
Longer commands replace the warmup/measured options with `--warmup-seconds=5
--measured-seconds=30`. Both configurations otherwise remain identical. Execution
order and committed TPS:

| Artifact | Revision | TPS | Order Status p99 upper bound |
| --- | --- | ---: | ---: |
| `control-long-1` | `7bcc11e` | 149.133 | 33.554 ms |
| `candidate-long-1` | `046dd43` | 174.967 | 16.777 ms |
| `control-long-2` | `7bcc11e` | 161.467 | 33.554 ms |
| `candidate-long-2` | `046dd43` | 176.100 | 16.777 ms |
| `control-long-3` | `7bcc11e` | 159.233 | 33.554 ms |

All five are clean, source-stable captures with the same configuration fingerprint,
zero retries/errors, successful invariants/reconciliation, terminal receipts and
performance capture. The last control brackets the candidate because the first
control was lower. Against the two later controls' mean, candidate throughput is
about 9.5% higher; the low initial samples are not used to inflate that figure.
Order Status p95 remains 16.777 ms. Other family tails remain within the observed
control/candidate histogram variation; no repeated unexplained regression emerged.

The final control and second candidate have effectively identical mean shared WAL
force duration (3.502/3.504 ms). Their captures contain 4,270/4,717 write submissions
and 4,244/4,664 forces. Cohorts remain overwhelmingly singletons. This is an observed
local gain from the existing workload, not a batching or cross-database claim.
The held-force tests prove actual independence and dependent-result fencing. Existing
tuple delta staging skips unchanged index keys, so non-key updates do not force
otherwise independent reads to inherit a new tuple-root dependency. Changes within
the same index still conservatively retain that index's registry-row dependency.

Decision: accept the constrained mechanism. Promote with annotated checkpoint
`perf-checkpoint-20260906-read-durability-dependencies`, retaining the original
short samples, interleaved controls, known intermittent allocation failures and
identical baseline policy failures. The clean gate passed 1,775 tests with two
skips. Independent concurrency/recovery review has no remaining blocker. Exact
merged-revision smoke and pushed integration/closure are recorded at delivery.

## Delivery

Merged and pushed as `23484fca398b9219b7ceba6a6ac06a53da04d044`, tagged
`perf-checkpoint-20260906-read-durability-dependencies`. The feature branch,
master and annotated tag were pushed atomically. The exact merged tree matches
the accepted feature and passed its `--warmup-seconds=1 --measured-seconds=3`
TPS smoke with zero retries/errors, successful invariants/reconciliation and a
valid terminal receipt (`merged-smoke`; 141.667 TPS, not a comparative sample).
`merged-build.log` records the successful merged runtime build.
