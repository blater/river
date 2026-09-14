---
id: tic-osgiliath
status: in_progress
branch: ticket/tic-osgiliath-checkpoint-exit
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
