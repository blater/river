# River performance checkpoints

This ledger records stable feature points for performance-sensitive work. It
does not turn short local samples into performance claims. Its purpose is to
make regressions visible, attribution reviewable, and rollback exact.

## Acceptance workflow

1. Identify the prior pushed `perf-checkpoint-*` tag and capture matched control
   samples before production changes.
2. Implement one coherent mechanism with focused correctness and failure-path
   tests. Record any slopmark architecture trigger and resulting refactor.
3. With no other build or workload active, run a clean full test build.
4. Capture at least two matched candidate samples. If status, phase, retries,
   errors, latency, or TPS shifts repeatedly beyond adjacent run variation,
   collect longer interleaved control/candidate evidence and mechanism telemetry.
5. Record the evidence and decision below. Merge with `--no-ff`, annotate the
   integration commit with a `perf-checkpoint-*` tag, and push branch, integration
   branch, and tag.

Use `git revert -m 1 <merge-commit>` to undo an accepted feature on a shared
integration branch. Do not rewrite the shared branch. Checking out the tag is
appropriate for reproduction and bisection, not for erasing later history.

## Entry template

```text
Checkpoint:
Purpose:
Feature commit:
Integration commit:
Tag:
Slopmark before/after:
Clean gate command/result:
Workload command and fixed configuration:
Control samples:
Candidate samples:
Correctness/phase/retry/error evidence:
Artifact paths or identifiers:
Decision and attribution:
```

## Checkpoints

### 2026-09-06 reclaimed page-frame handoff (`tic-2828`)

Status: promoted and pushed at the user's request after independent
review, engine tests, and the clean full test build passed. The user explicitly
requested commit, push, and promotion after the existing repository policy
failures were reported. This accepts those recorded gate limitations for this
delivery; it does not establish a throughput improvement or a green policy gate.

- Base: pushed `1ce3c802c636cca9c6551f4fbb98d4ecebe6a153`, tagged
  `perf-checkpoint-20260906-batched-lock-release`.
- Branch: `ticket/tic-2828-page-generation-reuse-followup`.
- Feature commit: `7b87a0d`.
- Integration commit: `f7ff998aa15145eee55eca3b1053851da83c06dd`.
- Checkpoint tag: `perf-checkpoint-20260906-page-generation-reuse`.
- Evidence root: `/private/tmp/river-tic-2828.XBvoMp`.
- Mechanism: retain the frames cleared by preflight reclamation in an intrusive
  cache-owned free chain and consume them before circular probing. The chain
  borrows the previous-version link only while a frame is empty; selection
  clears the link before admission. Existing visibility, pin, reservation,
  version-splicing, and eviction policy remain authoritative. No extra scan,
  per-operation allocation, copy, queue capacity, or payload-view change.
- Baseline allocation test: warmed single-row inserts allocated 80,896 bytes
  against the existing 512-byte ceiling (`baseline-allocation.log`). Both
  unchanged allocation tests pass after the fix (`focused-allocation.xml`).
  Five small-geometry tests cover production preflight order, multiple retained
  frames, pin/snapshot protection, prepared publication, and pressure recovery
  (`focused-reuse.xml`); the existing eviction tests also pass.

Matched diagnostic command, serialized with all builds and other workloads:

```sh
./make.sh
tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-tic-2828.XBvoMp/<sample>
```

Fixed defaults: tiny standard mix, serializable isolation, one warehouse,
ten terminals, no-wait-stress scheduling, 32 maximum attempts, default resource
budgets, OpenJDK launcher 26.0.2.1. These are engineering TPS, not tpmC.

| Sample | Committed TPS | Retries/errors | Result |
| --- | ---: | --- | --- |
| `baseline-1` | 121.600 | 0 / 0 | Successful capture and terminal receipt |
| `baseline-2` | 121.300 | 0 / 0 | Successful capture and terminal receipt |
| `candidate-1` | 118.700 | 0 / 0 | Repeated short-run drop triggered investigation |
| `candidate-2` | 114.700 | 0 / 0 | Repeated short-run drop triggered investigation |
| `control-long-1` | 129.400 | 1 / 0 | Reconciled order-status deadlock retry |
| `candidate-long-1` | 132.167 | 0 / 0 | Successful capture and terminal receipt |
| `control-long-2` | 124.967 | 0 / 0 | Successful capture and terminal receipt |
| `candidate-long-2` | 133.933 | 0 / 0 | Successful capture and terminal receipt |

The four longer runs used `--warmup-seconds=5 --measured-seconds=30`, alternating
control/candidate in the order shown with all other settings fixed. Only the
cache source differed; `interleave.sh` rebuilt each variant and restored the
candidate afterward. Every sample completed with zero errors, successful
capture, deadlock reconciliation, and a successful terminal receipt. The longer
interleaved evidence did not reproduce the short-run regression; it does not
establish a throughput claim. Mean preflight reclamation fell from
119.255–127.666 microseconds/event in the longer controls to 30.909–32.993 in
the candidates. Mean group preflight fell from 476.667–493.737 to
373.135–382.089 microseconds/event. WAL force remained variable at
3.427–3.682 milliseconds/event across those four samples. Exact values and
source-linked raw metrics are retained in `mechanism-summary.txt` and each
sample directory.

Validation: all 981 engine tests executed successfully in
`engine-and-policy.log`; `verifyIndexedTableClassReferences` passed.
`./gradlew clean test --no-fail-fast --continue` then passed in
`clean-test.log`, with 1,762 reported tests, zero failures/errors and two skipped
benchmark tests (`clean-test-summary.txt`). Gradle reused valid cached results,
including the just-executed engine suite. The separate `verifySourcePolicy`
and `verifyHotPathBytecode` checks still fail on unchanged source-format and
test-support findings, stale/missing method entries, and the existing engine
API exception instruction. No finding names either touched Java file; no
policy allowlist or allocation threshold was changed. Those failures prevent
reporting a fully green repository policy gate.

Independent correctness review found no blocker in reclamation ownership,
duplicate prevention, generation links, pins/reservations, failure cleanup,
or detach. Slopmark triggered a separate design review: the touched cache's
score rose from 76.6761 to 87.1949; cognitive maximum/count stayed 18/96 and
NPath maximum/count stayed 54/96, while cyclomatic total/maximum changed from
341/14 to 342/15. The reviewer recommended retaining the small addition in its
existing owner: extracting it would add indirection without simplifying
ownership. Both isolated scores and full baseline ranking are retained in the
evidence root. This is a reviewed trigger, not a waived correctness gate.

Promotion: the feature branch, `master`, and annotated checkpoint tag were
pushed atomically. The exact integration commit passed a post-merge smoke with
`--seed=42 --warmup-seconds=1 --measured-seconds=3` after `./make.sh`: zero
retries/errors, successful pre/post invariants, checkpoint, capture, and terminal
receipt (`promotion-smoke`). This shorter smoke is a correctness check and is
excluded from the matched performance samples. `tk validate` passed, and
`tic-2828` was closed against the pushed integration commit and checkpoint tag.

### 2026-09-06 batched terminal lock-release scheduling

Status: promotion requested from `perf/batched-lock-release`, currently held
for remaining storage allocation failures after test-source repairs.
Performance remains inconclusive.

- Control: handoff integration `1d94901`. Short controls used `f557af1`, whose
  production/test source is identical to that integration.
- Candidate source: `fa1a910`.
- Planned promotion tag: `perf-checkpoint-20260906-batched-lock-release`;
  not created while the clean test gate remains red.
- Evidence root: `/private/tmp/river-batched-lock-release.U3GReO`.
- Scope: two production files, `LockExactLifecycle` and `LockExactScheduler`.
  Defer scheduler draining across terminal request cancellation and holding
  release; reuse the existing deduplicated resource worklist and drain before
  transaction recycling/return. Final drain remains included in lock-release
  timing. No grant/fairness, lock-count, WAL, engine, protocol, or harness change.
- Independent concurrency review approved resource lifetime, cancellation,
  conversion, and nested deadlock-drain behavior. Two focused tests extend the
  existing lock-table tests; no new production queue or allocation is introduced.
- Slopmark: both touched production files scored 0 before and after. Rankings
  are `slopmark-before.txt` and `slopmark-after.txt`; no file crossed 80.

Each source switch was followed by `./make.sh`, with no overlapping build or
workload. Commands retained the prior entry's fixed tiny/serializable/default
configuration and seed 42:

```sh
tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-batched-lock-release.U3GReO/<short-sample>
tools/tps-test.sh --seed=42 --warmup-seconds=3 --measured-seconds=30 \
  --output-dir=/private/tmp/river-batched-lock-release.U3GReO/<long-sample>
```

| Sample, in execution order | TPS | Lock release microseconds / captured write |
| --- | ---: | ---: |
| `baseline-1` | 120.200 | 442.4 |
| `baseline-2` | 121.100 | 444.6 |
| `candidate-1` | 123.500 | 433.4 |
| `candidate-2` | 123.400 | 438.2 |
| `control-long-1` | 129.867 | 408.5 |
| `candidate-long-1` | 128.767 | 408.9 |
| `control-long-2` | 126.333 | 404.6 |
| `candidate-long-2` | 130.900 | 412.3 |

All eight samples passed invariants and complete capture, with zero measured
retries/errors and zero terminal transactions, lock holdings, and waiters.
The short-run increase triggered longer interleaving. The longer pairs have
mixed TPS direction and no consistent improvement in normalized lock-release
cost. Do not attribute a throughput gain to this change from these results.

Validation: the new overlap test initially used a mismatched keyspace, corrected
before candidate measurements. `affected-tests-repeat.log` records all 138
transaction tests: 137 passed, with only the known warmed lock-allocation
assertion failing (6,832 bytes, also observed before this feature). All 51
focused engine handoff/fault, relational WAL, and embedded-program tests passed.
The unchanged allocation test passed in isolation (`allocation-repeat.log`).
The full transaction invocation is still reported as failed, not waived by
the isolated pass. The baseline full-build and policy failures are recorded
in the handoff entry below; no green clean full-build checkpoint was claimed
at the initial measurement point.

Promotion checkpoint: `./gradlew clean test`, with no competing build or
workload, reproduced the existing CLI, backup, server, and client test-source
compilation failures against unchanged APIs. The log is
`promotion-clean-test.log` under the evidence root.

The user then explicitly requested all test compilation errors be fixed before
promotion. Five existing test classes now provide explicit resource requests
matching the engine test profile, and server test SQL frames carry the current
diagnostic fields. The server malformed-UTF-8 case now corrupts the SQL text
at its current payload offset; the continuation cleanup test waits for socket
acceptance before testing closure and slot reuse. The SQL savepoint test now
expects successful fourth-savepoint admission under the dynamically growing
store, preserving its rollback assertions. No production compatibility API,
allocation threshold, or build configuration changed. Independent review
approved these repairs. All test sources compiled (`test-compilation-fixes.log`)
and the repaired module tests passed (`test-repairs-focused.log`, followed by
`server-test-repairs.log` for the accept/close race correction).

The full checkpoint `./gradlew clean test --no-fail-fast --continue` compiled
all test sources and completed with only the engine task failing: 976 engine
tests, three failures (`repaired-clean-test.log`). One was the deliberately
corrupt tree fixture attempting a clean detach while dirty; it now explicitly
abandons its disposable pages, preserving the cycle-detection assertions.
Both tree-structure tests pass in `remaining-engine-test-failures.log`.
The two remaining `IndexedTableAllocationTest` failures reproduce in isolation:
80,896 bytes for warmed inserts and 20,224 bytes for wide-row inserts, against
unchanged 512-byte limits (72,704 and 18,176 in the full run). These match the
documented `tic-2828` page-generation reuse problem: current-frame selection
can choose unused slots before reusable retired generations. Addressing that
requires a storage implementation change, not another test-compilation repair.
No allocation limit was raised, no test disabled, and no production storage
change was made in this test-repair slice.

Decision: after receiving the results and validation limitations, the user
explicitly requested commit/merge/promotion. Integrate with a merge commit,
annotated source-checkpoint tag, and push the feature, master, and tag. This
is an explicit exception to the normal performance-promotion gate, not a
reinterpretation of the measurements or a waiver of the recorded failures.
The subsequent request for a clean test build holds that promotion pending
resolution of the storage allocation failures. No batching merge, tag, or push
has been performed.

### 2026-09-06 prepared-lock handoff experiment

Status: user-requested integration of prepared-lock handoff; no demonstrated
throughput improvement and not an accepted performance checkpoint.

- Branch: `ticket/tic-f1bb-durability-handoff`
- Feature source commit: `f557af1`.
- Control: `1b9bf8f` (no existing `perf-checkpoint-*` tag was available).
- Evidence root: `/private/tmp/river-durability-handoff.Ufwv2v`
- Scope: publish the irrevocable prepared group and hand off its locks before
  the existing writer forces WAL. Keep outcomes pending and pages pinned until
  force; preserve the active-transaction budget and fence dependent results on
  failure. No benchmark, provenance, build, or lock-grant-policy changes.
- Durability waits cover rows, absence/end-of-scan, and read-only completion.
  Program intermediates remain inside program execution until commit. The
  refined candidate waits for the maximum observed snapshot/current-row/commit
  sequence, rather than every unrelated newest group.

Short diagnostic command, run serially after `./make.sh`:

```sh
tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-durability-handoff.Ufwv2v/<sample>
```

Defaults held fixed: tiny profile, standard mix, serializable isolation,
one warehouse, ten terminals, no-wait-stress scheduling, 32 maximum attempts,
and default resource budgets. Launcher: OpenJDK 26.0.2.1. Each sample directory
contains the existing tool's configuration, server metrics, acceptance artifact,
and source fingerprint. These are engineering TPS, not tpmC.

| Sample | Committed TPS | Retries/errors | Assessment |
| --- | ---: | --- | --- |
| `baseline-1` | 121.600 | 0 / 0 | Control |
| `baseline-2` | 124.400 | 0 / 0 | Control |
| `candidate-1` | 123.400 | 0 / 0 | Global result barrier; no demonstrated TPS gain |
| `candidate-2` | 123.000 | 0 / 0 | Same candidate; no demonstrated TPS gain |
| `candidate-profile` | 121.500 | 0 / 0 | JFR diagnostic; excluded from the paired comparison |
| `candidate-v2-1` | 103.000 | 0 / 0 | Observed-sequence barrier; slower force service in this sample |
| `candidate-v2-2` | 121.000 | 0 / 0 | Same refined candidate; no demonstrated TPS gain |

All short samples reported complete captures, successful invariants, and zero
terminal transactions, holdings, and waiters. In `baseline-2`, captured lock
blocking was 84.144 seconds and WAL force was 4.144 seconds across 1,124 cohorts.
In `candidate-1`, these were 78.127 seconds and 3.906 seconds across 1,115 cohorts.
Average cohorts still rounded to 1.0. Lower blocking alone did not establish an
end-to-end throughput improvement. `candidate-v1.patch` preserves that first
implementation; the JFR diagnostic is `candidate-profile.jfr`.

Focused publication, transaction, failure/recovery, handoff, and reader-pin
checks passed during editing. The independent concurrency review required the
absence-result barrier and confirmed pending admission ownership, page pins,
snapshot/current-read dependencies, and sidecar recovery ordering. The clean
full test attempt failed in the unchanged warmed lock-allocation test (6,832
bytes against its existing 512-byte allowance); its isolated repeat passed.
The wider follow-up also exposed unrelated CLI, backup, client, and server test
compilation errors against unchanged APIs. Logs are retained at the evidence
root; these failures are not waived or repaired by this performance slice.

Slopmark: no touched production file crossed 80. Existing high scores changed
from 159.532 to 159.851 (`TransactionManager`), 156.331 to 156.656
(`IndexedTableStore`), and 283.617 to 283.768 (`SqlSessionExecutionCoordinator`).
The last retains only a durability delegate; result-delivery policy stays in
the SQL facade. `IndexedGroupCommitBatch` decreased from 31.233 to 30.358.
Full rankings are `slopmark-before.txt`, `slopmark-after.txt`, and
`slopmark-final.txt`. Program lifetime state belongs to `SqlTransactionState`;
the SQL facade retains only its existing execution-coordinator field.

Decision: after reviewing the flat throughput result, the user explicitly
requested integration into `master`. This overrides the normal inconclusive
performance promotion rule for this change; it does not establish a speedup or
waive the recorded full-build failures. No performance checkpoint tag or push
is part of this integration. The final independent concurrency review approved
the implementation, including program-state ownership and ordinary SQL result
barriers.

Final validation logs: `final-module-tests.log` records a 1,528-byte warmed
engine API allocation assertion; that assertion passed in the module repeat.
Transaction-module tests passed. `final-module-repeat.log` reached 666 engine
tests and stopped at `SqlSessionTest.namedSavepointCoexistsWithStatementRollback`
(fourth savepoint returned OK rather than RESOURCE_EXHAUSTED); its focused
repeat is `final-focused-tests.log`. These are not reported as a green full
engine gate.

The savepoint assertion also failed on unchanged control `1b9bf8f`
(`base-savepoint-test.log`). Final focused handoff, recovery, reader-pin,
transaction-session, SQL ownership, and program validation passed all 121
tests (`final-handoff-tests.log`); indexed-table reference verification passed.
Hot-path bytecode verification failed on stale/missing method entries and an
unchanged engine API exception instruction; source-policy verification also
failed on unrelated existing files. No policy allowlists were edited.

After rebuilding `f557af1`, two final identical short samples are retained at
`/private/tmp/river-batched-lock-release.U3GReO/baseline-1` and `baseline-2`.
These also serve as the separate batched-release feature's before samples.
They recorded 120.200 and 121.100 TPS respectively, zero retries/errors,
successful invariants and capture, and zero terminal transactions/locks/waiters.

Follow-up recommendation, not implemented here: batch grant/deadlock scheduler
draining across terminal lock cleanup in the existing transaction-layer owner.
`LockExactHoldingLifecycle.releaseAll` currently schedules and drains after each
holding while the lock-manager monitor remains held. Reuse the scheduler's
existing deduplicated resource worklist, preserve queued resource lifetimes,
and drain after cleanup without changing grant/fairness rules. In
`candidate-v2-2`, 88,986 holdings were released; holding cleanup accounted for
0.480 seconds of the 0.485-second lock-release stage. This identifies removable
repeated work, not a measured scheduler-only cost or promised TPS gain.

### 2026-09-04 pre-launcher recovery source snapshot

Status: recoverable source snapshot; **not an accepted performance feature
checkpoint**.

- Source commit: `adccf7172e74450cf4518a561b3712c4e8927c0d`
- Recovery branch: `origin/recovery/pre-launcher-auth-cutoff`
- Contained by: `master`
- Performance checkpoint tag: none

Purpose: preserve the coherent pre-launcher/authentication River source that
restored the no-argument TPS path after later workspace changes caused
`RESOURCE_EXHAUSTED` and large throughput regressions. This is the safe source
baseline from which the ticketed P0/P1 work proceeds; it is not proof that a P1
optimization passed the feature-checkpoint workflow above.

The local diagnostic evidence recorded in `docs/perf_review.md` under
“2026-09-04 apparent TPS regression investigation” remains useful but does not
constitute a promotion result. After a clean `:river-bench:classes` build,
no-argument `tools/tps-test.sh` samples were 124.4 and 123.9 committed TPS. After
the script delegated freshness to Gradle's incremental task, adjacent samples
were 125.1 and 124.5 TPS. All four reported zero retries and errors and complete
captures. No immutable artifact paths or source-linked run IDs are recorded
here, so the figures support recovery diagnosis only.

No repository-wide clean test gate completed for this snapshot. A clean attempt
was disrupted by concurrent authentication/server API and TLS dependency-
verification changes. There are no accepted post-clean TPS samples, feature
merge commit, slopmark comparison, or `perf-checkpoint-*` tag. Do not fill those
fields retroactively or treat the recovery commit's name as performance
certification.

The previous version of this entry also described a completed page-frame
allocation optimization. That was factually incorrect for the preserved source:
at this snapshot, `IndexedPageFrame.prepare()` still creates a duplicate and
slice payload view on each preparation. A constructor-owned reusable payload
view exists only in the uncommitted `feature/billion-row-capacity` worktree. The
recorded 80,896 bytes across 64 warmed single-row commits is retained as a
candidate observation, not as proof that the mechanism or its fix was accepted.

Likewise, the test deletions described previously are not an accepted feature:
the deleted tests remain on `master`, and no settled clean gate proved that their
coverage was redundant. The factual carry-over classification is in
[`docs/plans/billion-row-capacity-carryover-review.md`](plans/billion-row-capacity-carryover-review.md).

Decision: retain `adccf71` as an exact recovery boundary and starting source.
The next performance checkpoint must be created prospectively by a ticketed
feature that completes the clean gate, matched samples, evidence recording,
merge, annotated tag, and push requirements. The P0 matrix in `tic-1dda` remains
the immediate performance evidence priority.
