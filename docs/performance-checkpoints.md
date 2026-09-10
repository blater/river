# River performance checkpoints

This ledger records stable feature points for performance-sensitive work. It
does not turn short local samples into performance claims. Its purpose is to
make regressions visible, attribution reviewable, and rollback exact.

## 2026-09-10 — contiguous WAL synchronization (`tic-c7e2`)

Base: `7444f542`, `perf-checkpoint-20260910-atomic-wal-sync`.
Branch: `ticket/tic-c7e2-wal-range-sync`; worktree:
`/private/tmp/river-wal-range-sync`. Fresh baselines used the unchanged main
checkout's built distribution, before candidate builds or workloads.

```sh
RIVER_JAVA=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --terminals=4 --warmup-seconds=2 --measured-seconds=10 \
  --version=tic-c7e2-baseline-N --output-dir=/private/tmp/wal-range-baseline-N
```

Baseline samples: **207.0 / 208.5 TPS**. Both passed with zero retries/errors,
successful reconciliation and performance capture. Artifact directories and
matching `.log` files: `/private/tmp/wal-range-baseline-{1,2}`. These are the
River-specific tiny standard-mix serializable diagnostic workload, one warehouse,
four terminals, GraalVM 25.0.4 on macOS/arm64.

The focused INSERT probe uses one connection, a reused prepared statement,
one explicit commit per row, 30s warmup and 10s measurement. The external
`msync` timer records exact requested byte lengths and elapsed time; it does not
change arguments, return values or durability. This run omits Java method tracing
on both control and candidate; do not compare its throughput with earlier
method-instrumented runs. Baseline: 29,572 measured commits, 29,573 syncs
(including final validation), all requesting **16,777,216 bytes**, with mean
**124.920 us/call** and **124.924 us/measured commit**. All 135,910 rows passed
the final count check. Owned processes and database directories were cleaned up.
Scripts, timer source and immutable baseline output:
`/private/tmp/wal-range-validation/insert-baseline` and its parent directory.
Slopmark baseline: `/private/tmp/wal-range-slopmark-before.txt`.

The interleaved INSERT sequence was control, candidate, control, candidate. Each
run used the same timer, workload and runtime configuration:

| Run directory | Mean sync (us) | Mean requested bytes | Measured commits | msync calls | Inserts/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| insert-baseline | 124.920 | 16,777,216 | 29,572 | 29,573 | 2,956.32 |
| insert-candidate | 24.589 | 9,001 | 50,777 | 50,780 | 5,077.51 |
| insert-control-2 | 113.301 | 16,777,216 | 34,684 | 34,685 | 3,468.24 |
| insert-candidate-2 | 23.844 | 9,002 | 51,658 | 51,660 | 5,165.64 |

All directories are below `/private/tmp/wal-range-validation`. Per-length
counts/timings and raw snapshots are retained in each. Candidate request lengths
ranged from 281 to 17,193 bytes, including mapping-boundary operations. Normal
commits still use one sync; boundary operations can add calls, and the capture
also brackets final row-count validation. All four row-count checks passed and
owned processes/data directories were cleaned up.

The repeated drop in requested range and sync latency supports the intended
mechanism. These local INSERT results are not cross-database throughput claims.

Initial candidate short TPS samples were **186.1 / 176.5**, both passing with
zero errors/retries. These lower results triggered longer interleaved runs and
an adjacent recheck of the original configuration; they were not discarded.

| TPS run suffix | Warmup / measured seconds | TPS | Retries |
| --- | --- | ---: | ---: |
| control-long-1 | 10 / 30 | 253.367 | 0 |
| candidate-long-1 | 10 / 30 | 256.433 | 0 |
| control-long-2 | 10 / 30 | 256.333 | 0 |
| candidate-long-2 | 10 / 30 | 266.567 | 1 |
| control-short-check | 2 / 10 | 211.5 | 0 |
| candidate-short-check | 2 / 10 | 207.0 | 0 |

Commands otherwise match the baseline above. Version labels are `tic-c7e2-`
plus the suffix; artifact directories and matching logs are `/private/tmp/wal-range-`
plus the suffix. Initial candidate artifacts use `candidate-{1,2}`. All runs
passed with zero errors, successful invariants, reconciliation and performance
capture, and zero retained transactions, snapshots or locks. The single retry
was PAYMENT attempt tag 4637, step 6, `DEADLOCK`: one server outcome matched one
client retry, with no retry-accounting overflow or unclassified outcome.

Measured-window WAL force averages in the longer pairs were **335.764 / 327.194
us** for controls and **87.826 / 79.285 us** for candidates. Group publication
averages were **451.775 / 452.808 us** versus **455.091 / 432.870 us**. The broader
publication slowdown seen in the initial short candidates did not persist.
Only `capture_*` counters, including drain, were used for these averages; lifetime
counters include setup and warmup. Summary:
`/private/tmp/wal-range-validation/stage-comparison.json`.

The initial short-run drop did not reproduce in the longer pairs or the adjacent
short recheck. Its specific transient cause is not established. The acceptance
decision rests on the interleaved comparisons and mechanism evidence, not an
assumption about host load or a fixed percentage tolerance. No sustained TPS
regression was observed; no qualified TPS speedup is claimed.

Validation: clean full `check :river-bench:installTps :river-server-app:nativeCompile`
passed with `--no-daemon --no-build-cache`, GraalVM 25.0.4, O3 and the existing
`-PriverPgoProfile=/private/tmp/river-native-final.iprof`; log:
`/private/tmp/wal-range-clean-check.log`. No additional test skips. The actual
native executable committed 100 rows, survived SIGKILL/restart with every value
intact, then passed public stop and readiness cleanup; log:
`/private/tmp/wal-range-validation/native-crash-smoke.log`.

Independent review covered captured range ownership, partial coverage, mapping
eviction, metadata barriers and failure propagation. Slopmark LocalWal
159.756 → 159.675; NioDurableFile 43.287 → 42.98; NioMappedWindow 15.3519 →
21.0395. The window increase was reviewed as one bounded mapping-lifetime
responsibility, with two coverage offsets and a cached mapped buffer, no new
executor, range collection or per-force view allocation. Scores:
`/private/tmp/wal-range-slopmark-before.txt`, `wal-range-slopmark-baseline-focused.txt`
and `wal-range-slopmark-after.txt` in `/private/tmp`.

Decision: accept. Checkpoint: `perf-checkpoint-20260910-wal-range-sync`.

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

## 2026-09-09 — mapped WAL (`tic-6a91`)

Branch: `ticket/tic-6a91-mapped-wal`, based on `da7a8794` (including the completed
local server-discovery fixes). This is a direct, user-requested WAL replacement.
The workload, SQL transaction boundaries, isolation and group-commit policy are
unchanged. WAL format v2 separates logical end from mapped capacity; old WAL
files require a fresh database directory.

Adjacent JVM diagnostic samples on the same Apple M1/macOS host, GraalVM JDK 25:

| Implementation / version suffix | Committed TPS | p99 (ms) | Retries | Run ID suffix |
| --- | ---: | ---: | ---: | --- |
| channel-baseline-1 | 169.71 | 85.07 | 606 | 211655_1b9ab61e |
| channel-baseline-2 | 168.47 | 88.93 | 598 | 211752_527875c1 |
| mapped-candidate-1 | 238.29 | 71.11 | 998 | 213910_783532a8 |
| mapped-candidate-2 | 246.12 | 68.16 | 1014 | 214001_e171026b |

All four report passed, zero failed/unknown outcomes, successful invariants and
four measured-phase cancellations at the deadline. Retries per commit increased
from about 0.119 to 0.139 as throughput increased. These short sequential samples
show a 43% higher mean; they are diagnostic evidence, not a qualified performance
claim or a durability-equivalent comparison with MariaDB.

Command for each sample (substitute the table's suffix):

```sh
~/src/ingres/river-harness/benchmark run river tpcc sample all \
  --river-executable=/private/tmp/river-tpcc-jvm-profile-20260909/river-jvm \
  --river-version=tic-6a91-SUFFIX --warmup=15s --duration=30s \
  --workers=4 --warehouses=1 --seed=42 --max-retries=20
```

Artifacts: `~/src/ingres/river-harness/runs/river_harness_20260909_` plus the run
suffix above; command logs in `/private/tmp/river-mapped-wal`.

The focused prepared INSERT probe commits each row separately. With the same
method tracing, the channel run returned 255.21 inserts/s and the mapped run
2,230.57 inserts/s (30s warmup, 10s measurement). The mean server commit call fell
from about 3.53 ms to 0.218 ms. Mapped force calls averaged 0.071 ms, with two
calls per commit (data then logical-end publication); the old channel force
averaged 3.34 ms. The mapped run verified all 107,772 rows. Traces and scripts:
`/private/tmp/river-commit-trace-20260909/traced` and
`/private/tmp/river-mapped-wal/insert-trace`.

Ordinary mapped commits use `msync(MS_SYNC)` on this JVM instead of the channel
path's `F_FULLFSYNC`. File growth still forces metadata. The measured gain does
not establish equivalent hardware power-loss guarantees. The ordering and
mapping-lifetime changes received an independent recovery review.

Validation: the clean build exposed old header/force-count/physical-size test
assumptions and two existing indentation errors. After those repairs, full
`check :river-bench:installTps` passed; no new skips or test removals. Logs:
`/private/tmp/river-mapped-wal/clean-check.log` and `check-repaired.log`.

Slopmark: LocalWal 147.523 → 148.722; NioDurableDirectory 89.7973 → 91.1306.
Final NioDurableFile 43.287, new NioMappedWindow 15.3519 and LocalWalMappedTail
25.6366. Review found no added unrelated responsibility or duplicate commit path.
Scores: `/private/tmp/river-mapped-wal-slopmark-before.txt` and
`/private/tmp/river-mapped-wal/slopmark-final.txt`.

Native completion: a standalone cursor reproducer isolated the O3 shared-arena
compiler failure to direct exits from `IndexedVacuumRowCursor.next()`'s loop.
A controlled loop exit preserves its statuses and pin lifetimes and passes the
actual clean O3/PGO build, with no optimizer exclusions. The cursor's Slopmark
score remains 25.76. Diagnosis and reproduction:
`/private/tmp/river-mapped-wal/compiler-root-cause.md` and `reproducer/`.

Full `check :river-bench:installTps` passed after the cursor change
(`check-cursor-refactor.log`). The rebuilt native executable committed and
verified 100 rows, survived SIGKILL recovery with all values intact, then passed
public `river stop` and readiness cleanup (`native-wal-smoke-public-stop.log`).

The final River-specific check used `tools/tps-test.sh --terminals=4
--warmup-seconds=2 --measured-seconds=10 --version=tic-6a91-mapped-cursor-final`.
It reported status OK, zero retries/errors, no retained transactions/locks,
and 172.5 TPS. This is a different workload from the external harness and its
TPS is not compared with the samples above. Evidence: `tps-final.log` and
`tps-final/` in the same artifact directory.

Final native harness samples use the earlier native baseline configuration:
5s warmup, 30s measured, sample all, four workers, one warehouse, seed 42,
20 retries. Versions `tic-6a91-mapped-native-final-{1,2}` returned 168.04/167.76
TPS, p99 104.53/108.99 ms, retries 795/778, zero failed/unknown outcomes and
successful invariants. Run IDs `river_harness_20260909_230444_0be985f4` and
`river_harness_20260909_230554_c1c5f1fa`. These remain within the earlier native
173.42/161.00 TPS range: native throughput is unchanged in these samples, but p99 and retries are higher
than the older controls (84.61/88.15 ms and 624/572 retries). Attribution is
unresolved because those native controls are not adjacent. On 2026-09-10 the user approved tag/merge/push with this attribution question
retained for follow-up. Faster commit completion increasing contention is
plausible but unproven. Checkpoint: `perf-checkpoint-20260910-mapped-wal`. Both owned
servers stopped; final `river ps` showed no running servers. The branch contains a
validated implementation with diagnostic performance evidence, not a qualified
cross-runtime or hardware-durability performance claim.

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

### 2026-09-07 separate build and TPS host ownership (`tic-d7c2`)

Accepted for diagnostic evidence correctness; no database or workload change
and no throughput claim. Pushed integration `bb4d88a59cfa5004fbaa5c497f720e48e394e209`
is tagged `perf-checkpoint-20260907-invocation-host-ownership`. The exact merged
make and three-second smoke passed (139.667 TPS, zero retries/errors, passing
invariants/cleanup and qualified receipt); this shorter smoke is not a matched
performance comparison. Candidate: `bcc15a6db7baab4027654eb63bb26b4a9519bb9f`,
from pushed `e470a5d` (the documentation closure of
`perf-checkpoint-20260907-prebuilt-tps-provenance`). Separate make and TPS
invocations acquire one canonical cooperative lease, retain bounded boundary
observations and release before their final completion attestation. Obsolete
monitoring and schemas are replaced. Independent lease/lifecycle review passed;
focused suites passed 45 provenance and seven P4 cases.

`./gradlew --no-daemon clean check --continue` produced 383 suites / 1,805 tests,
zero failures/errors and two existing skips in 8m 5s. Seven small modules reused
cached test results; engine, transaction, WAL, protocol, client/server, JDBC,
benchmark and other affected test tasks executed. The full check remains red
on the four recorded baseline policy gates: dependency ledger, hot bytecode,
source policy and SQL shape (19 against 13). After fixing touched indentation,
the narrow source-policy rerun reports 134 existing findings versus the earlier
146; none remain in touched scripts. No allowlist or acceptance rule was relaxed.

Identical tiny standard, serializable, ten-terminal, one-warehouse, seed-42,
synchronous-WAL samples used one-second warmup and ten-second measurement with
OpenJDK 26.0.2.1: controls **159.300 / 163.900 TPS**; candidates **162.000 /
161.000 TPS**. All four have zero retries/errors and passing invariants, capture,
and cleanup. Both candidate receipts qualify through the shared host validator;
legacy controls do not prove the new host contract. Ordered runtime bytes are
identical after normalizing only the Gradle cache directory prefix. No repeated
regression was observed; these short diagnostics establish no speedup or
statistical equivalence.

The user requires `--no-daemon` for every subsequent Gradle invocation; defaults
and working instructions now match. Candidate builds used the normal Gradle
registry after this task retired its two private idle daemons. The user confirms
AC power. Slopmark cannot score shell/Kotlin, so before/after captures record its
unsupported-language result rather than a fabricated score.

Raw evidence: `/private/tmp/river-tic-d7c2-evidence-20260907`, including
`clean-check.log`, `clean-tests.json`, `source-policy-final.log`,
`frozen-provenance-tests-13.log`, `frozen-p4-tests-14.log`, `samples.json`, and
`runtime-byte-comparison.json`. Failed development fixtures and rejected
`real-smoke-1` remain recorded; the latter exposed the inventory counting its
own capture shell, corrected by direct bounded capture. They are not accepted
performance samples.

This slice exceeded the new review budgets; review-only time was not separately
tracked (about 1h 40m of total slice work had elapsed at the user's check-in).
Future provenance overhead is governed by the **8-minute / 15-minute** limits
in `AGENTS.md`, counting reconciliation and fixture rework and reporting automated
waiting separately. No further broad provenance review is a delivery gate.

### 2026-09-07 prebuilt TPS artifact provenance (`tic-ed12`)

**Accepted required evidence correctness; no TPS speedup claimed.** Measured candidate `bd66b13` (implementation `18ca330`, followed by
the real-build correction) binds each TPS diagnostic to a successful separate
`make.sh` invocation. Integration `737faca5bae4a97b7098c1e2154c903f48230444` is pushed and tagged
`perf-checkpoint-20260907-prebuilt-tps-provenance`. Baseline `04d4c09` is production-identical to
`perf-checkpoint-20260907-retained-snapshot-gauge`. No database Java, workload,
isolation, retries, durability or resource budgets change. Ordered runtime
class/JAR byte manifests are identical after normalizing only checkout roots.

Gradle remains the classpath/input authority. Make records its fixed argv,
source boundaries, selected compiler facts and ordered runtime bytes, publishes
completion last, and cleans up its own build process group on interruption.
TPS verifies and retains the record at lifecycle boundaries without building.
Current tool-v3/terminal-v2 receipts bind the actual publisher and build record;
host ownership is explicitly unsupported. P4 uses the same validator and
rejects promotion until `tic-d7c2`, while ordinary diagnostic success remains
available. These samples do not certify P0 or Alpha3.

Independent source reviews and lead integration review resolved child cleanup,
classpath metadata binding, late-mutation outcomes and symmetric cache-trust
validation. Systems review rejected a common build-ID restriction: unique
build events can produce equivalent runtime bytes. Real make also disproved a
proposed `systemPropertiesArgs.isNotEmpty()` guard: Gradle itself populates
encoding/locale defaults. The final design admits only make's fixed retained
argv and checks external environment/configuration through Gradle, without
copying Gradle defaults. Normal make succeeds; extra make `-D` arguments exit 2;
harmless `GRADLE_OPTS` injection leaves an unsealed record and exits 1.

Frozen shell suites pass **42 provenance boundary groups and 7 P4 groups**,
including stale/missing/reordered bytes, source mutation, failed/interrupted
builds, publication collisions and coherently rehashed false claims. The real
candidate receipts pass canonical diagnostic validation and fail promotion
validation for the stated absent host capability.

Clean `GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f8dd ./gradlew clean check
--continue` restored **1,805 Java test results from Gradle cache**, zero
failures/errors and two existing skips. This is not a fresh Java test execution.
Build-policy, module graph and provenance fixtures passed. The full check is
not green: 146 source-policy findings, 113 hot-path bytecode findings, SQL-shape
ceiling 19 versus 13, and surplus dependency-verification metadata all reproduce
on unchanged `04d4c09`; source differences only shift line numbers. No policy
was relaxed. Make does not attest that the custom dependency ledger passed.
The installed slopmark supports neither shell nor Kotlin, so before/after scans
report no supported files; no numeric score is claimed for this tooling slice.

Two fresh controls and candidates ran serially on user-confirmed AC power,
separately from the earlier battery/throttled period. No further battery checks
were made after the user's request. Fixed command after separate make builds:

```sh
RIVER_JAVA=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-tic-ed12-evidence-20260907/<sample>
```

| Sample | Committed TPS | Retries / errors |
| --- | ---: | ---: |
| control-1 | 160.000 | 0 / 0 |
| control-2 | 161.700 | 1 / 0 |
| candidate-1 | 156.200 | 0 / 0 |
| candidate-2 | 171.700 | 0 / 0 |

All passed pre/post invariants, phase/capture and deadlock reconciliation.
Both candidates ended with zero active transactions, retained snapshots, active
locks and waiting locks at capture. The control retry is one measured Order Status DEADLOCK (attempt 1710,
logical sequence 175, terminal 7, step 2): one server outcome equals one client
retry, with no exhaustion, unclassified outcome or overflow. Candidate directions
are mixed; no repeated directional regression is identified. Absolute throughput
variation is retained and is not attributed to artifact verification.

The exact-merge smoke (1s warmup/3s measured) passed at **142.000 TPS** with
zero retries/errors, passing invariants and all four cleanup gauges zero. This
shorter run verifies integration correctness, not throughput equivalence.

Evidence: `/private/tmp/river-tic-ed12-evidence-20260907`, including individual
receipts/build records, source/runtime manifests, frozen tests, real input
probes, policy control comparison and cached XML results. Build provenance
trusts Gradle declared-input/cache correctness; selected launcher/options facts
are not a full JDK or hermetic compilation proof. Boundary snapshots cannot
prove absence of nonparticipating changes between observations. Host ownership
and the remaining performance gates retain their separate owners.

### 2026-09-07 canonical retained-snapshot gauge (`tic-8e74`)

**Accepted cold observability; no repeated regression identified in the short
diagnostic samples.** Candidate `79f4577` exposes the transaction manager's existing snapshot registry count through the
cold managed-server diagnostics path. The registry remains the sole owner;
there are no new counters, lifecycle mutations, or hot-path operations.
`server_retained_snapshots_at_capture` is independent of lock classification
and performance capture enablement. It reports the capture instant before
server/database close; diagnostics do not clean up snapshots to satisfy it.
Unsupported providers retain the existing unavailable convention (`-1`).

Baseline: `fa77adceeae9e1a0702971d122617b925a53701f`, production-identical to
`perf-checkpoint-20260907-lock-block-causality`. Existing lifecycle tests now
check successful begin/commit/abort, failed source and capacity admission,
prepared abort, public-session cleanup, and deliberately retained snapshots.
The group-fault test proves published-pending transactions are distinct:
active transactions 2 versus retained snapshots 0, then 4 versus 2 after
successor/reader admission, and both zero after complete cleanup.
Independent review approved the code and coverage.

Clean `GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f8dd ./gradlew clean
test --no-fail-fast verifyHotPathBytecodeFixtures` passed in 7m47s: 1,805 tests,
zero failures/errors, two existing skips. Source/bytecode policy checks retain
exactly 259 existing violations with no additions/removals; the indexed-table
class-reference check passes. The repository policy gate is not fully green.

Touched slopmark: TransactionManager 160.306 → 160.555;
EmbeddedDatabase 164.388 → 164.185; TpccServerMain 75.118,
EmbeddedRiver 66.1254, RelationalDatabase 13.2193 and RiverDatabase 0 unchanged.
The small change extends existing diagnostic delegation without adding a
technical responsibility. Getter bytecode is only a registry read and return;
no allocation, clock call, or field update is added.

Identical diagnostic command, after separate `./make.sh` builds:

```sh
RIVER_JAVA=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-tic-8e74-evidence-20260907/<sample>
```

Fixed tiny standard mix, serializable, no-wait-stress, ten terminals, one
warehouse, 32 attempts, synchronous WAL, unchanged resource budgets, JDK
26.0.2.1. Baselines before edits: **161.400, 157.300 TPS**, zero retries/errors.
Candidates: **161.900, 160.500 TPS**, also zero retries/errors. All four passed
pre/post invariants, phase/capture and deadlock reconciliation with matching
configuration fingerprints and clean stable source. Both candidate server logs
contain exactly `server_retained_snapshots_at_capture=0`.

One candidate Delivery maximum was 351.584ms versus controls 248.019/242.288ms;
the other candidate was 248.213ms. Order Status maxima were 16.134/25.192ms
versus controls 15.936/12.741ms. These single larger tails are retained; no
repeated directional shift outside adjacent variation was identified. The
samples do not prove throughput equivalence or a speedup.

The receipts for these historical samples did not establish launched-class
provenance or complete host exclusion. Later `tic-ed12` restores artifact
binding; host ownership remains separately scoped. Workloads/builds
were manually serialized, and the user's unrelated host load remains present.
These are River-specific diagnostic checks, not P0 certification, a TPC-C
claim, or a comparison with external harness artifacts.

The exact integration smoke (`2d8e4cb307135102a5457c5ffae9305f0183b0f6`,
1s warmup/3s measured) completed at 142.667 TPS with zero errors and zero
terminal transactions/snapshots/locks/waiters, but one measured Delivery
`DEADLOCK` retry. Its attempt tag 513, logical sequence 53, terminal 1, step 23
reconcile to one server outcome, one client retry and one captured victim;
there is no exhaustion, unclassified outcome or overflow. Detailed cycle
capture was disabled, so the cycle identity and cause remain unknown. The
feature/tag had been pushed following the successful receipt check; ticket
closure and further delivery were held for this new retry signal.

Longer interleaved A/B/A/B (5s warmup, 30s measured, otherwise identical):
**162.067 / 162.767 / 176.967 / 165.033 TPS**. A is the same `fa77adc` source
baseline and B is the exact integration commit. All four had zero retries and
errors, passing invariants, and both candidate terminal snapshot counts zero.
Pairwise directions differ, with both candidate values inside the observed
control range. The larger short Delivery tail did not recur (long candidate
maxima 167.043/228.908ms, controls 212.025/208.557ms). This does not identify the
smoke retry's cycle or prove throughput equivalence. Independent review found
no measured-path invocation of the new getter: it runs only during final
metrics writing after workload completion. Runtime content differs only in
the four expected benchmark, engine API, engine and transaction artifacts.
No repeated regression was identified; the smoke anomaly remains preserved.

Integration: `2d8e4cb307135102a5457c5ffae9305f0183b0f6`; pushed annotated tag:
`perf-checkpoint-20260907-retained-snapshot-gauge`. Feature evidence: `5f908e4`.
Evidence root: `/private/tmp/river-tic-8e74-evidence-20260907`, including all
short/long samples, smoke retry accounting, runtime delta, preserved clean XML,
policy comparison, slopmark and independent review.



### 2026-09-07 causal lock-block aggregates (`tic-af29`)

**Required observability; performance inconclusive.** Candidate `d1460d8`
adds one bounded 432-bucket phase aggregate through the canonical scheduler
predicate, without changing grant policy, lock lifetime, durability, or the
workload. `tic-8e74` retains ownership of the snapshot-registry cleanup gauge.
Base `5d70625` is production-identical to pushed tagged integration `195c641`
(`perf-checkpoint-20260907-force-target-ownership`).

Independent concurrency and performance review accepted the preserved release
and fairness invariants, enabled/disabled release-order tests, revoked handoffs,
quiescent capture retry/reset/close, exact bucket/disposition reconciliation,
and overflow rejection. Clean full `./gradlew clean test --no-fail-fast
verifyHotPathBytecodeFixtures` with
`GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f8dd` passed **1,805 tests, zero
failures/errors, two existing skips**, in 7m45s. This includes all 146 transaction
tests. Source/bytecode findings remain **259 → 259, zero added**;
indexed-reference and bytecode-fixture checks pass.

Slopmark touched-file review: TransactionManager 159.851 → 160.306;
LockExactTable 87.5097 → 90.2038; admission 31.6231 → 38.3106;
LockManager 10 → 10.7039; conflicts 25 unchanged. New grant-decision owner
101.8, aggregate 73.6849, cold snapshot 76.4318. Scheduler and remaining touched
adapters/lifecycle files score zero before/after. These are accepted ownership
boundaries, not a numeric complexity improvement. No policy was scattered to
reduce the score.

Both lock allocation modes preserve the existing **≤512-byte allowance over
10,000 measured rounds after 1,000 warmup rounds**, with unchanged resources.
Exploratory failures are retained: 152 bytes under an exact-zero assertion and
roughly 7.2 KB under the bounded assertion. JFR traced the larger failure to
optional `TransactionGroupCompletionTimings` class loading through existing
null-timing cleanup. Resolving that type in test setup makes the full suite
pass without changing the allowance or production. Bytecode inspection finds
only fixed aggregate/snapshot constructor arrays, with no classifier allocation
or clock-read sites. The measured gate is bounded evidence, not literal zero.

All runs used tiny/standard, 10 terminals, one warehouse, seed 42, serializable,
no-wait-stress, synchronous WAL, 32 maximum attempts and the same resource
budgets. TPS JVM was pinned to OpenJDK **26.0.2.1** at
`/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/java`;
build/test toolchain remains GraalVM 25.0.4. `./make.sh` prepared the runtime before
workloads. Standard command was `tools/tps-test.sh --seed=42
--warmup-seconds=W --measured-seconds=D --output-dir=ARTIFACT`.

Individual committed TPS samples, in execution order within each series:

| Series | Warmup/measure | Samples (A control, B candidate) |
| --- | --- | --- |
| Standard enabled, before edits | 1s/10s | A 164.800, A 156.700 |
| Standard enabled, candidate | 1s/10s | B 157.700, B 162.400 |
| Capture disabled, before edits | 1s/10s | A 161.300, A 167.400 |
| Capture disabled, candidate | 1s/10s | B 170.000, B 167.300 |
| Longer enabled | 5s/30s | A 177.900, B 160.300, A 175.467, B 164.167 |
| Longer disabled | 5s/30s | A 180.100, B 166.400, A 178.567, B 180.833 |
| Matched client/server JFR | 5s/30s | A 175.033, B 167.333 |
| Reversed enabled | 5s/60s | B 170.967, A 154.767, B 152.667, A 169.533 |

The disabled diagnostics invoke the same existing Java runner/server with all
four metrics-control arguments omitted; no workload, production flag, or TPS
wrapper change was introduced. They are **supplemental Java-runner diagnostics,
not passing tps-test runs**. Their receipts record stable source/classpath
hashes, zero terminal transactions/locks/waiters, successful exits and database
cleanup. Runtime content comparison finds exactly the expected engine and
transaction jar differences; all other 22 entries match.

The initial 30-second enabled regressions are not discarded. They followed a
repeated short-run Payment maximum increase despite unchanged coarse percentile
bounds. In the profiled pair, measured-window FileForce means were 3.318ms A
and 3.463ms B, while acquisition/scheduler execution samples did not increase.
That association does not explain every earlier pair, and sparse virtual-thread
monitor/park attribution leaves uncertainty. The 60-second pairs reverse
direction; their means (B 161.817, A 162.150) do not prove equivalence. Duration
and profiler groups remain separate. Short enabled/disabled means 160.05/168.65
are descriptive only; they do not isolate observer cost from other capture
instrumentation and changing host conditions.

All runs passed invariants and completed cleanup with zero errors. Every
candidate had zero retries. The final 60-second control had one measured
Delivery DEADLOCK retry: attempt tag 10240, terminal 6, logical sequence 1030,
step 39; one server outcome, one client retry and one captured deadlock reconcile,
with zero unclassified outcomes or overflow. Candidate block totals and terminal
dispositions reconcile exactly; the short runs classified 1,991 and 2,054 blocks
with no unclassified/overflow cases. Roughly 70% were FIFO_QUEUE_HEAD; this is
count-at-admission evidence, not waiting duration or transaction-family cause.

Decision: accept the required `tic-1dda` observability prerequisite under the
working agreement's explicit **inconclusive-performance exception**. This is
neither a speedup nor proof of unchanged performance; observer cost remains
unquantified. The existing `tic-f1bb` performance and recovery gates remain.
Background host load and incomplete TPS host/launched-byte provenance for
these historical samples remain explicit: their v2 success receipts authenticate
diagnostic publication, not the removed stronger ownership/build contract. Builds and
workloads were manually serialized, and all raw negative results are retained.

Evidence root: `/private/tmp/river-tic-af29-evidence-20260907`, including
per-run directories/logs, `samples.json`, `disabled-samples.json`, causal and
mechanism summaries, JFR recordings/window reports, allocation traces, clean
XML/logs, slopmark reports, bytecode audit, policy delta and independent review.
Feature evidence tip: `0b39bfe`. Integration: `8d1d2bc8837fb3426a35b6f5ca7b415f22a1127b`.
Annotated tag: `perf-checkpoint-20260907-lock-block-causality`. Feature,
integration and tag were pushed atomically. Post-merge 1s/3s smoke passed at
145.000 TPS, zero retries/errors and valid capture; this smoke is not comparative.

### 2026-09-07 serial WAL force-target ownership (`tic-7352`)

Architecture checkpoint, with no speedup claim. Implementation/measured candidate
`91b00ea` replaces mutable-tail completion and global forced-batch release/cursors
with captured coverage and exact target/token ownership in the existing serial
path. Group/direct/vacuum callers validate retained coverage; local force and
configured durability success remain distinct. Pushed integration `195c6419afda3e288caf1f57f6e29aae5e51101c`
has annotated checkpoint `perf-checkpoint-20260907-force-target-ownership`; its post-merge smoke passes.

Fresh pinned OpenJDK 26.0.2.1, tiny/standard serializable synchronous-WAL,
10-terminal seed 42 controls: **155.500/157.200 TPS**; candidates:
**167.400/163.800 TPS** (1s warmup/10s measurement). The upward shift triggered
5s/30s interleaving: **control 167.300 → candidate 171.367 → control 180.833 →
candidate 166.033 TPS**. Pair directions reverse (+2.4%, −8.2%); measured writer
stages and latency buckets show no repeated worsening. Independent review accepts
**no repeated regression identified in these diagnostics**, without proving
equivalence or attributing the shift to noise. All eight runs have zero retries
and errors, passing invariants/capture/reconciliation and successful receipts.
Background load and current host/launched-byte provenance limits remain explicit.

Clean full tests and bytecode fixtures pass in 7m42s: **1,796 tests, 0 failures,
2 existing skips**; affected engine 1,008/WAL 33 pass. Policy delta 261→259,
zero added, two stale affected WAL selectors corrected, with unchanged allowances.
Slopmark vacuum 28.4502→31.1973 after a 47.0012 draft triggered shared coverage
comparison; LocalWal 145.405→147.523; group 70.7069→71.4171; coordinator 5→14.1504
for required exceptional-unwind cleanup. Independent recovery review accepted
ownership/failure/cleanup and final performance evidence.

Commands, exact samples, limitations and evidence are in
[`tic-7352`](tickets/tic-7352.md) and
`/private/tmp/river-tic-7352-evidence-20260907` (`SHA256SUMS`).

### 2026-09-07 resource-accounted SQL savepoints (`tic-5cc0`)

Correctness/resource ownership checkpoint; no independent speedup claim.
Integration `274968e8be4ddff0949086ea98e44dbe5ea4a81a`, pushed annotated tag
`perf-checkpoint-20260907-savepoint-admission`; post-merge smoke passes.
Implementation `3c338fc`, measured candidate `f20f2e14590ff4823bac1b719f4a74765fb03555`.
The existing session shape lease admits retained savepoint storage before mutation
and returns it on close. SQL program/rollback/durability behavior is preserved.

Pinned OpenJDK 26.0.2.1, identical tiny/standard serializable synchronous-WAL
10-terminal seed 42 workload, 1s warmup/10s measurement: controls 156.2/162.0 TPS,
candidates 164.0/161.3 TPS. All zero retries/errors and successful invariants,
capture, cleanup and receipts; no repeated short-sample regression signal.
Background host load remains recorded; these are diagnostics, not exclusive-host
or cross-database capacity evidence.

The real SQL boundary regression fails before and passes after; 16 focused tests
and the first clean full test build pass (1,788 tests, 0 failures, 2 skips; 7m42s).
Independent review accepted code/tests. Slopmark state 0 unchanged/coordinator
283.768 unchanged. Indexed-table reference check passes; source/bytecode policy
checks still fail with the same 261 violations, 0 added/removed. No thresholds or
policy allowances changed. Evidence and commands:
[`tic-5cc0`](tickets/tic-5cc0.md), `/private/tmp/river-tic-5cc0-evidence-20260907`.

### 2026-09-06 metadata-directory reload correctness (`tic-f8dd`)

- Stable base: `e6e17b1fd7dbc0433e64c01b2918e9075cc25858`.
- Fix: `f790f9975eb366eff86aad5b36807f4b641307ac`; policy selectors: `e9af239`.
- Checkpoint: `perf-checkpoint-20260906-directory-cache-reload`.
- Pushed integration: `0cf9970f5371f520b6a9639424b4446d9e4c3412`; exact-merge
  lifecycle smoke passed with zero retries/errors and complete cleanup.
- Evidence: `/private/tmp/river-tic-f8dd-evidence`; details: [`tic-f8dd`](tickets/tic-f8dd.md).

The unchanged 30-second workload reproduced order_line CORRUPTION. A 60-second
trace and six deterministic failing disk-backed tests identified exhausted buffer
positions in metadata-directory frame reloads. Both loaders now reset buffers;
row-directory read misses use bounded LRU eviction. Stored formats and durability
are unchanged. All temporary tracing was removed. Independent storage/recovery
and policy review approved the scoped change.

The final clean checkpoint passes **1,781 tests**, zero failures, two skips,
including **1,000 engine tests**. The first run's unrelated exact-lock allocation
assertion is retained; unchanged control and subsequent clean runs passed without
changing its limit. Source/bytecode policy checks retain exactly **261 existing
control violations**, with none added/removed; class-reference verification passes.
Slopmark row directory **40.5049 -> 40.4243**, version directory **28.4651 unchanged**.

Explicit OpenJDK 26.0.2.1, seed42, tiny/standard, serializable, ten terminals,
one warehouse, user background load left running. Untouched 10-second baselines
**153.4/161.5 TPS**, candidates **160.1/156.8**. The original 30-second configuration
passes twice (**181.533/175.733**); the 60-second run also passes (**153.767**).
Final samples have zero measured retries/errors and complete invariants/cleanup.
Failed pre-fix samples have no valid TPS. No speedup is claimed; differing duration
and invalid long control prevent treating the 60-second number as a performance
comparison. This correctness checkpoint resolves tic-f539's specific corruption
stop; it does not implement or accept commit-force pipelining.

### 2026-09-06 commit-force opportunity investigation (`tic-f539`)

Evidence only; no new performance checkpoint or production change.
See [`tic-f539`](tickets/tic-f539.md) for commands, runtime correction, all
samples and independent review. Evidence is retained under
`/private/tmp/river-commit-force-opportunity-20260906`.

With the user's constant background load left running, explicitly pinned
GraalVM 25.0.4 short baselines were **146.6/146.4 TPS**, captured with all probes
removed before the repeated experiment. Interleaved 30-second controls were
**176.100/178.967**, timing probes **178.700/176.933**; all six pinned samples
passed with zero measured retries/errors. Initial OpenJDK-control/GraalVM-probe
TPS pairings are rejected and retained, not used for attribution.

The corrected traces show about **41% of writes** wait behind a force, with
**1.51–1.55 ms** mean overlap among affected writes. Force accounts for about
**97% of actual enqueue-to-selection delay**. Observed preparation/publication
cost fits the preceding force window for about **29% of all cohorts**. This is
an optimistic local opportunity, not achieved TPS or causal-successor proof;
existing QUEUE_RESIDENCE also includes preflight work.

A separate unchanged-master OpenJDK control returned **CORRUPTION** on the
post-run `order_line` count query before CHECKPOINT, and again on database
shutdown. Its receipt is evidence_invalid; it has no valid TPS result.
[`tic-f8dd`](tickets/tic-f8dd.md) owns root cause and resolution. Passing later
samples do not clear it: performance acceptance remains stopped. All probes
were removed, source restoration verified, and touched slopmark scores are
unchanged. Future pipeline work requires prefix-specific force completion and
bounded ownership of pending cohorts/pages after the correctness gate clears.

### 2026-09-06 observed read durability dependencies (`tic-e544`)

- Stable base: `7bcc11ea4624f3e7276cdb562cc33dd310a27fbd`, immediately after
  `perf-checkpoint-20260906-catalog-transaction-resolution`.
- Implementation: `046dd432ab2402ff085932af973d58faf5fc699a` on
  `ticket/tic-e544-read-durability-dependencies`.
- Checkpoint: `perf-checkpoint-20260906-read-durability-dependencies`.
- Evidence: `/private/tmp/river-tic-e544-evidence`; detailed configuration,
  review and decision: [`tic-e544`](tickets/tic-e544.md).

Reads await the maximum sequence of the versions and tuple roots they observed,
including tombstone, absence, range, current-row and write-admission decisions.
Tuple dependencies remain conservative per index. Savepoint rollback retains
observations; transaction reuse resets them. Caller row buffers retain their
ownership without a new steady-state copy or allocation. Fenced read-only commit
uses the existing abort cleanup; cancellation stays active and retryable. Write
acknowledgment, WAL force, locks, isolation, SQL/client requests and workload are
unchanged. Public getter documentation distinguishes a read-only snapshot CSN
from a durable-prefix watermark.

Fixed short command: `tools/tps-test.sh --seed=42 --warmup-seconds=1
--measured-seconds=10 --output-dir=<artifact>`. Tiny/standard, one warehouse,
ten terminals, serializable, no-wait stress, 32 attempts, synchronous durability,
GraalVM 25.0.4. Initial controls were 73.4/62.0 TPS; the user requested repeats.
Unchanged-master repeats were 147.3/149.9 and candidates 149.6/148.5. The short
pair is neutral, including Order Status p95 of 16.777 ms.

Longer interleaving uses 5 seconds warmup and 30 seconds measured, keeping all
other configuration fixed:

| Artifact (execution order) | TPS | Order Status p99 upper bound |
| --- | ---: | ---: |
| `control-long-1` | 149.133 | 33.554 ms |
| `candidate-long-1` | 174.967 | 16.777 ms |
| `control-long-2` | 161.467 | 33.554 ms |
| `candidate-long-2` | 176.100 | 16.777 ms |
| `control-long-3` | 159.233 | 33.554 ms |

The extra trailing control checks the control shift. Candidate throughput is
about 9.5% above the later controls' mean; retain the lower first control and
initial short samples rather than presenting them as the gain denominator.
Every sample has zero retries/errors and successful invariants, reconciliation,
capture and terminal receipt. The longer runs have identical configuration
fingerprints and clean, stable source identities. Order Status p95 is unchanged.
The final control and second candidate have mean shared WAL force durations of
3.502/3.504 ms, with 4,270/4,717 writes and 4,244/4,664 forces. This remains a
local River diagnostic; no cross-database or audited TPC-C claim is made.

Full engine tests passed all 994 cases, including held-force SQL results,
negative observations, caller row ownership, session reuse, force-failure cleanup
and cancellation/retry. Clean full tests passed 1,775 tests with two skips in
`clean-tests-repeat.log`. The first clean run and isolated lock repeat hit the
existing intermittent exact-lock allocation assertion; an isolated SQL allocation
assertion also reproduced 608 bytes on unchanged master, then passed in the full
engine run. No threshold or test was weakened. Source and bytecode policies have
identical 261-violation sets on candidate/control; indexed-table reference checks
pass. Independent concurrency/recovery review approved the final source.

Slopmark triggered review at visibility 55.454 and session 53.5602. Simplifying
the existing control flow reduced final visibility to 44.8766 (from 44.249) and
session to 38.2417 (from 39.1925). Store forwarding is 157.04 versus 156.656;
all other touched engine production scores are unchanged. Scores and raw reports
are retained in `slopmark-touched.tsv` and the before/after artifacts.

Decision: accept this one mechanism based on repeated longer throughput and
read-tail improvement, preserved durability/failure behavior, independent review
and the clean gate. The per-index granularity remains an explicit limitation.

Integration `23484fca398b9219b7ceba6a6ac06a53da04d044` is pushed with the
annotated checkpoint above and the feature branch. Its exact tree matches the
accepted feature. `merged-build.log` and `merged-smoke` record the successful
merged build and 1-second warmup/3-second correctness smoke (zero retries/errors,
successful invariants, reconciliation, capture and terminal receipt). The smoke's
141.667 TPS is not used as a comparative sample. Ticket closure is recorded only
after the pushed integration was verified.

### 2026-09-06 caller-owned catalog resolution (`tic-186e`)

Status: promoted and pushed following the user's commit/push/promote instruction.
The internal descriptor durability blocker is removed. Local saturated
throughput improves with an explained Order Status
read-tail tradeoff; this is River-specific diagnostic evidence, not TPC-C or a
cross-database performance claim.

- Base: pushed `perf-checkpoint-20260906-page-generation-reuse` (`f7ff998`),
  plus its closure documentation at `7df1dc6`.
- Branch: `ticket/tic-186e-catalog-transaction-resolution`.
- Feature commit: `4e871da`.
- Integration commit: `db1059a08257d35de9b1b7bc7ac72225d6225da1`.
- Annotated tag: `perf-checkpoint-20260906-catalog-transaction-resolution`.
- The exact merged revision passed `promotion-smoke` with zero retries/errors,
  passed invariants, stable source and a successful terminal receipt. Feature,
  integration and tag were pushed atomically to the existing origin.
- Evidence: `/private/tmp/river-tic-186e-evidence`; individual sample artifacts,
  logs, terminal receipts and `sample-summary.json` remain outside Git.
- Mechanism: internal name resolution passes the admitted relational transaction
  into the existing authoritative catalog loader. Shared head/manifest checking,
  exact cache identity and reservation cleanup remain in one opener. Standalone
  catalog opens retain their independent durable transaction. Private DDL
  overlays remain caller-owned. No new cache, queue, flag or allocation site.
- Deterministic proof: unchanged production fails `reproducer.xml` because a
  blocked successor cannot enqueue during a held force. The candidate passes
  both success and force-failure cases through a SQL transaction program with
  subsequent descriptor/FK reads. No response escapes early. Cold-cache assembly,
  exact cache hits, failed lookup cleanup and standalone durability also pass.
- Validation: all **983 engine tests** passed. `clean test --no-fail-fast
  --continue` ultimately passed with **1,764 reported tests**, zero failures,
  two skips and valid Gradle cache reuse. The first clean run hit the unchanged
  `LockExactAllocationTest` (7,104 bytes against 512); the full transaction suite
  reproduced it on unchanged master (6,832 bytes), its isolated rerun passed,
  and the second clean build passed without code or threshold changes. Preserve
  this intermittent baseline failure in `clean-lock-allocation-failure.xml`,
  `base-lock-allocation.xml` and the associated logs.
- `verifyIndexedTableClassReferences` passed. `verifySourcePolicy` and
  `verifyHotPathBytecode` still fail on unchanged files/entries; no touched
  production or test file is named. See `policy.log`; no allowlist was widened.
- Slopmark: `CatalogTableOpener` **7.42713 -> 0**; descriptor names **17.9248**,
  lifecycle **13.7851**, services **0**, all unchanged. Shared load/completion
  reduced opener complexity. Bytecode retains only its three pre-existing
  constructor allocation sites.
- Independent concurrency, allocation and performance review:
  `/root/review_catalog_overlap`; no blocking finding. The review accepts the
  scoped mechanism and requires recording the read-tail tradeoff below.

Build each source with `./make.sh` before `tools/tps-test.sh`. The feature
worktree uses `GRADLE_USER_HOME=/private/tmp/river-gradle-tic-186e`; its project
cache and outputs are isolated. The fixed workload command is:

```sh
tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=/private/tmp/river-tic-186e-evidence/<sample>
```

Defaults held fixed: tiny profile, standard mix, one warehouse, ten terminals,
SERIALIZABLE JDBC/program isolation, no-wait stress, 32 maximum attempts,
durable local WAL, diagnostic evidence, GraalVM Java **25.0.4**, same host and
resource budgets. No build or other workload overlapped a sample. The longer
investigation changes only warmup/duration to **5/30 seconds**, scheduled
control-1, candidate-1, control-2, candidate-2. Configuration fingerprints match
within each duration group; every terminal receipt reports success, source and
workspace remain stable, invariants pass, and errors are zero.

| Samples | Control TPS | Candidate TPS | Retries |
| --- | --- | --- | --- |
| Short 1 | 114.600 | 143.200 | 0 / 0 |
| Short 2 | 116.800 | 143.500 | 0 / 0 |
| Longer 1 | 128.467 | 160.400 | 0 / 0 |
| Longer 2 | 128.533 | 160.767 | 1 reconciled control deadlock / 0 |

The longer candidate mean is **25.0% higher** in this local saturated workload.
Force duration remains approximately 3.7–3.9 ms. Cohorts remain predominantly
singleton (long pair 1: 3,463 transactions / 3,463 forces in the control,
4,299 / 4,238 in the candidate). The held-force test proves execution overlap;
the gain should not be described as broad force amortization.

**Latency tradeoff:** Order Status p95 rises from **8.388 ms to 16.777 ms** in
both longer pairs; its approximately sixteen JDBC requests encounter a more
continuously active writer. Other family distributions are retained in
`sample-summary.json`; New Order, Payment and Stock Level improve in the first
long pair. Tagged client/server diagnostic probes explain the read-tail shift:
attempt **2684** takes **17.359 ms**, including **12.420 ms** in three public
durability waits (4.963, 3.785, 3.672 ms), each overlapping a distinct WAL force.
The matching baseline/candidate traces record waits of at least 100 us in
**6/136 versus 54/176** Order Status attempts across warmup, measurement and
drain. These instrumented traces are causal evidence, not TPS samples.

The temporary probes are removed. Their JFRs, extracted events and correlated
attempts remain under `base-waits-*`, `waits-*` and `order-status-waits.json`.
Reducing repeated public read waits requires a separate dependency or transaction
program improvement; this feature preserves public durability and does not
absorb that mechanism or the separate Payment program work.

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


## riverd authenticated lifecycle candidate — 2026-09-08 (not accepted)

Branch: `ticket/tic-ec50-riverd-launcher`, building on pushed checkpoint
`75775e20`. This delivery replaces plain connections with TLS, generated client
configuration and the accepted durable security-audit path. Database isolation,
commit durability, workload SQL and the explicit TPS resource profile were kept
unchanged. No promotion is approved by these results.

Two pre-change samples at `75775e20` measured **162.4 and 163.4 committed TPS**.
Two authenticated candidate samples measured **8.5 and 8.1 TPS**. Both pairs used:

```sh
tools/tps-test.sh --profile=tiny --mix=standard --terminals=4   --warehouses=1 --warmup-seconds=2 --measured-seconds=10 --seed=42   --output-dir=<unique-evidence-directory>
```

These River-specific diagnostic runs used GraalVM Java 25.0.4, serializable
isolation and no-wait-stress scheduling on the same AC-powered Mac. No build or
other workload overlapped a sample. All four measured runs reported zero errors,
successful checkpoint/deadlock reconciliation and valid performance capture.
The slowdown repeats and is much larger than adjacent sample variation. This is
a comparison of the delivery's added security cost, not an audited TPC-C result.

Artifacts under `/private/tmp/riverd-delivery-evidence`:

- Baseline: `launcher-tps-before-1`, `launcher-tps-before-2`.
- Candidate: `launcher-tps-after-2`, `launcher-tps-after-3`.
- `launcher-tps-after-1` failed before readiness because the temporary directory
  used the macOS `/var` alias. Canonicalizing the tool-owned directory fixed the
  launch; that failed attempt contains no TPS measurement.
- `launcher-tps-candidate-build-2.log` records the successful no-daemon build;
  its sealed build identity is
  `5321106e462ca6ad9205057839b1ce39dd450d641cd82884f63b5e7b5eb03379`.
- `launcher-tps-audit-cost` and `launcher-audit-cost.server.jfr` are a separate
  diagnostic capture, also 8.5 TPS. The JFR includes startup/load activity and
  does not provide measured-window audit-force accounting or an allocation
  claim. Its samples include audit append/wait and APFS force paths.

Focused review traced each executed program step to its required synchronous
security-audit admission. The coordinator forces the active audit stream once
per cohort; it does not force the control files for every decision. Earlier
single-client audit evidence measured roughly 3.9 ms per force, but that is a
separate workload. No redundant authorization was identified. Removing or
postponing required audit records would change the accepted security contract.
The user has been asked whether normal statement execution should require this
separate durable audit, retaining authentication and database commit durability.
That decision is pending; the implementation has not relaxed the contract.

Slopmark (`launcher-slopmark-2.txt`): instance ownership rose from 153.77 to
245.744, principally from retaining each nonterminal resource and its identity
lock during cleanup. Independent review checked that ownership behavior and its
busy-session test. The instance owner still coordinates existing components;
credential, audit, path and record-format policy remain with their existing
owners. Server score fell from 131.928 to 122.729; client connection from
196.723 to 195.799. Identity and runtime-record scores stayed at 988.965 and
552.142. These scores prompted review, not a speculative refactor or a quality
claim. Full integration validation and Windows qualification remain separate
completion gates.


### Final candidate integration checks (2026-09-08)

The clean `--no-daemon --no-parallel clean test` integration run executed
1,879 tests successfully, with 18 skips and no failures or errors (1,897 total).
The accompanying policy tasks left the overall command red: the module ledger
was corrected and `verifyModuleGraph` then passed; pre-existing source-policy
and SQL-shape violations remain. No release-check pass is claimed. Evidence:
`/private/tmp/riverd-delivery-evidence/launcher-clean-integration-1.log` and
`launcher-policy-check-2.log` in the same directory.

The migrated UPDATE trace tool built with `--no-daemon`, used generated TLS
client configuration, and completed its UPDATE/COMMIT trace successfully.
Evidence: `/private/tmp/riverd-delivery-evidence/launcher-update-trace-1.log`
and the adjacent `launcher-update-trace-1/` artifacts.

This is a feature checkpoint only. Windows execution evidence and the decision
on synchronous SQL auditing remain outstanding; do not promote or close the
standalone milestone from these results.


## 2026-09-08 — Remove mandatory riverd SQL/security auditing

The user removed auditing from the immediate roadmap. `tic-1c4d` deletes the
subsystem rather than adding a disabled mode. Authentication, permissions,
credential fencing, database/WAL durability, and workload SQL remain intact.
Future consideration requires a concrete architecture supporting performance
neutrality; no audit implementation or study is scheduled.

Existing audited JARs (`23a1cb8e`) measured 8.2 and 8.2 TPS; the audit-free
candidate measured 71.9 and 71.9 TPS. Commands used GraalVM 25, tiny/standard,
four terminals, one warehouse, seed 42, two seconds warmup, ten measured.
Successful samples had zero errors and passing reconciliation/capture.
The user reported battery power; power was not independently controlled across
the series. These diagnostics do not establish AC performance neutrality or
justify comparison with the earlier 162–163 TPS result. The subsequent master
control timed out in warmup and provides no TPS comparison. No further battery
performance investigation is included in this removal.

Evidence paths: `/private/tmp/riverd-noaudit-before-2/`, `before-3/`, `after-1/`,
and `after-2/` (all share the `riverd-noaudit-` prefix). `before-1` is excluded
because an agent built concurrently in the wrong checkout; those unintended
source edits were restored. The invalid control is `riverd-noaudit-control-1/`.

The clean test/distribution/module-graph build passed with 1,863 passed tests,
18 skips, no failures/errors; unchanged tasks used Gradle cache results.
Audit-only tests were deleted, while authentication/permission/expiry and
cancellation checks remain. Installed lifecycle passed on macOS/APFS and
Linux/ext4/XFS. Slopmark fell for identity (988.965→935.340), instance owner
(245.744→221.793), and server (122.729→113.333). Full evidence and review are
recorded in `docs/tickets/tic-1c4d.md`. Windows execution remains outstanding
for the overall standalone milestone.


## 2026-09-09 — unified River executable baseline (tic-ed14 / tic-9cfd / tic-a51d)

Baseline source: `af562206`, with ticket-only planning commit `849c8f12` on
`ticket/tic-ed14-unified-command-integration`. Built using
`./gradlew --no-daemon :river-bench:installTps`; build passed. No other build or
workload overlapped the two measured samples. Default runtime was OpenJDK
26.0.2.1 on macOS arm64; native/JVM packaging comparisons must separately match
the GraalVM runtime version.

Commands: `tools/tps-test.sh --version=unified-before-N --seed=42
--output-dir=PATH` for N=1,2. Defaults: tiny, standard mix, serializable,
no-wait-stress, one warehouse, ten terminals, 32 maximum attempts, one second
warmup and ten measured seconds, unchanged explicit resource budgets and durable
commit behavior. Both completed checkpoint with status OK and exit 0, with
successful deadlock reconciliation and performance capture: **157.7, 157.8 TPS**.
Artifacts: `/private/tmp/river-unified-before-1-authorized/` and
`/private/tmp/river-unified-before-2/`. The initial sandbox-restricted launch at
`/private/tmp/river-unified-before-1/` failed startup and is not a TPS sample.

Slopmark baseline: `/private/tmp/river-unified-slopmark-before.txt`, covering
river-cli, river-server-app production sources and tools. Command parser 215.049,
foreground owner 163.184, server main 13.044, client main 8.61233. These are review
signals; this delivery does not authorize changes to database hot paths.
Candidate validation and native feasibility remain pending.


Entry-point slice integrated at `39c9df6d` (agent source `004ffbac`; final
agent branch `04bcf875` adds two documentation-name corrections).
`:river-server-app:test :river-cli:test verifyModuleGraph
:river-server-app:installDist` passed in an isolated checkout, with installed
help/version/client-usage smoke passing. Root review checked application/library
direction and command error reporting. Slopmark after entry-point migration:
`/private/tmp/river-unified-slopmark-entrypoint.txt`; parser and foreground owner
unchanged, new dispatcher 31.3175, client runner 6.60964. Installed persistent
lifecycle and cumulative TPS checks remain required before acceptance.


Help slice integrated at `18c353b2` (agent `37a69a0a`). Full client/server module
tests, module distribution and assembled help alias smoke passed. Root review
removed help-display-to-parser coupling and duplicate topic definitions, required
all topic/alias exit checks, and corrected the inherited ps/datadir mismatch.

The installed JVM lifecycle control on GraalVM 25 passed fresh start, CLI commit,
graceful termination, restart and readback. Evidence directory:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-unified-jvm-smoke-46cirw86`;
script `/private/tmp/river-jvm-lifecycle-smoke.py`. Owned database was removed.
The first control used a noncanonical macOS /var alias for the config path and
was rejected; using the canonical server path passed. This is a test-path fix,
not an authentication change.

Native feasibility on GraalVM 25.0.4 macOS arm64 produced a roughly 49 MiB image.
First start exposed a missing FFM downcall registration; adding APFS signatures
allowed startup to proceed to a later INVARIANT_BROKEN outcome. Native functional
acceptance and TPS comparison are pending. Native build log:
`/private/tmp/river-native-evidence-a51d/nativeCompile.log`. No native performance
or cross-platform support claim is made from a build/help-only result.


Unified command/help/default-client acceptance at `ce5ac8b9`:

- Full client/server module tests and assembled help/default-client workflow pass.
  Default server creation plus bare-client SQL used an isolated user home; startup
  prints the resolved directory, endpoint and client configuration after readiness.
- Updated TPS distribution build passed. Matching short samples
  `unified-help-after-1` and `unified-help-after-2`: **155.8, 156.8 TPS**,
  checkpoint/status OK, exit 0, reconciliation/capture OK. Artifacts are
  `/private/tmp/river-unified-help-after-1/` and `...-2/`.
- Because both short samples were below the first pair, ran a longer matched
  control/candidate pair with 5-second warmup and 30 measured seconds; all other
  settings unchanged. Control `af562206`, `unified-control-long-1`: **161.033 TPS**;
  candidate `ce5ac8b9`, `unified-help-long-1`: **172.533 TPS**. Both completed with
  status OK and reconciliation/capture OK. Artifacts:
  `/private/tmp/river-unified-control-long-1/` and
  `/private/tmp/river-unified-help-long-1/`. The short downward movement did not
  repeat. Accept the Java command changes without a throughput improvement claim.
- Final command/help slopmark: `/private/tmp/river-unified-slopmark-help.txt`.
  Parser 215.049 -> 217.903; foreground 163.184 -> 163.844; new root dispatcher
  71.357 and command catalog 6.89256; help renderer 42.1454. Cold command-routing
  responsibilities account for growth; database, credential, transaction and
  execution owners remain unchanged in these slices. Root review removed the
  initial duplicated topic policy and display-driven flag parsing.

Native image compatibility/performance remains a separate unfinished acceptance;
these Java diagnostic samples do not measure the native executable.


### Native executable credential compatibility — 2026-09-09 (not accepted)

Branch: `ticket/tic-a51d-native-river-executable`. Native compatibility candidate
used the unified entrypoint at `39c9df6d` plus native packaging and trace-derived
credential registrations; the final packaging source also incorporates the
accepted help/default-client changes at `7c1b45d5`. JVM control distribution was
built at `ce5ac8b9`. These commits have the same database engine behavior.

GraalVM 25.0.4 on macOS arm64 built a standalone executable with default `-O2`,
Serial GC and a 1 GiB maximum heap. The JVM control used the same GraalVM JDK,
its default collector and a 1 GiB maximum heap. Both servers used unchanged
production resource defaults. This comparison measures native versus JVM
execution; it is separate from the earlier `tools/tps-test.sh` numbers.

The temporary driver `/private/tmp/river-native-tps.py` launched each production
server and the existing `TpccAcceptanceMain` over JDBC. All samples used tiny
cardinalities, standard mix, serializable isolation, no-wait-stress scheduling,
one warehouse, ten terminals, seed 42, batch rows 32 and maximum attempts 32.
The same JVM benchmark client drove both server types. Full commands, runtime,
results and cleanup are retained under `/private/tmp/<label>/` in `run.json`,
`client.log`, `server.log` and `acceptance.properties`.

Samples ran serially without compilation or other task-owned workloads:

| Variation label | Warmup / measurement | Committed TPS |
| --- | --- | ---: |
| `native-packaging-jvm25-before-1` | 1s / 10s | 133.5 |
| `native-packaging-native25-after-1` | 1s / 10s | 116.0 |
| `native-packaging-jvm25-before-2` | 1s / 10s | 135.6 |
| `native-packaging-native25-after-2` | 1s / 10s | 116.8 |
| `native-packaging-jvm25-long-1` | 5s / 30s | 162.367 |
| `native-packaging-native25-long-1` | 5s / 30s | 107.667 |

Each run passed pre/post workload invariants and checkpoint, with no failed
transactions. Owned servers exited and temporary databases were removed.
These workload runs did not execute the separate recovery-verify phase.
Recovery and credential reuse were checked independently by a copied-executable
lifecycle: fresh startup, authenticated CREATE/INSERT/SELECT, graceful shutdown,
restart and reading the committed row. Evidence:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-native-lifecycle-smoke-aqqn2cs0`.

The native credential failure was missing Bouncy Castle reflection registration.
A successful JVM tracing-agent lifecycle identified six zero-argument constructors
needed for create/sign/parse/reload. Independent review confirmed that these
registrations leave crypto and TLS semantics unchanged. Credential source has
no production change; diagnostic logging and an ineffective provider flag were
removed. Investigation details and build logs:
`/private/tmp/river-native-evidence-a51d/credential-registration.md`.

Decision: credential compatibility is fixed, but native packaging is **not
accepted for merge**. The short-sample throughput gap repeated and grew in the
longer pair. Compiler/collector differences are candidates for investigation,
not an established cause. Do not disguise the gap with benchmark changes or
broaden this packaging ticket into engine optimization. Linux and Windows native
lifecycle validation also remains outstanding.


Focused integrated validation: `:river-server-app:test :river-cli:test` passed
with `--no-daemon` on GraalVM JDK 25.0.4. Log:
`/private/tmp/river-native-evidence-a51d/affected-tests.log`.
Slopmark comparison against the accepted help slice found no changed scores in
credential, TLS, parser, lifecycle or CLI execution owners. The changed version
resource reader scores 0. Output: `/private/tmp/river-unified-slopmark-native.txt`.

Final integrated native build passed with per-platform FFM metadata selected by
the build and common credential metadata embedded. Log:
`/private/tmp/river-native-evidence-a51d/nativeCompile-integrated.log`.
The copied executable passed all help topics and aliases, embedded version,
invalid-port rejection, conflicting startup (`CONFLICT`), wrong-token rejection
(`INVALID_EXTERNAL_INPUT`, the existing authenticator contract), authenticated
SQL, graceful shutdown, and restart/read. Evidence:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-native-lifecycle-smoke-j2ylovc7`.
Owned processes exited and the temporary database was removed. An earlier smoke
asserted a nonexistent AUTH status for token rejection; its fixture was corrected
to the existing contract before this successful run. No product change was
needed for that assertion.


Native slowdown investigation (no production optimization applied): matched
5s/30s profiles produced JVM 154.6 TPS and native O2 105.133 TPS. Native pauses
totaled 52.387 ms over the approximate 30s measurement interval; GC pauses cannot
explain the throughput gap. Evidence under
`/private/tmp/native-cause-{jvm,native}-profile-1/` includes GC logs, sampled
stacks, workload artifacts, cleanup results and JVM JFR.

A symbol-retaining O2 build reproduced 108.2 TPS. Its largest Java execution leaf
was `DirectByteBuffer.get`: 273 of approximately 1,820 Java execution samples.
Native disassembly shows four out-of-line byte-getter calls for one
`BTreePage.getInt`, retaining per-access bounds/session checks. A temporary O3
compiler-only build inlined these getters and reached 129.6 TPS. Getter leaf
samples fell to 20; samples moved into callers. This supports expensive native
accessor code generation as a material contributor, but O3 changes optimization
globally, so this experiment does not assign the entire gain or remaining gap
to one B-tree method. The JVM still leads.

Evidence: `/private/tmp/native-cause-native-symbols-1/`,
`/private/tmp/native-cause-native-o3-1/`,
`/private/tmp/river-native-hot-assembly.txt`, and
`/private/tmp/river-native-o3-btree-assembly.txt`.
The compiler experiments used temporary Gradle initialization scripts; no
production source/build setting changed. All workload invariants and owned
cleanup checks passed. The original O2 executable was restored after retaining
the experimental binaries in `/private/tmp/river-native-before-symbols/`.

A bounded follow-up is to express canonical fixed-width reads/writes directly
with static fixed-endian ByteBuffer-view VarHandles in FormatBytes and route
the B-tree duplicate primitives through that owner. This is a proposed source
change, not an implemented or measured improvement. Keep CRC, key comparison,
and broader buffer API changes outside that slice.


### Fixed-width format access — tic-e419 (2026-09-09)

Branch `ticket/tic-e419-fixed-width-access` starts at native candidate `349de73e`.
The user requested comparison against JVM 154.6 TPS and native O3 129.6 TPS.
Adjacent unchanged controls were JVM 153.4 TPS and native O3 130.0 TPS, retained
at `/private/tmp/word-access-jvm-control-1/` and
`/private/tmp/word-access-native-control-1/`.

The source change replaces FormatBytes byte assembly with three static final
little-endian ByteBuffer-view VarHandles using plain get/set. BTreePage and
BTreeKeyLayout import those primitives and delete their duplicate implementations.
Checksums, comparison loops, buffer ownership, format layouts, compiler defaults,
and durability behavior are unchanged. Bytecode inspection confirms primitive
signatures without boxing/allocation in the six accessors.

One clean `test check` checkpoint ran with `--no-daemon` on GraalVM JDK 25.0.4.
All tests passed, including new independent encoding/state/boundary checks and
existing format, storage, recovery and SQL integration tests. Overall `check`
failed solely in `verifySourcePolicy`: existing XML/Java indentation, raw Unicode
escapes and test-support identifier violations. Every reported file is unchanged
from baseline `349de73e`; no waiver or unrelated cleanup was added. Full log:
`/private/tmp/river-word-access-check.log`.

Slopmark before/after: BTreePage 28.6416 → 28.3441; BTreeKeyLayout 7.92481 →
7.92481; FormatBytes 6.00817 → 6.00817. Artifacts:
`/private/tmp/river-word-access-slopmark-{before,after}.txt`.

Comparisons use the same temporary `/private/tmp/river-native-profile.py` driver
as the requested baselines: tiny, standard mix, serializable, no-wait-stress,
one warehouse, ten terminals, seed42, batch rows32, maximum attempts32, 5s warmup
and 30s measurement. Both servers retain production defaults and a 1GiB heap.
GraalVM25.0.4 drives both; native remains O3/Serial GC with retained symbols and
GC logging; JVM retains default G1, GC logging and profile JFR. Both receive the
same 20s stack sample during measurement. No build overlaps any measured run.
The JVM launches the current compiled classes directly, without Gradle, using
`/private/tmp/river-word-access-jvm`; native uses the rebuilt `bin/river`.

| Runtime | Requested baseline TPS | Adjacent control TPS | Candidate 1 TPS | Candidate 2 TPS | Candidate mean TPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| JVM | 154.6 | 153.4 | 174.2 | 174.0 | 174.1 |
| Native O3 | 129.6 | 130.0 | 143.6 | 141.7 | 142.7 |

Candidate means are 12.6% and 10.1% above the respective requested baselines.
These are short local diagnostic comparisons, not a sustained performance claim
or promotion gate. Both runtimes improve; native still trails the improved JVM.
Run order was JVM control, native control, JVM candidate 1, native candidate 1,
JVM candidate 2, native candidate 2. Candidate artifacts are
`/private/tmp/word-access-{jvm,native}-after-{1,2}/`; each contains the exact
commands, workload result, GC/profile data and cleanup outcome. TPS sums measured
commits only, excluding drain commits. All runs completed with zero retries,
retry exhaustion or failed outcomes; workload invariant checks passed and owned
servers/databases were cleaned up. Longer interleaved controls/candidates remain
necessary before making a sustained gain claim or promoting a performance feature.

Native disassembly confirms single 32/64-bit loads in the FormatBytes fast paths,
with bounds and buffer-lifetime checks retained. Byte assembly and its repeated
byte getter calls are gone. Evidence:
`/private/tmp/river-word-access-native-assembly.txt`. This demonstrates the intended
mechanism; it does not attribute the entire measured difference to one call site.

The copied native executable passed help aliases, version, invalid port rejection,
credential generation/use/rejection, duplicate instance rejection, SQL commit,
shutdown and restart/read with JAVA_HOME and GRAALVM_HOME unset. Its owned database
was removed. Logs:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-native-lifecycle-smoke-16hyfnsb/`.

Decision: implementation and requested diagnostic comparison complete; retain the
isolated feature commit. No merge/promotion is recorded here. Native packaging
acceptance remains open under tic-a51d, including platform validation and the
remaining native/JVM performance gap. The inherited source-policy check failures
remain visible rather than being waived or folded into this optimization.


### Native CPU target and PGO investigation — tic-a51d (2026-09-09)

Branch `ticket/tic-a51d-native-compiler-tuning`, source `c05e3439`. This is a
compiler-only experiment after fixed-width access; no database semantics,
protocol, durability, heap, GC, or production build defaults change.
Artifacts and temporary build/runner scripts: `/private/tmp/river-native-tuning/`.
All native builds use GraalVM 25.0.4, `--no-daemon`, retained local symbols, and
run serially without concurrent workloads. Native runtime keeps Serial GC and
1GiB maximum heap. Every measurement uses the same diagnostic production-server
runner described above, 5s warmup and 60s measurement, tiny data, serializable,
ten terminals, one warehouse. TPS excludes drain commits.

CPU-target interleaving, standard mix/seed42:

| Variant | Sample 1 TPS | Sample 2 TPS | Mean TPS |
| --- | ---: | ---: | ---: |
| O3 armv8.1-a | 140.1 | 139.4 | 139.7 |
| O3 native | 140.8 | 140.5 | 140.7 |

This small observed difference does not establish a worthwhile general gain.
Disassembly confirms hardware AES/PMULL in the native-target intrinsic stubs,
where the generic image retains software AES/GHASH methods. CRC32 and LSE were
already enabled in the generic target. Do not silently narrow distributed
binary CPU compatibility on these measurements.
Runs: `/private/tmp/cpu-tuning-{generic,native}-{1,2}/`, in that interleaved order.
All passed workload checks and owned cleanup. Native sample1 had two Delivery
deadlock retries, matched by server/client counters; neither exhausted. Other
runs had no retries. No failed or drain-failed outcomes occurred.

PGO training uses `--pgo-instrument -march=native`, the standard five-family mix,
seed77, 5s warmup and 90s measurement. Graal reports its instrumentation build
as O2, sampling+instrument; it is not a timed performance candidate. Training
passed with zero retries/failures and successful cleanup, producing
`/private/tmp/river-native-tuning/training.iprof`. Training run:
`/private/tmp/pgo-tuning-training/`. The optimized candidate uses O3,
`-march=native`, and that profile; build output confirms `PGO: user-provided`.


PGO validation interleaves O3 native-target controls with the PGO candidate,
keeping all other settings fixed. Standard mix uses seed42 (training used77).
The held-out New Order/Stock Level 50/50 mix uses seed99.

| Workload / variant | Sample 1 TPS | Sample 2 TPS | Mean TPS |
| --- | ---: | ---: | ---: |
| Standard / control | 143.3 | 139.5 | 141.4 |
| Standard / PGO | 158.9 | 157.7 | 158.3 |
| New Order/Stock Level / control | 88.2 | — | 88.2 |
| New Order/Stock Level / PGO | 100.1 | — | 100.1 |

Standard-mix mean improvement is 12.0%; the single held-out pair improves 13.5%.
All six validation runs had zero retries, retry exhaustion, failed or drain-failed
outcomes, passed workload checks and removed their owned servers/databases.
Artifacts: `/private/tmp/pgo-tuning-{native,pgo}-standard-{1,2}/` and
`/private/tmp/pgo-tuning-{native,pgo}-new-order-stock-level-50-50-1/`.
The candidate code area fell from 46.70MB to 24.70MB. That verifies a substantial
code-generation change but does not allocate the measured gain among inlining,
branch decisions, instruction-cache effects or individual methods. Profiling
and compiler logs are retained with the experiments. These local diagnostics
do not establish performance across all workloads or platforms, and are not
matched directly against earlier 30s JVM samples.

The PGO executable passed the same copied-file native lifecycle smoke with no
JAVA_HOME/GRAALVM_HOME: help aliases, version, invalid port, credential creation,
wrong-token rejection, duplicate-instance rejection, SQL commit, shutdown and
restart/read. Logs:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-native-lifecycle-smoke-2c3a7aa_/`.
No production source/build change was made, so this compiler-only experiment
needed no new source tests or slopmark run. The preceding source checkpoint's
full test suite passed; its inherited source-policy check failures remain open.

Decision: PGO is a promising packaging follow-up with a repeated local gain and
a positive held-out check. CPU targeting alone shows only a small effect here;
do not require the build host's CPU features in public binaries without choosing
and validating a supported target. Keep both decisions separate. Production
build defaults are unchanged. Experimental executables remain in
`/private/tmp/river-native-tuning/river-o3-{generic,native,pgo}`; the working
`bin/river` is restored to the starting generic O3 executable. No merge or native
packaging acceptance is implied; tic-a51d remains open for supported-platform
validation and a deliberate final build configuration.


## Native delivery validation and predecessor ownership — 2026-09-09

Branch `ticket/tic-a51d-native-compiler-tuning`, following `1faa014d`.
The supported native task now provides an O3 build and explicit PGO instrument/profile
options using the compiler's portable default CPU target. The user accepted the
remaining Linux/Windows native validation risk for pre-alpha.

The manual hot-method bytecode inventory and SQL source-match ceilings were
removed at the user's request, including their fixtures and task wiring. Global
source, dependency and architecture checks remain. The complete `check` task
passed without exclusions before final workload validation
(`/private/tmp/river-native-final-check-complete.log`).

Final validation then exposed intermittent publication `INVARIANT_BROKEN`
failures in both JVM and native execution. Failed samples are retained at
`/private/tmp/native-final-jvm-1`, `/private/tmp/native-final-native-1`, and
`/private/tmp/native-final-jvm-fence-probe-{1,2}`; the latter three retain their
failed databases. Successful adjacent JVM samples do not erase those failures.
Temporary failure probes narrowed the returned error to group publication,
without a durability-cleanup failure or an unexpected Java exception. A later
probed 90s JVM sample passed (`/private/tmp/native-final-jvm-fence-probe-3`);
it is diagnostic only, not an optimized throughput sample.

Review found an unowned predecessor cache-slot reference in prepared page
publication. Later member preparation could evict that predecessor before its
successor was linked. The small-cache regression
`preservesPreparedPredecessorWhenLaterMemberNeedsEviction` fails at installation
with `INVARIANT_BROKEN` on the original code and passes after the fix. The batch
now holds a predecessor pin until linking, or releases it on cancellation.
This adds no allocation or copied payload and preserves the existing WAL ordering.
Independent review checked ownership across repeated same-page generations,
reverse cancellation and concurrent reader pins. The focused cache test class
passes, including old/new snapshot visibility and cancellation pin cleanup.
Red/green logs: `/private/tmp/native-fence-probes/predecessor-{red-2,green-2}.log`.
Slopmark for `IndexedPreparedPageBatch`: 32.8233 → 35.1522; the change stays within
its existing page-generation ownership responsibility. All temporary probes
were removed. A benchmark rollback failure now retains the original SQL error
as a suppressed exception while preserving the existing thrown-error classification.

The final full `check :river-bench:installTps` build passed without exclusions
after the ownership fix (5m08s):
`/private/tmp/river-native-final-fixed-check.log`. All Gradle invocations used
`--no-daemon`. The final native rebuild reuses the representative seed77 profile
`/private/tmp/river-native-final.iprof`, captured with the supported generic-target
instrumentation task before the pin-accounting fix. The training workload passed;
the profile remains a build input only.

Final build: `/private/tmp/river-native-fixed-final-build.log`, successful in
1m51s; Oracle GraalVM 25.0.4, O3, armv8.1-a, user-provided PGO, Serial GC.
Final matched samples use tiny data, standard mix, serializable isolation,
10 terminals, one warehouse, seed42, batch rows32, maximum attempts32,
5s warmup and 60s measurement. Server resource defaults are unchanged; the JVM
launcher uses the same GraalVM JDK with `-Xmx1g`. Runs are sequential and
interleaved JVM/native/JVM/native. Driver and commands:
`/private/tmp/river-native-fixed-compare.py`,
`/private/tmp/river-native-final-forensic.py`, and each artifact's `run.json`.

| Runtime | Sample 1 TPS | Sample 2 TPS | Mean TPS |
| --- | ---: | ---: | ---: |
| JVM | 169.100 | 169.600 | 169.350 |
| Native O3/PGO | 153.333 | 153.017 | 153.175 |

Artifacts: `/private/tmp/native-fixed-final-{jvm,native}-{1,2}/`. All four
completed load, preflight, measurement, drain and checkpoint, with zero failed
or exhausted transactions and successful owned-server/database cleanup. Native
sample2 had one Delivery deadlock retry, matched exactly by server/client
counters; the other three had no retries. Accounted Delivery deadlocks also
appeared in the earlier CPU-target controls. The publication failure did not
recur. These local results exceed the original 154.6 JVM / 129.6 native figures,
but those earlier baselines were separate runs; do not interpret the difference
as a controlled estimate of the pin fix's cost or gain.

The final executable, copied alone outside the build tree with no JAVA_HOME or
GRAALVM_HOME, passed help aliases, version and invalid-port handling, credential
creation, wrong-token and duplicate-instance rejection, authenticated SQL commit,
shutdown, restart and reading the committed row. Both owned servers stopped
and the database was removed. Log directory:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-native-lifecycle-smoke-l9j51dyb/`.

Final `tools/tps-test.sh` smoke also passed: version
`a51d-final-predecessor-pin`, tiny standard mix, serializable, seed42,
10 terminals, one warehouse, 2s warmup and 10s measurement; 148.0 TPS, zero
errors, `deadlock_reconciliation=OK`, `performance_capture=OK`. This short
managed-server smoke is separate from the matched runtime comparison above.
Artifact directory `/private/tmp/river-a51d-final-tps-test`; console log
`/private/tmp/river-a51d-final-tps-test.log`.

Decision: accept the unified command/help/native delivery and the reviewed
predecessor ownership fix for pre-alpha promotion. The original failing samples
remain recorded; the focused reproducer is fixed, full checks pass and the
four final workload samples and standalone lifecycle smoke pass. Linux/Windows
native validation remains explicitly unclaimed under the user-approved exception.

## 2026-09-09 — Local host:port stop and instance listing

Branch `ticket/tic-0803-river-stop`, stable integration base `b15b0a6b`,
tickets tic-0803 and tic-d2e9. Integration tag:
`perf-checkpoint-river-stop-20260909`.

`river stop [HOST:PORT]` selects the default instance or one verified local
endpoint; `river ps` shows SERVER, DEFAULT and DATA DIRECTORY. Both retain the
`river server` aliases. Stop uses the existing listener-first shutdown and
portable filesystem ownership contract. Callers own their unpublished stages;
they join only published requests or acceptance receipts. Shared record parsing
now recognizes the checksum at a line boundary rather than inside a field name.
There are no engine, transaction, durability or benchmark workload changes.

Validation: full `./gradlew --no-daemon check` passed, followed by an incremental
`check :river-bench:installTps` after the final owner-exit test. Logs:
`/private/tmp/river-0803-check-12.log` and
`/private/tmp/river-0803-check-final-2.log`. The full run took 5m32s; an engine
worker inspected during an intentional lock-timeout test completed normally.
All builds used GraalVM 25.0.4 and isolated worktree Gradle caches.

Native build: `:river-server-app:nativeCompile
-PriverPgoProfile=/private/tmp/river-native-final.iprof`, O3, armv8.1-a,
unchanged Serial GC/1 GiB heap. Log:
`/private/tmp/river-0803-native-build.log`. The executable copied alone, with
Java removed from PATH, passed help aliases, two-instance listing, endpoint and
default stop, SQL commit, restart/read, and control-file cleanup. Driver:
`/private/tmp/river-0803-native-smoke.py`; logs:
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-0803-native-smoke-ogqq2np3/`.

Performance used the actual native foreground server because `tools/tps-test.sh`
starts its benchmark-specific server and would not exercise the new lifecycle
poll. Before edits, two short TPS controls were 148.500 and 146.900, version
`0803-stop-baseline-{1,2}`, standard tiny serializable mix, 10 terminals, one
warehouse, seed42, 2s warmup, 10s measurement; artifacts:
`/private/tmp/river-0803-baseline-{1,2}`. Both passed reconciliation and capture.

The native comparison used the unchanged acceptance workload: standard tiny
serializable mix, 10 terminals, one warehouse, seed42, batch32, maximum attempts32,
5s warmup and 60s measurement. Command:
`python3 /private/tmp/river-native-final-forensic.py --server=EXECUTABLE
--label=LABEL --mode=native --warmup=5 --duration=60`. Each artifact's `run.json`
records the executable and complete workload command.

| Run order | Label | TPS |
| --- | --- | ---: |
| Before implementation | river-0803-native-control-1 | 156.883 |
| Before implementation | river-0803-native-control-2 | 155.400 |
| Final executable | river-0803-native-candidate-1 | 143.383 |
| Adjacent stable recheck | river-0803-native-control-3 | 143.767 |

Artifacts are `/private/tmp/LABEL/`. All completed load, preflight, measurement,
drain and checkpoint, with no failed or exhausted transactions and successful
owned-server/database cleanup. The adjacent stable recheck reproduces the lower
throughput, so the earlier higher controls do not establish a code regression.
The adjacent pair shows no meaningful separation; this is local diagnostic
evidence, not a performance gain claim. Per the user's instruction to streamline
routine tickets, no duplicate JVM/native matrix or further samples were added.

Slopmark guided deletion of the initial foreign-stage adoption and duplicate
cleanup paths: Stop fell from 430.532 to 341.925. Existing Foreground changed
163.844 → 174.887 and RuntimeRecords 552.142 → 553.656. New Targets is 185.085,
Target 139.125 and StopRequest 0. Independent review covered ownership cleanup
and receipt retention; the final implementation keeps one lifecycle owner.

Decision: accept the local stop/list delivery. Linux/Windows native execution
remains unclaimed under the existing pre-alpha validation boundary. Next is
tic-b1a1: release.sh, GitHub Actions release assets and the Homebrew tap, using
NQL's existing approach.

## 2026-09-10 — atomic WAL sync (`tic-9f2c`)

Base: master `84e31c80`, tag `perf-checkpoint-20260910-mapped-wal`.
Branch: `ticket/tic-9f2c-wal-atomic-sync`; isolated worktree
`/private/tmp/river-wal-atomic-sync`. Baselines were captured before production
edits, using the existing built distribution in `/Users/blater/src/river`.

Command (each run serial, same GraalVM 25.0.4):

```sh
RIVER_JAVA=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --terminals=4 --warmup-seconds=2 --measured-seconds=10 \
  --version=tic-9f2c-baseline-N --output-dir=/private/tmp/wal-atomic-baseline-N
```

Baseline samples: **211.4**, **202.9** committed transactions/s. Both report
`status=OK` and `server_performance_capture_status=OK`; commands, raw telemetry,
correctness and cleanup results are retained in the artifact directories and
`/private/tmp/wal-atomic-baseline-{1,2}.log`. This is River-specific diagnostic
evidence, not an external harness or cross-database comparison.
Candidate samples with the same command and `candidate-N` labels: **206.6**,
**212.3** TPS. All four samples passed, with zero retries/errors and successful
deadlock reconciliation and performance capture. Candidate artifacts and logs
use `/private/tmp/wal-atomic-candidate-{1,2}` and the corresponding `.log` paths.
These short samples show no regression or clear TPS gain; the ranges overlap.

The clean `check :river-bench:installTps :river-server-app:nativeCompile` passed,
with `--no-daemon --no-build-cache`, GraalVM 25.0.4, O3 and the existing
`-PriverPgoProfile=/private/tmp/river-native-final.iprof`. Log:
`/private/tmp/wal-atomic-clean-check.log`. The real native executable committed
100 rows, survived SIGKILL/restart with every row and value intact, then passed
public stop and readiness cleanup. Evidence:
`/private/tmp/wal-atomic-validation/native-crash-smoke.log`.

The same instrumented one-worker INSERT probe used in the preceding elapsed
investigation observed **30,297 msync calls for 30,296 measured commits**, including
the final validation commit: one sync per commit instead of two. All 120,792
rows passed the final count check. Mean sync time was 105.221 us/call and
105.224 us/measured commit, versus the preceding 57.243 us/call and
114.488 us/commit. Halving the call count did not halve aggregate sync cost.
Instrumented throughput was 3,029.51 inserts/s versus 2,249.68 previously;
these are diagnostic probes, not repeatable throughput claims. Scripts, raw
trace, timer snapshots and summary:
`/private/tmp/wal-atomic-validation/insert-sync`. Both probes used 30s warmup and
10s measurement with the same method tracing and unchanged syscall timer.
All owned servers stopped and temporary database directories were removed.

Independent recovery review accepted force ownership, whole-group recovery,
suffix repair, error propagation, quorum history and the explicit distinction
between record end and next-record offset. Slopmark LocalWal 148.722 → 159.756
triggered a cohesion review: new methods remain in the WAL ownership boundary,
with reusable framing state in LocalWalCommitGroup and the old mapped-tail class
deleted. No second commit path was introduced. Scores:
`/private/tmp/wal-atomic-slopmark-{before,after}.txt`.

Decision: accept the one-sync implementation with no TPS speedup claim. WAL v3
replaces v2 directly and requires fresh database directories. Checkpoint:
`perf-checkpoint-20260910-atomic-wal-sync`.


## 2026-09-10 — single-pass INSERT (`tic-a73c`)

Base production `ad1db42f`; candidate `b66de835` on
`ticket/tic-a73c-insert-admission`. User-authorized deferred step 5 resumed.
GraalVM 25.0.4 JVM, four terminals, tiny standard mix, serializable, one warehouse,
seed42, unchanged durability/resources. Each command uses `tools/tps-test.sh
--terminals=4 --seed=42 --warmup-seconds=W --measured-seconds=D
--version=insert-a73c-LABEL --output-dir=/private/tmp/insert-step5/a73c-LABEL`.

| Order | Label | W/D seconds | TPS |
| --- | --- | --- | ---: |
| 1 | control-1 | 2/10 | 240.900 |
| 2 | control-2 | 2/10 | 240.300 |
| 3 | candidate-1 | 2/10 | 205.500 |
| 4 | candidate-2 | 2/10 | 199.400 |
| 5 | control-long-1 | 5/30 | 256.900 |
| 6 | candidate-long-1 | 5/30 | 262.567 |
| 7 | control-long-2 | 5/30 | 253.967 |
| 8 | candidate-long-2 | 5/30 | 264.933 |

All passed invariants, zero retries/errors, reconciliation, capture and cleanup.
The shorter directional drop prompted longer interleaved controls. It did not
persist; accept without a general speedup claim. Use the longer configuration
for the remaining candidates. Raw command/configuration and outcomes are in each
artifact; console logs `/private/tmp/insert-a73c-LABEL.log`.

Matched four-worker prepared INSERT probes: 15s warmup, 25s load, 20s wall capture;
7,523.17 → 8,026.01 inserts/s, final row counts passed. Visible descriptor INSERT
8.082 → 6.142 thread-seconds and published probes 3.750 → 2.434. Inclusive stacks
overlap and unmounted virtual waits are absent. Source, commands, raw profiles,
SVGs and cleanup: `/private/tmp/insert-step5/{profile.py,InsertProfile.java}` and
`a73c-{control,candidate}-profile/`.

Clean checkpoint log `a73c-clean-check.log` initially failed an unchanged tx
allocation assertion (392 bytes versus 256); isolated and full-suite reruns passed
unchanged, final `a73c-check-final.log` passed full check/installTps. Independent
review and slopmark details are in the ticket; BatchInsert score rose under
consolidation while SQL execution/batch state fell. No threshold was weakened.
Checkpoint: `perf-checkpoint-20260910-insert-admission`.


## 2026-09-10 — validated tuple lookup (`tic-2e91`)

Base `2d7833ff`, checkpoint `perf-checkpoint-20260910-insert-admission`;
candidate `3caa18d3` on `ticket/tic-2e91-unique-lookup`. Reused immediately
preceding accepted controls `a73c-candidate-long-{1,2}`: 262.567 / 264.933 TPS.
Same command/configuration as the preceding longer samples (warm5/duration30).
New labels use `insert-2e91-LABEL`, artifacts `/private/tmp/insert-step5/2e91-LABEL`.

| Subsequent order | Label | TPS |
| --- | --- | ---: |
| 1 | candidate-1 | 275.600 |
| 2 | candidate-2 | 278.033 |
| 3 | control-recheck | 264.400 |

All passed invariants, zero retries/errors, capture and cleanup. Clean full
check/installTps passed (`2e91-clean-check.log`). Independent lookup review and
slopmark evidence are in the ticket; cursor advance/open scores returned to their
baselines after simplifying the first draft.

Same four-worker INSERT workload: 8,026.01 → 8,722.66 inserts/s, final row counts
passed. Prefix comparison 1.133 → 0.071 accumulated thread-seconds; published
probes 2.434 → 1.059; descriptor INSERT 6.142 → 4.893. Source/commands, SVGs,
raw stacks and cleanup: `/private/tmp/insert-step5/2e91-candidate-profile/` and
the preceding `a73c-candidate-profile/`. These overlapping wall estimates omit
unmounted virtual waits; they are diagnostic evidence, not exact call timings.
Decision: accept with no sustained regression and the targeted search/decode work
removed. Checkpoint: `perf-checkpoint-20260910-unique-lookup`.


## 2026-09-10 — adaptive lock storage (`tic-8b64`)

Base `2a21dbcf`, checkpoint `perf-checkpoint-20260910-unique-lookup`; candidate
`df88f18c` on `ticket/tic-8b64-lock-storage`. Reused preceding accepted controls
`2e91-candidate-{1,2}`: 275.600 / 278.033 TPS. Same JVM and workload configuration
as above: four terminals, seed42, warm5/duration30, unchanged isolation/durability.
Command: `tools/tps-test.sh --terminals=4 --seed=42 --warmup-seconds=5
--measured-seconds=30 --version=insert-8b64-LABEL
--output-dir=/private/tmp/insert-step5/8b64-LABEL`.

| Subsequent order | Label | TPS |
| --- | --- | ---: |
| 1 | candidate-1 | 451.867 |
| 2 | candidate-2 | 452.833 |
| 3 | control-recheck | 277.333 |

All passed invariants, zero retries/errors, capture and cleanup. Clean full
check/installTps passed (`/private/tmp/insert-step5/8b64-clean-check.log`).
Slopmark stayed 0 → 0 for both touched production files; independent ownership,
allocation-failure and concurrency review is recorded in the ticket.

Four-worker INSERT improved 8,722.66 → 9,360.04 inserts/s; row counts passed.
Directory lookup self time fell 2.318 → 0.256 accumulated thread-seconds and lock
work 4.519 → 1.904. Raw stacks, SVGs and commands are in
`/private/tmp/insert-step5/{2e91,8b64}-candidate-profile/`. Overlapping wall groups
omit unmounted virtual-thread waits. The control recheck supports a repeatable
local improvement; this is not a native or cross-database throughput claim.

The final combined O3/PGO native build and indexed insert/duplicate rejection/
SIGKILL recovery/public stop smoke passed. Logs:
`/private/tmp/insert-step5/final-native-build.log` and `native-crash-smoke.log`;
temporary test sources alongside them. No native performance matrix was added.
Decision: accept. Checkpoint: `perf-checkpoint-20260910-lock-storage`.


## 2026-09-10 — INSERT storage decision and epic completion (`tic-4f20`, `tic-6d42`)

All three code tickets were accepted and promoted in order. The final bounded
investigation retains separate logical base rows and tuple indexes; no storage
format rewrite is supported by the remaining evidence. This documentation-only
decision changes no database behavior and needs no additional TPS matrix.

At the final code candidate `df88f18c`, the same four-worker INSERT probe with
one ordinary secondary index committed 216,680 rows at 8,667.09 inserts/s, versus
234,003 at 9,360.04 with only the primary key. Both final row checks and cleanup
passed. Warmup15s, load25s, wall profile20s; source/command and raw artifacts:
`/private/tmp/insert-step5/final-secondary-profile/` and `8b64-candidate-profile/`.
Tuple compilation was 2.123 → 3.238 accumulated thread-seconds, locks
1.904 → 2.990, sync 4.116 → 3.810. These overlapping estimates omit unmounted
virtual waits; different achieved row counts/tree growth prevent isolating the
primary mapping's cost. Logical mutations are not physical writes or syncs.

The ticket records row identity, secondary references and primary-key updates,
snapshot/rollback, recovery and split implications. Keep the current layout;
future replacement needs evidence of material removable cost. Independent source
assessment and integrator review completed this decision without another code
path, format migration or speculative framework. Epic and all four children are
closed. Final checkpoint: `perf-checkpoint-20260910-insert-efficiency`.


## 2026-09-10 — transaction-scoped descriptor bindings (`tic-5c21`)

Base `56f73a81`, checkpoint `perf-checkpoint-20260910-insert-efficiency-complete`;
candidate `2dcf5c22` on `ticket/tic-5c21-transaction-bindings`. One reusable,
budgeted session workspace retains published table bindings within an admitted
transaction. DDL/savepoint changes invalidate bindings; terminal outcomes release
pins; session close releases capacity. No SQL, isolation, durability or workload
changes. Independent review and focused ownership/DDL/pressure/allocation tests
passed. Clean full `check` and installTps passed in `clean-check.log` below.

Artifact root: `/private/tmp/river-tic-5c21/`. GraalVM25.0.4 JVM on the same
macOS26.5.2 arm64 host. No concurrent build or workload during measurements.

River-specific controls/candidates use `tools/tps-test.sh --terminals=4 --seed=42
--warmup-seconds=5 --measured-seconds=30 --version=LABEL --output-dir=PATH`.
Tiny standard mix, serializable, unchanged default resources/durability.

| Order | Artifact directory | Version label | TPS |
| --- | --- | --- | ---: |
| 1 | control-1 | master-56f73a81-bindings-control-1 | 450.367 |
| 2 | control-2 | master-56f73a81-bindings-control-2 | 416.500 |
| 3 | candidate-1 | tic-5c21-candidate-1 | 507.300 |
| 4 | candidate-2 | tic-5c21-candidate-2 | 423.667 |
| 5 | control-recheck | master-56f73a81-bindings-control-recheck | 349.900 |

All passed with zero errors and successful invariant, capture, reconciliation
and cleanup outcomes. Retries in table order were 1, 1, 0, 1, 0 and reconciled
with server outcomes. The falling unchanged control demonstrates
substantial variation; do not claim a precise TPS-test speedup. This prompted
interleaving the relevant full-mix workload below.

External harness command: `~/src/ingres/river-harness/benchmark run river tpcc
sample all --river-executable=EXE --river-version=LABEL --warmup=15s --duration=30s
--workers=4 --warehouses=1 --seed=42 --max-retries=20`. READ COMMITTED with explicit
FOR UPDATE locks, -Xmx1g. Control executable:
`/private/tmp/river-maria-20260910-final/river-jvm`; candidate:
`/private/tmp/river-tic-5c21/river-jvm`. Each launcher points to its checkout's
runnable distribution; neither builds. Labels are
`master-56f73a81-bindings-full-control-{1,2,recheck}` and
`tic-5c21-full-candidate-{1,2}`. Reports live under `river-harness/runs`.

| Run order / label | TPS | p99 ms | Retries | Report ID |
| --- | ---: | ---: | ---: | --- |
| full-control-1 | 307.178 | 55.050 | 1199 | `river_harness_20260910_140118_58419d83` |
| full-control-2 | 303.259 | 56.099 | 1199 | `river_harness_20260910_140210_ade5fdb1` |
| full-candidate-1 | 338.792 | 49.709 | 1312 | `river_harness_20260910_142605_0281c1ab` |
| full-control-recheck | 286.997 | 63.406 | 1182 | `river_harness_20260910_142657_002df16b` |
| full-candidate-2 | 332.524 | 52.724 | 1308 | `river_harness_20260910_142749_2aaa9513` |

Every report passed invariants, zero failed/unknown outcomes, graceful shutdown
and inactive final state. All are eligible with identical comparison key;
`full-summary.json` retains it. Candidate TPS and p99 improved both times, and
retry rates per commit remained similar. Accept a local diagnostic improvement
with no sustained regression; these short samples are not a general performance
claim or a native/MariaDB comparison.

The separate full-mix CPU/wall profile uses the same workload, warm15/load60,
with 20-second CPU then 20-second wall capture. Descriptor-resolution inclusive
CPU share fell 16.061% → 1.839%; name search 8.245% → 0.556%, catalog loading
7.347% → 1.069%. FK update-check share fell 7.659% → 2.481% through the shared
resolver. The new binding search is 0.171%. Wall shares agree (descriptor
resolution 16.235% → 1.345%). These percentages use request/commit-worker stacks,
overlap and omit unmounted virtual waits; they are not predicted TPS gains.

Profile artifact `river_harness_20260910_142931_16792550` passed correctness and
cleanup. Its instrumented 315.96 TPS is excluded from timing comparisons.
Baseline profiles: `/private/tmp/river-maria-20260910-final/`; candidate raw stacks,
SVGs, configuration, scripts and summary: `/private/tmp/river-tic-5c21/`.

Slopmark and independent review details are in the ticket. The wider test run
exposed idle helper sessions left open and a sole-owner assumption in a shared
budget test; callers now close and the pressure test consumes the actual shared
remainder. No test expectation about database correctness was weakened. The new
pressure test proves reservation failure leaves no caller pin and retry recovers.
The O3/PGO native build and actual indexed-INSERT/duplicate/SIGKILL recovery/
public-stop smoke passed (`native-build.log`, `native-smoke.log` in the artifact
root). All 100 acknowledged rows recovered; owned data and readiness state were
removed. Native throughput was not measured.
Checkpoint: `perf-checkpoint-20260910-transaction-bindings`.


## 2026-09-10 — share live prepared statements (`tic-7a32`)

Stable base `a4567c01`; feature `ticket/tic-7a32-shared-preparation`, implementation
`f90eabf9`. Exact SQL now shares one live session-owned plan across independent
handles. An immutable published preparation generation controls reuse; private DDL
never enters the index. Existing authorization and atomic schema admission remain
in the single validation path. No benchmark, SQL, transaction, isolation or
durability changes. Independent ownership/visibility review found no blocker.

Artifact root: `/private/tmp/river-tic-7a32/`. GraalVM25.0.4 JVM, `-Xmx1g`, same
macOS arm64 host. `clean check :river-bench:installTps` passed in 4m14s; focused
ownership, generation, private-DDL rollback, authorization, transaction-program
and retained-key pressure tests passed. Logs: `clean-check.log`,
`final-focused-tests.log`. Slopmark details and review are in the ticket.

External workload command:
`~/src/ingres/river-harness/benchmark run river tpcc sample all
--river-executable=EXE --river-version=LABEL --warmup=15s --duration=DURATION
--workers=4 --warehouses=1 --seed=42 --max-retries=20`.
READ COMMITTED with explicit FOR UPDATE, unchanged local durable WAL acknowledgement.
Control launcher: `/private/tmp/river-maria-20260910-final/river-jvm`; candidate:
`/private/tmp/river-tic-7a32/river-jvm`. No build, profiling or other owned workload
ran concurrently with a timed sample. Full configuration, versions, eligibility
keys and immutable report paths are in `samples.json`; individual logs retain the
short names below. Reports are under `~/src/ingres/river-harness/runs/`.

| Order / sample | Duration | TPS | p99 ms | Retries | Report ID |
| --- | ---: | ---: | ---: | ---: | --- |
| 1. control-1 | 30s | 313.033 | 52.855 | 1205 | `river_harness_20260910_170117_58030e31` |
| 2. control-2 | 30s | 338.371 | 50.561 | 1292 | `river_harness_20260910_170208_7f915b13` |
| 3. candidate-1 | 30s | 377.231 | 43.647 | 1418 | `river_harness_20260910_172259_d65da7f1` |
| 4. candidate-2 | 30s | 377.604 | 43.647 | 1386 | `river_harness_20260910_172351_19a2110d` |
| 5. long-control | 60s | 371.733 | 44.237 | 2864 | `river_harness_20260910_172506_1e2bd23b` |
| 6. long-candidate | 60s | 389.817 | 41.878 | 2891 | `river_harness_20260910_172629_c5323521` |

Short labels: `master-a4567c01-preparation-control-{1,2}` and
`tic-7a32-f90eabf9-shared-preparation-jvm-{1,2}`. Longer labels:
`master-a4567c01-preparation-long-control` and
`tic-7a32-f90eabf9-preparation-long-candidate`.

All samples passed warmup and measurement with zero failed/unknown outcomes,
successful invariants, graceful stop and inactive final state. Comparison keys
match within each duration; do not pool the 30s and 60s groups. Short candidate
mean was 15.9% above controls, but the adjacent longer pair was only 4.9% higher.
The duration/host variation rules out a precise general speedup claim. Candidate
p99 and retries per commit improved in both groups, with no repeated regression.
These are local JVM diagnostics, not native or cross-DBMS performance claims.


The repeated full-mix CPU/wall profile used warm15/load60, then 20s CPU at 10ms
and 20s wall at 1ms (`--total --nobatch`). Baseline at the same stable source:
`/private/tmp/river-tic-5c21/{cpu,wall}.collapsed`. Candidate raw stacks, request/
commit SVGs, configuration and summary are in the current artifact root.
PREPARE inclusive CPU share fell 16.895% → 3.195%; wall share fell
16.583% → 3.659%. Template capture had no candidate samples. Percentages use
request/commit-worker stacks, overlap, and omit unmounted virtual waits; they are
not method-duration measurements or predicted TPS gains. Profile report
`river_harness_20260910_172847_359b3c1a` passed all correctness and cleanup checks.
Its instrumented 357.20 TPS is excluded from the timing comparison.

Accept the bounded repetition removal: tests and the profile establish the
mechanism, and matched timing groups show improvement without a repeated
regression. Socket write self time remains prominent (17.14% of selected CPU);
FK discovery and execution workspace reset also remain. These observations do not
expand this ticket. The prepared-store refactor also removes the redundant second
handle lookup when resolving a plan without a requested query kind.

The approved O3/PGO standalone build passed in 1m56s (`native-build.log`), using
`-PriverPgoProfile=/private/tmp/river-native-final.iprof`. The resulting executable
passed `sample all --warmup=1s --duration=3s --workers=1 --warehouses=1 --seed=42
--max-retries=3 --no-report`, including authenticated lifecycle, post-run
validation and public shutdown (`native-smoke.log`). This smoke is functionality
evidence only. Checkpoint: `perf-checkpoint-20260910-shared-preparation`.
