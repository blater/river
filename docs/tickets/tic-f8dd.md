---
id: tic-f8dd
status: closed
type: bug
priority: 1
assignee: blater
delivery: code
base-commit: e6e17b1fd7dbc0433e64c01b2918e9075cc25858
branch: ticket/tic-f8dd-order-line-corruption
delivered-commit: 0cf9970f5371f520b6a9639424b4446d9e4c3412
checkpoint-tag: perf-checkpoint-20260906-directory-cache-reload
evidence:
    - /private/tmp/river-tic-f8dd-evidence
tags:
    - correctness
    - storage
    - tpcc
created: 2026-09-06T17:38:02.569133Z
---
# Fix metadata-directory frame reload corruption

Unchanged master 4ee882b produced checkpoint_failed/CORRUPTION after a 30-second standard tiny TPC-C run; measured retries/errors were zero. Failure occurred querying SELECT COUNT(*) FROM order_line in TpccInvariants.verifyBusiness, and database shutdown returned CORRUPTION. This blocks performance acceptance.

## Design

Retain failed evidence /private/tmp/river-commit-force-opportunity-20260906/control-long-3. Runtime was OpenJDK 26.0.2.1, seed42, serializable,10terminals,1warehouse,warmup5s,measured30s; custom timing-event-only JFR enabled but production source had no probes. Reproduce and identify the first storage invariant failure before selecting a fix. Do not weaken checkpoint validation or attribute failure to JDK/background load without evidence. Related investigation tic-f539.

## Acceptance Criteria

Identify root cause and add a focused deterministic regression test; prove valid scan/checkpoint and cleanup through the real path; independent storage/recovery review; passing affected-module tests and repeated unchanged-workload samples with complete invariants.


## Root cause and fix

Both disk-directory caches reused ByteBuffers without resetting the position
before reads. A completed write or full read leaves position at the limit.
Zeroing with absolute puts does not move it. NioDurableFile therefore sees no
remaining bytes and returns OK with zero bytes transferred; the loader admits
zeroed metadata as a valid frame. A failed partial read similarly leaves a
nonzero position, so a retry can shift persisted fields within the buffer.
Version lookup then reports unavailable metadata and can reach CORRUPTION or
incorrectly fall back to checkpoint metadata.

The unchanged 30-second reproducer failed at the same post-run order_line count
as the original report. A temporary bounded status probe in a 60-second run
located the first observed CORRUPTION at IndexedKernelVisibility.resolve,
immediately after version lookup. The retained version file was 5.1 MiB,
exceeding the version cache's 64 frames / 4 MiB. The deterministic regressions
prove the same reload defect with real NIO files under GraalVM 25; the workload
reproduces under OpenJDK 26, so this is not attributed to JVM choice.

The fix clears each frame buffer immediately before file.read in
IndexedVersionDirectory and IndexedRowDirectory. Row-directory read misses now
use the same bounded LRU/writeback selection as write misses; previously reads
could return RESOURCE_EXHAUSTED once all 64 frames were occupied. The private
read/create eviction boolean and all its callers were removed. No cache growth,
new buffer, extra row copy, WAL force policy, file format, lock rule, SQL,
protocol, retry or workload change was introduced. Temporary status tracing is
fully removed. Four build-policy descriptors follow the revised private method
signatures while preserving their existing checks and cold-allocation allowance.

Implementation: `f790f9975eb366eff86aad5b36807f4b641307ac`.
Policy signatures: `e9af239`.
Checkpoint: `perf-checkpoint-20260906-directory-cache-reload`.
Evidence: `/private/tmp/river-tic-f8dd-evidence`.

## Deterministic proof and independent review

IndexedDiskDirectoryEvictionTest uses real disk-backed files with one record in
each of 65 pages, exceeding either cache's 64 frames. Dirty-victim and cold clean
reload cases verify exact version sequence, predecessor, deletion/vacuum flags
and row page/slot through repeated forward/backward passes. Growth checks verify
zero EOF tails. Injected partial failed reads verify that retries reload complete
fields rather than admit partial or shifted metadata.

All six invocations failed before the fix and pass afterward; retained XML
records the failures. Existing IndexedDiskDirectoryCapacityTest and
IndexedSidecarStatusTest also pass. Independent storage/recovery review confirmed
the cause, the two-owner fix, failure invalidation, bounded eviction and tests.
A separate final check confirmed the policy descriptor changes do not remove
coverage or broaden allowances. No page-generation cache change was needed.

## Test and policy gates

With GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f8dd:

```sh
./gradlew :river-engine:test \
  --tests io.riverdb.engine.table.IndexedDiskDirectoryEvictionTest \
  --tests io.riverdb.engine.table.IndexedDiskDirectoryCapacityTest \
  --tests io.riverdb.engine.table.IndexedSidecarStatusTest --no-fail-fast
./gradlew clean test
```

The final clean full checkpoint passes **1,781 tests, zero failures, two skips**,
including all **1,000 engine tests**. The first clean run completed the engine
suite successfully but hit the previously observed LockExactAllocationTest
assertion: 7,088 bytes versus 512. That unrelated test passed on unchanged master
and on two subsequent unchanged-production clean checkpoints. No limit or test
was weakened; the original failure is retained. Successful tasks were restored
from Gradle's cache during the repeat clean runs.

verifySourcePolicy and verifyHotPathBytecode still fail with **exactly the same
261 existing violations** on control and final candidate, zero added/removed.
Intermediate missing-selector failures led to updating the four owned policy
descriptors. verifyIndexedTableClassReferences passes. The existing unrelated
policy backlog is not represented as a passing gate.

Slopmark: IndexedRowDirectory **40.5049 -> 40.4243**;
IndexedVersionDirectory **28.4651 -> 28.4651**. No steady-state allocation or
copy is added; existing cold-frame allocation and bounds are retained.

## TPS and real-path evidence

The user's existing constant background load remained running. No other River
build or workload overlapped a sample. These are River-specific diagnostic
workloads, not exclusive-host or cross-database performance claims.
Every run explicitly pins:

```sh
RIVER_JAVA=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=<evidence>/<sample>
```

Both untouched short baselines preceded diagnostic source edits. Fixed defaults:
tiny/standard, serializable, no-wait stress, ten terminals, one warehouse,
seed 42, 32 maximum attempts and normal synchronous WAL durability.
Long runs use 5-second warmup, the listed duration, and the original bounded
recording configuration on both the reproducer and fixed candidate:

```text
--server-java-option=-XX:StartFlightRecording=settings=/private/tmp/river-commit-force-opportunity-20260906/opportunity.jfc,filename=<evidence>/<sample>.jfr,dumponexit=true,maxsize=64m
```

| Sample | Measured | TPS | Outcome |
| --- | ---: | ---: | --- |
| baseline-1 | 10s | 153.400 | passed |
| baseline-2 | 10s | 161.500 | passed |
| reproduce-1, unchanged | 30s | unavailable | CORRUPTION during post-run count and shutdown |
| origin-probe-1, temporary tracing | 30s | 175.333 | passed; diagnostic only |
| origin-probe-2, temporary tracing | 60s | unavailable | CORRUPTION, first propagation through version lookup |
| candidate-1 | 10s | 160.100 | passed |
| candidate-2 | 10s | 156.800 | passed |
| candidate-long-1 | 30s | 181.533 | passed |
| candidate-long-2 | 30s | 175.733 | passed |
| candidate-long-3 | 60s | 153.767 | passed |

All successful final samples have zero measured retries/errors, passing complete
invariants, performance capture, deadlock reconciliation, terminal cleanup and
successful receipts. Launcher paths/hashes and clean, stable source identities
were checked; the short pair has identical configuration fingerprints. The first
long candidate predates only the policy-selector updates; production code is
identical to the final candidate. Full diagnostic databases and stack traces are
retained at paths listed in retained-databases.txt in the evidence root.

The short pair stays inside the observed baseline range. The longer 60-second
sample is retained in full; its different duration and invalid pre-fix control
provide no basis for a TPS gain or regression claim. This is accepted for
correctness, with no speedup claim. Successful real-path scans and checkpoint /
shutdown after cache eviction, together with the deterministic red/green tests,
resolve the specific corruption stop raised by tic-f539.

## Delivery

Merged and pushed at `0cf9970f5371f520b6a9639424b4446d9e4c3412`. The feature
branch tip `7d6cd7aa9ef80fbfa8c99fdc594651c5e6133188` and annotated
`perf-checkpoint-20260906-directory-cache-reload` tag were pushed atomically.
The exact integration revision passed a 1-second-warmup / 3-second end-to-end
smoke with valid invariants, cleanup and receipt, zero retries/errors
(140.667 TPS; lifecycle evidence only, not a comparative sample). The specific
corruption blocker is resolved and this ticket is closed.
