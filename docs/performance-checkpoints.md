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
