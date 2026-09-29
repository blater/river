# Erebor accepted integration checkpoint

Date: 2026-09-29 UTC. Ticket: `tic-erebor`.

## Approval and integrated source

The owner confirmed that independent review passes, Erebor is ready for
promotion and no further code changes are requested. The accepted feature tip
is `04cd0917262e2694b6ebd5ffcf82a34a4fea8e0e`. The integration merge is
`fdfda8316dbc821b00fcf2de6f4244c331bbc9c8`, with first parent stable
`2ada6350ae420944ce51072e3223164c9f697091`. Its tree exactly matches the
reviewed feature tip. No production or test source changed during promotion.
The annotated checkpoint is
`perf-checkpoint-20260929-tic-erebor-clustered-row-store`.

The [retained independent follow-up](2026-09-29-tic-erebor-followup-review.md)
accepts F1/F2 at `5cfefeeb` / source `4e08034d`, with all 53 independent tests
passing and the earlier durable-format/recovery/concurrency reviews included.
Its remaining three-retry condition was subsequently satisfied by the
[versioned common-binding correction](2026-09-29-tic-erebor-three-retry-mix.md).
The owner's latest approval confirms promotion of the resulting feature tip.
The copied review is unchanged from `/private/tmp/erebor-promotion-review-3/review.md`,
SHA-256 `ab044a7340b1015c8f6b6193058674a7a14fe9fe7670b5c0e9e4fd1ec0d228fa`.
Its old pending-workload statement retains the review's original scope/date.

## Final checkpoint validation

The fresh serial clean checkpoint on merge `fdfda831` passed in 5m 35s,
completing at 2026-09-29 23:02:43 UTC (2026-09-30 00:02:43 BST).
There were 156 actionable tasks: 125 executed and 31 up to date. All 1,137
engine tests passed with zero failures, errors or skips. Across the repository,
2,128 tests were reported: 2,109 passed, zero failed/errored and 19 skipped.
Those existing conditions are 11 Linux filesystem tests, five Windows
filesystem tests, one opt-in native-trampoline test and two opt-in TPC-C
lifecycle workloads. The installed-server workload evidence covers the
accepted diagnostic runs separately.

Command, from the integration worktree:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-erebor-integration \
  /Users/blater/src/river/.river-gradle/wrapper/dists/gradle-9.7.0-bin/d4tj7w02tcgubx9zk9hbippn6/gradle-9.7.0/bin/gradle \
  --no-daemon --offline \
  --project-cache-dir /private/tmp/river-project-cache-erebor-integration \
  --max-workers=1 clean check :river-server-app:jar
```

Retained log: `/private/tmp/erebor-promotion-final/clean-check-fdfda831-cached.log`,
SHA-256 `01e9b62fd0f1a88207e8acba12cdff84017d33c1b2270595324171fb98bdd564`.
Per-module counts are in `/private/tmp/erebor-promotion-final/clean-counts.json`;
the copied XML suites are below `/private/tmp/erebor-promotion-final/test-results`.
Closure edits after the tested merge affect documentation only.

The integration worktree is `/private/tmp/river-erebor-integration`, branch
`integration/tic-erebor-20260929`, with its own Gradle user home and project
cache. A short repository integration lease covers the tested merge and push.
All build and workload work is serial. The initial offline attempt could not
resolve dependencies because the read-only cache path used the wrong layout.
The private integration cache was then populated from the accepted feature's
dependency cache; the successful check uses that ordinary private cache. This
was build setup, with no source correction.

The reviewed harness correction `356682a` is locally integrated on `main` at
`9c0be772c2a7ab1382dd7f8c0621bee1b71b1152`. The rebuilt normal executable has
SHA-256 `e3ced16fb594d5162773f617bcda4fe426a4a3020a56da206c15691c6c2d0627`,
identical to the tested artifact. That repository has no remote configured;
its established local-delivery boundary is retained. The original unrelated
untracked files remain intact.

## Measurement decision and rollback

The owner's accepted [Stock Level measurements](2026-09-29-tic-erebor-candidate-stock-level.md)
and [physical identity/write-cost evidence](2026-09-29-tic-erebor-write-cost.md)
retain their named source versions, host variation and attribution limits.
The three-retry candidate/control pair passes invariants with zero retries,
failed or unknown outcomes, equal eligible comparison keys and complete owned
cleanup. No new performance campaign or baseline designation is needed for
this approved promotion. The Baseline stats table is unchanged; these are
diagnostic workload results with no cross-database speedup claim.

The canonical clustered row store replaces the descriptor head/location/heap
path. Direct primary and secondary routing, mapping maintenance, page-history
ownership, bounded cross-owner reclamation, cancelled commit handling and exact
logical replay are delivered under their reviewed contracts. Control major 4,
allocation root version 5, overflow version 2 and logical WAL version 9 are
retained. Earlier formats are rejected before mutation; this checkpoint does
not introduce an old-format migration path.

River's feature branch, integration branch, master update and annotated tag are
published together. The closure documentation records the immutable delivered
merge. The user's existing dirty main checkout is preserved. To roll back code
on the shared branch, revert merge `fdfda831` with first parent 1; reproduce the
accepted source from the annotated tag. Database-format recreation/rejection
continues to follow [ADR 0015](../../adr/0015-clustered-relational-row-store.md).
