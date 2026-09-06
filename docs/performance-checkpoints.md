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

### 2026-09-06 prepared-lock handoff experiment

Status: user-requested integration of prepared-lock handoff; no demonstrated
throughput improvement and not an accepted performance checkpoint.

- Branch: `ticket/tic-f1bb-durability-handoff`
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
