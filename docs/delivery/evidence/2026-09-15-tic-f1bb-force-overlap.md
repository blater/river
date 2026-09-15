# tic-f1bb WAL force-overlap delivery evidence

Date: 2026-09-15  
Stable base: `b28f33db95c74464cd0311d7fbd668aa49b23adf`
(`perf-checkpoint-20260915-force-contract`)  
Feature branch: `ticket/tic-f1bb-force-overlap`

## Decision

Accept the bounded force-overlap mechanism. Deterministic tests prove that the
single commit writer can append and publish same-page successor cohorts while a
captured prefix force is held. The six longer interleaved main-workload samples
showed candidate benefit in two of three matched pairs amid substantial control
variation, while aggregate queue occupancy fell in all three pairs. Single-worker
and four-warehouse controls found no repeated regression. This is local diagnostic
evidence, not a general or statistically significant throughput claim.

## Delivered contract

One database-local `LocalWalForceWorker` owns a bounded command/result slot and
performs only the provider range force. The existing commit writer remains the
only transaction and publication owner. After visibility publication and lock
handoff, it seals a cohort, transfers its exact page-generation chain to a bounded
FIFO `IndexedDurabilityCohortRing`, submits a captured WAL prefix, and reuses its
append/preparation scratch while that prefix force is outstanding. The writer
alone consumes force results, advances local durability, performs configured
quorum work, releases covered cohorts and publishes outcomes.

`physicalCohortsWhileForceActive` is a focused telemetry/test counter for work
retained while a force target is outstanding. An outstanding target may already
have a ready result, so the counter alone does not prove simultaneous provider
I/O. The held-force state, CSN and page-pin assertions provide that proof.

The implementation preserves these boundaries:

- a force target captures the file identity, start/end offsets, record count,
  CSN and predecessor/final digests for one sealed suffix;
- successful local force advances local durable truth before any later quorum
  failure, while acknowledgement still requires the post-force admission and
  quorum checks;
- prefix completion releases only cohorts whose required WAL end is covered;
  global force failure fences and terminalizes every pending cohort through its
  own page-chain token;
- publication installs the earliest pending-durability barrier before exposing
  rows or handing off locks;
- footer write uncertainty fences at the WAL owner, and no-write validation
  failures preserve the existing prefix;
- direct commit drains the same pipeline, and maintenance/rotation retains the
  existing active-transaction and lock guards; no alternate writer, executor,
  fallback or outcome state machine was added.

## Acceptance matrix

| Clause | Production evidence | Focused evidence | Disposition |
| --- | --- | --- | --- |
| Successor work during force; same-page generations | `LocalWalForceCoordinator`, `LocalWalForceWorker`, `IndexedGroupCommitCoordinator`, `IndexedDurabilityCohortRing`, `IndexedPageFrameCache` | `IndexedGroupCommitForceOverlapTest` holds A's provider force while B/C publish distinct generations of the same initial page, then checks FIFO completion and exact pins | Passed |
| Prefix isolation and failure cleanup | Captured exact prefix validation in `LocalWalForceTarget`; token-scoped release in the coordinator; seal failure cleanup in `IndexedHybridCommitGroup` | `LocalWalForceTargetTest`, `DurableWalQuorumTest`, `IndexedGroupCommitFaultTest`, `EmbeddedCatalogDurabilityOverlapTest` cover partial/thrown footer writes, gap and zero-CRC targets, pre/post-force fencing, quorum failure, cancellation, close/wakeup, recovery, locks and pin cleanup | Passed |
| Budget and bounded ownership | `DatabaseCommitPipelineRetainedLayout` charges `5*A(T,8) + 3*A(T,4) + 112 + 4096` before coordinator creation; `DatabasePageCacheRetainedLayout` charges frame owner/link scalars; ring admission checks capacity/token before append/publication | `DatabaseCommitPipelineRetainedLayoutTest`, `RelationalDatabaseResourceAdmissionTest`, `IndexedPreparedPageBatchTest` cover exact admission edges and all-frame transfer prevalidation | Passed |
| Allocation/copies | Cohort metadata uses preallocated primitive arrays; append, force-target and result carriers are reused; mapped duplicate views remain confined to the force worker and pinned generation | Quorum allocation check passes the unchanged 1,024-byte measured limit after increasing warmup only; 40 iterations initially measured 1,056 bytes and an adjacent 400-iteration measurement fell to 56 bytes, identifying fixed warmup overhead rather than a steady per-operation excess | Passed |
| Workload mechanism and outcome | Held-force tests establish actual physical overlap. Runtime `QUEUE_RESIDENCE` is recorded when path attribution occurs after group preflight, so it is submission-through-preflight time, not pure enqueue-to-selection delay. Queue nonempty time is aggregate occupancy, not a per-request wait. | Six 30-second interleaved samples and eight bounded regression controls below; every valid sample passed invariants, reconciliation, performance capture and cleanup | Passed with stated metric limits |
| Review and gates | Provider mapping lifetime remains in `NioDurableFile`/`NioMappedWindow`; commit/WAL lifecycle remains in existing owners | Independent concurrency/recovery review: `/private/tmp/river-tic-f1bb-review.md`; provider evidence: `/private/tmp/river-tic-f1bb-provider-evidence.md`; clean full gate 2,000 executed tests | Passed |

## Validation

Focused WAL validation executed 15 cases in `LocalWalForceTargetTest` and seven
cases in `DurableWalQuorumTest`. `IndexedGroupCommitFaultTest` executed 20 cases with zero
failures, errors or skips after migration from the retired synchronous batch
seams to the real coordinator and held provider force. The focused engine set
also covered `IndexedGroupCommitForceOverlapTest`, publication, relational WAL,
session and prepared-page ownership. Provider validation executed 13 focused
mapped-file cases, then 39 platform cases with 17 platform-dependent skips and
zero failures or errors.

The final clean command was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f1bb \
  ./gradlew --no-daemon \
  --project-cache-dir /private/tmp/river-project-cache-tic-f1bb \
  --no-build-cache clean test --continue
```

It used GraalVM 25.0.4 and passed in 2m57s across 116 tasks:
430 suites, 2,019 cases, 2,000 executed, 19 skipped, zero failures and zero
errors. The provider component and final WAL/engine slice received independent
review; no known production correctness blocker remains.

A serial geometry-6 old-snapshot/tiny-cache flush control returns the same
post-commit `CORRUPTION` on exact stable `b28f33db` and candidate code. The source,
XML, logs and exact commands are retained under
`/private/tmp/river-tic-f1bb-evidence/tiny-cache-control/`. This proves the small
cache/history flush limitation predates force overlap; it is recorded as an
out-of-scope limitation and is not used as feature evidence.

The user-directed P0 scaling and warmup-accounting deferral recorded by the
ticket remains in force. This delivery neither runs nor claims that campaign,
and it does not use this local checkpoint to certify the deferred P0 gate.

## Workload evidence

Every valid sample pinned GraalVM 25.0.4 and used tiny/standard, serializable,
no-wait stress, seed 42, batch rows 32, maximum 32 attempts, fresh load, a
2-second warmup, diagnostic evidence, disabled retained deadlock diagnostics,
no persisted-write tracing and the `load-run` path that skips SQL CHECKPOINT.
The main samples used four terminals and one warehouse. Substitute the sample
label, duration and output path:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  RIVER_JAVA=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --version=tic-f1bb-SAMPLE --profile=tiny --mix=standard \
  --terminals=4 --warehouses=1 --scheduling=no-wait-stress \
  --evidence=diagnostic --fresh-load=true --batch-rows=32 \
  --maximum-attempts=32 --warmup-seconds=2 --measured-seconds=DURATION \
  --seed=42 --isolation=serializable --deadlock-diagnostics-bytes=0 \
  --sample-id=SAMPLE \
  --output-dir=/private/tmp/river-tic-f1bb-evidence/SAMPLE-live
```

Pre-edit 10-second controls were **847.0/846.3 TPS**. Initial candidates were
**787.7/831.1 TPS**; the lower direction triggered the declared longer
interleaving. The 30-second order was control, candidate, candidate, control,
control, candidate:

| Sample | TPS | Retries | New-order p95 upper | Submission-through-preflight mean | Blocked-lock mean | Queue nonempty total | Forces / cohorts |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| control-1 | 915.100 | 3 | 8.388 ms | 175.802 us | 2,177.416 us | 149.789 ms | 25,157 / 25,157 |
| candidate-1 | 898.267 | 3 | 16.777 ms | 178.107 us | 2,199.336 us | 98.712 ms | 24,670 / 24,684 |
| candidate-2 | 902.400 | 1 | 16.777 ms | 177.049 us | 2,189.352 us | 98.464 ms | 24,789 / 24,801 |
| control-2 | 812.367 | 1 | 16.777 ms | 196.835 us | 2,430.423 us | 161.116 ms | 22,297 / 22,297 |
| control-3 | 840.333 | 1 | 16.777 ms | 194.062 us | 2,356.957 us | 154.137 ms | 23,059 / 23,059 |
| candidate-3 | 893.033 | 2 | 16.777 ms | 177.831 us | 2,211.846 us | 100.289 ms | 24,516 / 24,526 |

Candidate TPS moved **-1.84%, +11.08%, +6.27%** in matched execution-order
pairs. Candidate values were tightly grouped while controls varied widely; the
median comparison is 840.333 versus 898.267 TPS, but no stable 6.9% gain is
claimed. Submission-through-preflight and blocked-lock means improved in the
same latter two pairs and were slightly worse in the first. Queue nonempty time
fell in every pair. Queue depth was at most two, with only 14-24 multi-depth
selections per sample; dividing aggregate occupancy by enqueues gives an
approximate 5.95/7.22/6.68 us for controls and 4.00/3.97/4.09 us for candidates.
That quotient is descriptive occupancy at this shallow depth, not a request
latency. Candidate force savings were only 10-14 across roughly 24,000-25,000
cohorts, so batching is not offered as the explanation.

Payment, order-status, delivery and stock-level p95 buckets were identical in
all six samples: 8.388, 4.194, 16.777 and 16.777 ms respectively. The single
lower new-order bucket occurred only in control-1. Every retry was an exactly
reconciled deadlock; all samples had zero failed or unknown outcomes and zero
retained transactions, locks or waiters.

Required 10-second regression controls used the same settings and C,K,K,C order:

| Control | Control 1 | Candidate 1 | Candidate 2 | Control 2 | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Single worker, T1/W1 | 788.1 | 760.9 | 754.1 | 727.8 | Reverse-order control drift spans both candidates; no repeated candidate loss; no retries/errors |
| Low contention, T4/W4 | 882.8 | 798.0 | 775.5 | 768.9 | Reverse-order control drift spans both candidates; no repeated candidate loss; one reconciled candidate deadlock, no errors |

All eight controls passed invariants, performance capture and terminal cleanup.

## Slopmark and retained evidence

Final versus baseline scores for the principal changed owners were: `LocalWal`
159.675 -> 176.082, `IndexedGroupCommitCoordinator` 83.9806 -> 131.935,
`IndexedGroupCommitTelemetry` 100.832 -> 101.761,
`IndexedHybridCommitGroup` 90.5303 -> 99.3986, `IndexedPageFrameCache` 97.4843
-> 98.4859, `IndexedTableStore` 89.7847 -> 92.5106,
`IndexedRelationalWalGroupAppender` 71.4171 -> 82.8563, and
`EmbeddedDatabaseOpener` 72.6785 -> 74.4822. Review accepted the increases as
the force-target, ordered-cohort, page-generation and admission responsibilities
of their existing owners. No benchmark semantics, duplicate executor, duplicate
force path or formatting-driven control flow entered production.
Provider scores were `NioDurableFile` 42.98 -> 77.691 and `NioMappedWindow`
21.0395 -> 10.3519; review accepted the former as the bounded mapped-generation
lifetime and force responsibility of the existing file owner.

Workload artifacts and slopmark snapshots are under
`/private/tmp/river-tic-f1bb-evidence/`. The stable executable remains in
`/private/tmp/river-tic-f1bb-stable`; candidate artifacts remain in the feature
worktree. Provider review evidence is
`/private/tmp/river-tic-f1bb-provider-evidence.md`; the independent final review
is `/private/tmp/river-tic-f1bb-review.md`.
