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

### 2026-09-04 restored P1 prototype baseline

Status: final clean gate and integration identifiers pending.

Purpose: preserve the restored no-argument TPS behavior and the current P1
prototype as the baseline for subsequent isolated optimization features.

Pre-checkpoint investigation evidence is recorded in `docs/perf_review.md` under
“2026-09-04 apparent TPS regression investigation.” Restored no-argument
`tools/tps-test.sh` samples after a clean `:river-bench:classes` build were 124.4
and 123.9 committed TPS. After replacing the unsafe shell timestamp/sentinel
freshness test with Gradle-owned incremental checking, matched samples were 125.1
and 124.5 TPS. All four samples had zero retries and errors and valid complete
captures. These adjacent results exonerate that tooling hardening as a throughput
regression; they are diagnostic local evidence, not an external performance
claim.

The clean gate exposed 80,896 bytes of warmed commit-path allocation across 64
single-row transactions (1,264 bytes per transaction). Stage isolation placed
all of it in page-frame acquisition. The cache selected an empty slot before a
safe historical generation later in the probe ring, so a serial workload
allocated one direct frame per commit until reaching cache capacity. The
candidate now scans the ring for a reclaimable historical generation before
using the first empty or evictable slot, and retains each frame's payload view
for reuse. This changes neither the configured cache geometry nor its structural
bounds. The warmed allocation guard and group-commit fault test pass, and a
direct cache-policy regression test covers the selection order.

The accompanying test-value pass removes two suites for superseded internal
contracts (`EmbeddedRiverLegacyCompatibilityTest` and
`DatabaseResourceDefaultsTest`), one tautological resource-default assertion,
one assertion pinning a SQL test to the superseded legacy dispatch classifier,
and one JVM-layout-dependent allocation threshold on the necessarily cold
structural split. The SQL behavior and split test still require their semantic
result, exact staged-page, and WAL copy behavior. Recovery, concurrency, WAL,
protocol, fault-injection, security, durable-format, invariant, and meaningful
warmed allocation coverage remain.

The first `./gradlew clean test` attempt after this slice was disrupted by the
concurrent authentication/server migration: River-owned callers temporarily
referenced removed client/server entry points and dependency verification did
not yet admit the new server-app TLS artifacts. Those boundary failures are not
accepted as evidence for or against this engine change. The isolated engine
gate, a settled clean gate, and post-clean TPS samples remain pending.
