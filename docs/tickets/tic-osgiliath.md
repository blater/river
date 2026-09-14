---
id: tic-osgiliath
status: in_progress
branch: ticket/tic-osgiliath-pending-write
type: bug
priority: 1
assignee: blater
parent: tic-rowlie
delivery: code
tags:
    - performance
    - recovery
    - platform
links:
    - tic-emeldir
created: 2026-09-13T12:15:36.613653Z
---
# Diagnose current-master TPS checkpoint disconnect and unreaped server exit

On unchanged ef935596, the second serializable TPS control measured/drained then CHECKPOINT returned JDBC IO_FAILURE; the owned GraalVM25 server entered Darwin exiting state and has not reaped after the harness cleanup escalation. Preserve the first passed sample and failed second sample; distinguish checkpoint database failure from process/external termination before changing code. Never certify this failed sample or cleanup as passed.

### Current triage evidence

On resumption no River/TPS process remains. Original temporary runner/server
logs are unavailable, so the earlier report is retained as an observation,
not proof of a database checkpoint defect. Client EOF/transport IOException
also maps to IO_FAILURE; the phase marker precedes business-invariant queries.
A system report exists at
/Library/Logs/DiagnosticReports/panic-full-2026-09-13-131928.0002.panic:
watchdog timeout, no watchdogd checkins for92seconds, panicked kernel_task,
OS25G83. This confirms a host failure, not River causation.

A fresh unchanged-source single-worker TPS smoke passed measured workload,
checkpoint, recovery and cleanup; durable evidence is
/Users/blater/src/river-performance-evidence/20260913-da4e/checkpoint-smoke/.
That smoke did not establish host safety: a subsequent unchanged-source control
again failed at checkpoint and server termination. That historical run did not justify another reproduction. Do not implement a
guessed checkpoint fix or count the
failed samples as accepted performance evidence.

### Retained recurrence evidence and next boundary

The later capture is retained at
/Users/blater/src/river-performance-evidence/20260913-da4e/checkpoint-failure-capture/.
Its client stack identifies `TpccRunPhase.checkpointAndIdentify` executing
`CHECKPOINT`; load, preflight, warmup, measured work and drain had completed.
JFR was disabled. The process snapshot records server PID 1697 in Darwin `?E`
state, still present after termination escalation. The later panic report is
/Library/Logs/DiagnosticReports/panic-full-2026-09-13-165327.0002.panic.
The watchdog reports do not identify the exact filesystem lock or prove which
River operation caused the kernel failure. The user reports a seven-minute
kernel lock hold blocking launchd; preserve that observation without presenting
an unverified lock attribution as a source finding.

Continue static inspection of mapped-file force, unmap, truncate and close
ordering and independent review. Any runtime validation requires a
user-designated isolated disposable host. Never reproduce the hang on this Mac,
and never infer that a userspace timeout or SIGKILL releases kernel resources.
tic-treebeard retains shutdown ownership corrections; tic-nimloth retains only
rollback/retry correctness. Neither establishes the kernel cause.

### Current authorization and findings

The user subsequently explicitly reauthorized Java builds and TPS, then withdrew
the 15-second policy on 2026-09-14. Historical guarded builds, mapped-file lifecycle and checkpoint
recovery tests passed. Short four-worker TPS passed checkpoint and cleanup;
the original longer failure is not declared fixed.

Detailed inspection of `processByPid` and `waitInfo` in both saved panics now
confirms the same Java worker owned the kernel read/write lock blocking launchd
for over seven minutes. Kernel UUID matching and offline symbolication identify
the native `VNOP_WRITE` / `cluster_write` path. The earlier inspection of
`panicString` alone missed this evidence. Exact file/offset and the initiating
Java write frame still need identification; do not infer an mmap/unmap cause.
The reproducible evidence and current decisions are recorded in
[the investigation](../plans/checkpoint-kernel-hang-20260913.md).


### Source trace, 2026-09-14

CHECKPOINT flushes live pages and writes an immutable positional page file before
WAL rotation/control installation. NioDurableFile's positional writes and file
extension write are candidates for the native write stack. Independent review
found no proven mapped-lifetime race. The retained process sampler failed after
the process entered exiting state, so the mounted Java frame and target file
remain unknown. The existing investigation now records the source chain and
precise missing evidence. No runtime reproduction was performed.

### WAL resumption evidence disposition, 2026-09-14

Independent review exhausted the retained panic/sampler/server-log evidence
without finding a mounted Java frame or target file/offset. The four traced
checkpoint/write owners are unchanged from `ef935596` at current `5dcee338`.
The [retained-data review](../plans/checkpoint-kernel-hang-20260913.md#retained-evidence-review-during-wal-resumption-2026-09-14)
records the precise limit. Keep this bug open: successful P0 checkpoints cannot
prove the original cause or a fix. No new production change, runtime probe or
instrumentation is justified by the available evidence.


### September 14 incident evidence

New user-requested [tic-emeldir](tic-emeldir.md) embeds the latest full failure
console, exact command and implicated kernel records, with a complete raw evidence
attachment. The 14:08:48 panic identifies server57024/thread798645 holding the
lock blocking launchd; all18 normalized frames match both earlier incidents.
This confirms recurrence, not the initiating Java write/file or a repair. The
60-second candidate failed CHECKPOINT/cleanup and is not accepted performance
evidence. Root-cause/fix ownership remains here; emeldir owns this incident's
missing-write evidence investigation. No reproduction was run for ticket creation.

### Checkpoint failure/exit work, 2026-09-14

User explicitly requested reproduction and clean exit after IO_FAILURE. Claimed by
root integrator on base d97b1084, branch `ticket/tic-osgiliath-checkpoint-exit`,
worktree `/private/tmp/river-checkpoint-exit`. Scope: controlled returned-I/O-error
and blocked-checkpoint shutdown tests, fixes demonstrated by those tests, and
identifying evidence needed for the initiating kernel write. No performance
feature changes. Kernel-hang reproduction requires an isolated environment;
ordinary fault-injection tests do not establish that the host panic is fixed.

### Accepted failure-handling slice

Checkpoint: `checkpoint-20260914-io-failure-reproducer`, correctness/diagnostics
only. The incident remains in progress; the native write that initiated the
macOS stall is still unknown.

- Controlled general JDBC CHECKPOINT tests reproduce both a returned page-write
  IO_FAILURE and a missing response while a write is deliberately held. They
  use real authenticated server instances in child JVMs, require normal exit,
  restart in the parent only after the original owner exits, and verify the
  committed row. No TPC-C constructs are required.
- The held-write capture showed a second TLS read during failed-transport close:
  IO_FAILURE appeared at 60,010 ms. Failure-only input shutdown removes that
  duplicate wait; the same scenario then reported IO_FAILURE at 30,009 ms before
  the write was released. This is not a universal socket-close time bound.
- The TPS server now stops connection workers before JFR/metrics capture. Tests
  prove socket cancellation precedes a held transaction-manager monitor and
  instance cleanup still happens after a JFR dump failure.
- CHECKPOINT error diagnostics reuse the connection's completed-request counter:
  one response means a received server error, zero means no completed response,
  other deltas are ambiguous. The original SQLException is preserved.

Validation on GraalVM Java 25.0.4 / macOS 26.6.2: 20 client tests, 75 server-app
tests, and six selected benchmark tests passed (101 total, zero skips/failures),
plus source and module policies. The final test-cleanup adjustment passed the
same two child-process tests again (30,009 ms held-case error visibility). No TPS sample or host-crash workload was run.
Slopmark: client connection 87.9892 → 87.4761; TPS server 75.118 unchanged;
run phase 0 unchanged. Independent execution_admission_review approved the
source, ownership, and test boundaries.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/river-checkpoint-gradle \
./gradlew --no-daemon --offline --project-cache-dir /private/tmp/river-checkpoint-cache \
  :river-client:test :river-server-app:test :river-bench:test \
  --tests io.riverdb.bench.tpcc.TpccRunPhaseTest \
  --tests io.riverdb.bench.tpcc.TpccServerMainShutdownTest \
  --tests io.riverdb.bench.tpcc.TpccMetricsTest \
  verifySourcePolicy verifyModuleGraph
```

The standalone reproduction is also runnable with just
`:river-server-app:test --tests io.riverdb.server.app.RiverDaemonCheckpointJdbcTest`
using the same Gradle invocation prefix. Baseline XML with the 35-second live
stack, final validation logs, and final reproducer XML are retained under
`/Users/blater/src/river/benchmark-results/checkpoint-20260914/`. The
[source investigation](../plans/checkpoint-kernel-hang-20260913.md#controlled-live-stack-result)
embeds the relevant stack and causal limits.

| Outcome | Status |
| --- | --- |
| Returned IO_FAILURE reproduction, clean exit after provider recovery, committed-data restart | Verified |
| Held-write client timeout, removal of second TLS wait, clean exit/recovery after release | Verified |
| Shutdown-before-metrics ordering and diagnostic-failure cleanup | Fixed and tested |
| Actual kernel-stall reproduction | Prepared for later isolated execution at the user's direction; [procedure](../plans/checkpoint-crash-reproduction.md) |
| Initiating kernel write and clean termination while kernel I/O never returns | Unresolved; no userspace termination guarantee |
| Persistent storage failure with no recovery of the provider | Close remains unsuccessful with ownership retained; no dirty-state discard contract added |
| WAL acceptance and P0 scaling/accounting | No new acceptance; existing deferrals remain |

### Capture rehearsal and plan iteration, 2026-09-14

User authorized controlled step 1 and independent plan review. Claimed by root on
base `e2642598`, branch `ticket/tic-osgiliath-capture-rehearsal`, worktree
`/private/tmp/river-checkpoint-exit`. No production/test source changes.

Two real held-write test runs passed normal child exit and parent recovery. The
first process-start capture was too early; the second attempt used an observed
checkpoint stack and captured Java/native/open-file/JFR evidence during the hold.
The second test reported IO_FAILURE at 30,004 ms. The independent reviewer
confirmed the result and attribution limits.

The [revised plan](../plans/checkpoint-crash-reproduction.md#rehearsal-evidence-and-revised-order)
records collector results and the new prerequisite: identify file/position/count
while the operation is still pending. JFR had no checkpoint latch park or write
offset, lsof was inventory only, and fs_usage lacked noninteractive administrator
authentication. Do not proceed to the kernel workload with those gaps. The next
bounded implementation is proposed, not implemented, and awaits authorization
for that next step: an in-flight observation at the existing file owner, with a
controlled gate after publication and before native I/O. The unchanged outer
fixture gate cannot validate that observation. No tracing framework is added.
Actual host-crash execution remains deferred and the bug remains in progress.

Independent review required and verified the downstream-gate clarification and
the distinction between completed rehearsal and proposed implementation. Delivery
tag: `checkpoint-20260914-capture-rehearsal` (evidence only).

### Pending-write observation claim, 2026-09-14

User authorized taking the review feedback forward. Root integrator owns this
bounded diagnostic slice on stable base `f007b0f8`, branch
`ticket/tic-osgiliath-pending-write`, worktree `/private/tmp/river-checkpoint-exit`.
The platform owner will publish exact pending positional-write arguments before
the provider call; a downstream controlled gate and independent review will
validate observation and cleanup. Concurrent writes must remain unconstrained,
with explicit sampled coverage limits. No new tracing framework, performance
feature, or kernel-crash workload is included. The actual isolated run remains
deferred.

### Accepted pending-write observation

Delivery checkpoint: `checkpoint-20260914-pending-write-observation`.
Independent `execution_admission_review` approved production concurrency/lifetime,
the corrected downstream tests, and independently decoded the retained live JFR.
The incident remains in progress; this is diagnostic capability, not a kernel
repair, WAL optimization acceptance, or throughput evidence.

- Opt-in `river.diagnostics.pendingFileWrites` publishes one immutable pending
  invocation per handle at both existing positional-write sites, including file
  extension. `river.PendingFileWrite` reports exact offset/count, Java writer and
  monotonic start, plus handle identity and explicit current/cumulative overlap
  gaps. Defaults are off; enabled diagnostics allocate and alter timing.
- Three controlled tests passed. External jcmd captured JVM 6220 while downstream
  gates remained held: handle 2 at offset 128/count 4, virtual writer 45, one
  uncaptured overlap; same-path handle 3 at offset 512/count 5, writer 48.
  The periodic emitting thread was Java 38, distinct from the writers.
- Tests verify uncaptured-only pending state after the sampled write completes,
  resize offset/count, returned IO_FAILURE clearing, disabled handles, direct
  close and directory-owned close, and actual JFR callback removal after drain.
- Final affected validation passed 26 platform tests and both existing controlled
  JDBC checkpoint/exit/recovery tests; 16 existing Linux/Windows-only tests skipped
  on macOS. JDBC held-case error visibility was 30,008 ms. Source/module policies
  passed. The three focused tests also passed separately. No host-crash or TPS
  workload was run.
- Slopmark: NioDurableDirectory 77.2063 and NioDurableFile 42.98 unchanged;
  new diagnostics owner 0. The shared write helper preserves original I/O/status
  handling, and the sampler acquires no file/directory/transaction lock.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/river-checkpoint-gradle \
./gradlew --no-daemon --offline --project-cache-dir /private/tmp/river-checkpoint-cache \
  :river-platform:test :river-server-app:test \
  --tests io.riverdb.server.app.RiverDaemonCheckpointJdbcTest \
  verifySourcePolicy verifyModuleGraph
```

The focused invocation replaces the task suffix with `:river-platform:test
--tests io.riverdb.platform.file.nio.NioPendingFileWriteTest`. Source base is
`f007b0f8`; final feature source is retained by this ticket branch and checkpoint.
Exact capture fields, causal limits and revised next steps are in the
[reproduction plan](../plans/checkpoint-crash-reproduction.md#downstream-gate-validation-2026-09-14).
Raw live JFR, decoded JSON, jcmd output, commands, XML and validation logs:
`/Users/blater/src/river/benchmark-results/checkpoint-pending-write-20260914/`.

| Outcome | Disposition |
| --- | --- |
| Sampled pending Java write arguments, truthful overlap gaps, bounded lifecycle | Implemented, tested and independently accepted |
| Actual kernel entry, OS thread/descriptor association and kernel root cause | Unresolved; pending record alone cannot establish them |
| Filesystem/native capture readiness | Requires working administrator tracing on the disposable host; prior local fs_usage authentication gap remains |
| Actual crash workload | Deferred for later isolated execution; not run here |
| WAL feature acceptance and P0 scaling/accounting | Unchanged; existing P0 deferrals remain |

### Single call-site hypothesis preflight, 2026-09-14

The focused [next-incident plan](../plans/checkpoint-stall-single-hypothesis.md)
asks whether the identified stalled native write is the one-byte
`NioDurableFile.resize` growth call or the ordinary positional-write call site.
The existing pending-write event now declares `POSITIONAL_WRITE` or
`RESIZE_GROWTH`; three focused tests pass and preserve the existing overlap,
retirement, failure and disabled-mode behavior. Slopmark remained 42.98 for
`NioDurableFile` and 0 for `PendingFileWriteDiagnostics`.

The mandatory native-attribution preflight then ran one 15-second authenticated
JDBC writer through the real `river-connection-0` virtual-thread path: 176
CHECKPOINTs, 5,632 inserted rows, a completed 12-second `/usr/bin/sample`, and a
completed JFR. Full-depth JFR found 15 actual `pwrite0` chains through
`NioDurableFile.write` and checkpoint frames on virtual Java thread 49, but JFR
reported OS thread ID 0. The native sample showed carrier-thread `pwrite0`
frames but unresolved JIT frames, so it could not join a carrier OS thread to
Java thread 49 and the River call site. Pending events recorded seven active
`POSITIONAL_WRITE` observations and no active `RESIZE_GROWTH` observation;
absence does not establish whether resize executed.

The mandatory gate therefore failed and the hypothesis remains undecided. Per
plan, no incident workload, speculative native instrumentation, provider change
or host-security change followed. Raw evidence, including the retained scratch
driver and a separately labelled initial transaction-mode setup failure, is in
`/Users/blater/src/river/benchmark-results/checkpoint-20260914/operation-kind-preflight/`.
The focused three-test diagnostic check and the final affected validation both
passed; the latter ran 42 platform tests with 16 existing platform skips and no
failures, followed by `verifySourcePolicy` and `verifyModuleGraph`.
The larger uncommitted capture draft in `/private/tmp/river-checkpoint-exit`
remains paused and was not adopted or changed.
