---
id: tic-emeldir
status: open
type: bug
priority: 1
assignee: blater
parent: tic-rowlie
delivery: evidence
tags:
    - recovery
    - platform
    - crash
    - wal
links:
    - tic-osgiliath
created: 2026-09-14T15:00:56.498699Z
---

# Capture September 14 CHECKPOINT IO_FAILURE and kernel watchdog crash

The final 60-second WAL-admission sample completed its measured workload and drain,
then the actual SQL `CHECKPOINT` call reported JDBC `IO_FAILURE`. Cleanup attempted
SIGKILL against its owned server PID 57024, could not reap it, and retained failure
logs. The subsequent macOS panic identifies a thread of that same server as the
owner of a kernel read/write lock blocking launchd. Its 18 normalized kernel frames
match both September 13 crashes exactly.

Status: open. This ticket owns the September 14 incident evidence and identification
of the initiating write. [tic-osgiliath](tic-osgiliath.md) remains the existing
root-cause/fix owner; this is not a second implementation stream. No speculative
checkpoint, mapped-file, timeout or process-tree implementation is authorized by
this evidence ticket. No claim that the crash is fixed or that the failed sample
qualifies as performance evidence.

## Findings and uncertainty

| Layer | Confirmed finding | Not established |
| --- | --- | --- |
| Workload trigger | Fresh load; successful load checkpoint/invariants; preflight probe; 2-second warmup; 60 seconds tiny/standard serializable work with four terminals; drain; then SQL CHECKPOINT. | A deterministic or minimal reproducer; a 60-second threshold; exact data/page layout needed. |
| Immediate client failure | `TpccRunPhase.checkpointAndIdentify:64` is the actual `statement.executeUpdate("CHECKPOINT")` call. Business-invariant verification precedes that call and returned normally; the combined success marker after CHECKPOINT was never printed. | Whether IO_FAILURE originated as a server storage status or a client socket/EOF/IOException. The JDBC wrapper discards that distinction in this stack. |
| Process cleanup | Individual server PID kill attempt; runner handled separately. Retained observation: PID57024, PPID1, Darwin `?E` after SIGKILL. | Original shell parent PID, full pre-failure process tree, signal delivery success, or exact first-stall/signal ordering. |
| Proximate panic trigger | September14 14:08:48+0100 watchdog panic: no watchdogd checkins for94 seconds. | Why watchdogd stopped making progress in every detail. |
| Kernel obstruction | launchd thread800866 waits to read kernel rwlock0x5ee9b380d4e609cd, owned by Java57024 thread798645. Java thread is uninterruptibly waiting in a terminated snapshot; native stack exactly matches the earlier write/VM-path incidents. | Initiating mounted Java frame, target file/vnode/fd, offset/length, or precise kernel defect. No proof that mmap, mapped force, unmap, truncate, SIGKILL, or the new admission code initiated it. |

**Root trigger remains unknown.** The strongest established mechanism is the
server's native filesystem-write/VM wait retaining the lock needed by launchd.
The same kernel UUID and frame sequence connect this incident to the earlier
symbolicated `VNOP_WRITE` / `cluster_write` / `cluster_write_ext` path. This is more
than an IO_FAILURE correlation, but it does not identify the particular River
write or prove a specific XNU/APFS defect. The signature predates `79c4da2e`.

## What cleanup actually targeted

At the incident source, `tools/tps-test.sh` launches both Java programs as direct
Bash children with `$!`: server `TpccServerMain` (PID57024) and a separate
`TpccAcceptanceMain` runner. There is no process-tree enumeration, process-group
kill, negative-PID kill, or parent-kill operation in that script.

The server path first requests graceful stop through its `server.stop` marker,
then falls back to this exact statement:

```sh
kill -KILL "$server_pid" 2>/dev/null || true
```

The separate runner path, if still present, attempts `kill "$runner_pid"` then
`kill -KILL "$runner_pid"`. The failure log identifies only the server as
unresponsive. It does not prove that the runner needed a signal. The supervisor
uses a shared 20-second stop deadline, polls Bash job state, and calls `wait` only
for a reapable child. Signal return values are suppressed, so the log proves an
attempt and failed reaping, not successful SIGKILL delivery. Java threads are
within that one process; they are not descendant processes. PPID1 in the retained
post-failure note means launchd, not a parent to target for cleanup. The later
panic's terminated snapshot corroborates incomplete kernel-side termination.
Do not infer that killing an ancestor or process group would release this lock.

Source anchors (unchanged by the feature): `tools/tps-test.sh:442-568,650-705`;
`river-client/.../RiverClientWireExchange.java:68` maps IOException to IO_FAILURE;
`RiverClientResponseReader.java:55,66` maps incomplete response reads likewise;
`river-jdbc/.../RiverJdbcStatement.java:411` propagates the status as SQLException.

## Exact observed recipe — evidence, not a validated deterministic reproducer

Source commit: `79c4da2ef5fb5f38c712b1bb48e7121c3ff1fd04` on
`ticket/tic-5b3e-cohort-admission`; base `39eb104c`. The feature was committed locally,
but not accepted/merged/tagged/pushed. Master remained `d975174e` (ticket config
change only after39eb104c). No build or other benchmark overlapped this run.

The benchmark distribution had been built in the feature worktree with:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/river-b1b7-gradle \
./gradlew --no-daemon --project-cache-dir /private/tmp/river-wal-5b3e-cache \
  clean test verifySourcePolicy verifyModuleGraph :river-bench:installTps
```

This reported 1,977 tests: 1,959 passed, 18 skipped, zero failures/errors; full log and XML
archive are attached. The preceding failed clean attempt was an adjacent test
still expecting RESOURCE_EXHAUSTED and implicit batch cancellation; the migrated
test and final gate passed. It is not the host crash.

The exact failing invocation, from `/private/tmp/river-wal-5b3e`, was:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
tools/tps-test.sh --version=wal-admission-79c4da2e --profile=tiny \
  --mix=standard --terminals=4 --scheduling=no-wait-stress \
  --evidence=diagnostic --fresh-load=true --warehouses=1 --batch-rows=32 \
  --maximum-attempts=32 --warmup-seconds=2 --measured-seconds=60 \
  --seed=42 --isolation=serializable --sample-id=admission-resolution \
  --output-dir=/Users/blater/src/river/benchmark-results/wal-20260914/admission-resolution \
  > /Users/blater/src/river/benchmark-results/wal-20260914/admission-resolution.console.log 2>&1
```

Environment: GraalVM Java25.0.4, Darwin25.6.0 arm64, macOS26.6.2 build25G83,
Mac17,3. Server port61306, authenticated local JDBC. JFR disabled. Detailed
server deadlock diagnostics disabled (budget0); the runner logged two
DEADLOCK retries during measurement. These retries are not evidence of the
checkpoint's root cause. Explicit resources: maximum1,073,741,824 bytes;
delivery268,435,456; lock provider67,108,864; version workspace67,108,864;
page cache268,435,456; staging frames67,108,864; staged-page capacity4,096.

This command is retained to specify the incident. Do not rerun this known
host-crash workload automatically on this Mac to create this ticket. Any next
reproduction should be designed for an isolated disposable host and capture the
missing write evidence before teardown. PID57024 is a historical identity,
not permission to signal a potentially reused PID after reboot.

## Adjacent evidence and chronology

| Sequence | Source | Measurement seconds | Committed TPS | Measured retries | Result |
| --- | --- | ---: | ---: | ---: | --- |
| Original control1/2 |39eb104c|10|496.100 /614.400|0 /0|Passed|
| Candidate1/2 |79c4da2e|10|900.600 /886.700|0 /1|Passed|
| Longer control1 |39eb104c|30|956.267|2|Passed|
| Longer candidate1 |79c4da2e|30|953.700|2|Passed|
| Longer candidate2 |79c4da2e|30|947.300|2|Passed|
| Longer control2 |39eb104c|30|954.967|2|Passed|
| Resolution candidate |79c4da2e|60|Unavailable as accepted result|2 logged|CHECKPOINT/cleanup failed; no recovery verification|

The 60-second run was the first half of a predeclared candidate/control pair to
resolve the small repeated direction in the30-second set. Its paired60-second
control was not run. Earlier passed runs do not establish host safety or fix
this fault. No speedup/non-regression acceptance follows from this failed pair.

Artifact times (file modification times, not independently timestamped phase
transitions): server/ready files13:59; capture stop/stdout14:00; combined failure
output/stderr14:01; console14:02; retained-path note14:05. Panic timestamp is
14:08:48+0100. The note's `?E` state and PPID are retained observations, not a
complete saved process table. The original retained database path was
`/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-tps-test.BaUqm0/database`.
That directory and temporary worktrees were absent when inspected after reboot;
no database bytes survive in the available evidence package. Git retains the
feature commit and these copied logs.

## Kernel evidence and prior incidents

The latest report names launchd's waiter exactly:

```text
thread 800866: krwlock 0x5ee9b380d4e609cd for reading owned by thread 798645
```

Owner thread798645 belongs to Java PID57024 and is named
`Java: ForkJoinPool-1-worker-6`, with TH_WAIT / TH_UNINT and TH_SFLAG_ABORT;
its process is marked terminatedSnapshot. The launchd waiter lastRunTime is
455.016082041 seconds. This means time since it last ran at the snapshot,
not a measured lock-acquisition timestamp or proven first-stall time.
All18 kernel-frame entries match the two earlier panics after image normalization.

| Report | Server PID/thread | launchd waiter | Waiter lastRunTime seconds |
| --- | --- | --- | ---: |
|2026-09-13 13:19:28|11854 /182510|183625|444.189|
|2026-09-13 16:53:27|1697 /41723|43004|437.923|
|2026-09-14 14:08:48|57024 /798645|800866|455.016082041|

Kernel UUID447D769E-1CB7-3086-A0B4-32226837B587 matches the previously symbolicated
kernel. Extension frame UUIDbd0236ac-207c-3a10-991f-18d2785815af at offset0x82fe8
also matches. See the existing [source and symbolication investigation](../plans/checkpoint-kernel-hang-20260913.md).
A carrier thread name alone does not identify which virtual-thread operation
was mounted when the kernel write stalled.

CHECKPOINT's relevant source sequence is live-page flush and sidecar writes;
immutable checkpoint page-file writes using POSITIONAL mode; resize/force/close;
then WAL rotation and checkpoint control installation. Positional
`FileChannel.write` and the one-byte extension write in `NioDurableFile` are
concrete native-write candidates. The snapshot does not choose between them.
These checkpoint/provider owners and the TPS/client source above are unchanged
by79c4da2e; admission's effect on workload timing/layout is not excluded.

## Bounded investigation and completion criteria

1. Identify the mounted Java write operation and target file/vnode/fd,
   offset/length, mapping/extension state and ordering before the first stall.
2. Distinguish a server-returned storage IO_FAILURE from client transport loss;
   correlate it with graceful stop, signals and kernel wait ownership.
3. Establish a minimized reproducer and governing kernel/provider invariant,
   or document an explicit evidence limit. Do not label a kernel frame's nearest
   exported symbol as its exact private function.
4. Hand a proven fix boundary to existing tic-osgiliath; do not implement a
   guessed mmap rewrite, process-tree killer, timeout framework or workload tuning
   under this incident-evidence ticket. Retain failed-run exclusion and exact
   cleanup semantics. Record actual validation before claiming repair.

Independent review: execution_admission_review verified the new report's
PID/thread ownership and exact frame recurrence using read-only inspection.
No workload was launched for this ticket's investigation.

## Embedded complete runtime failure evidence

```text
version=wal-admission-79c4da2e
branch=ticket/tic-5b3e-cohort-admission
managed_server=starting port=0
managed_server=started port=61306
managed_server_resources=explicit maximum_bytes=1073741824 delivery_bytes=268435456 lock_provider_bytes=67108864 version_workspace_bytes=67108864 page_cache_bytes=268435456 staging_frame_bytes=67108864 staged_page_capacity=4096
managed_server_deadlock_diagnostics=disabled budget_bytes=0
Running 60 seconds of authenticated River TPS testing against localhost:61306
profile=tiny mix=standard warmup_seconds=2 measured_seconds=60 scheduling=no-wait-stress evidence=diagnostic
=== TPS runner output ===
profile=tiny-nonstandard
warehouses=1 terminals=4 warmup_seconds=2 measured_seconds=60 batch_rows=32 maximum_attempts=32 scheduling=NO_WAIT_STRESS mix=STANDARD isolation_contract=SERIALIZABLE jdbc_isolation=SERIALIZABLE program_isolation=SERIALIZABLE phase=LOAD_RUN_CHECKPOINT evidence=DIAGNOSTIC jfr=disabled
bounded_metrics=5 transaction types x 64 latency buckets per terminal
phase_start=load
load_seconds=1.157
load_checkpoint=completed
pre_run_invariants=passed
phase_complete=load
phase_start=preflight
preflight_deadlock_probe=passed expected_victims=1 metrics_epoch=3
phase_complete=preflight
phase_start=warmup
phase_complete=warmup
phase_start=measured
retry_correlation index=19791 attempt_tag=19791 logical_sequence=4929 terminal=1 transaction=DELIVERY attempt=1 step_tag=23 status=DEADLOCK client_will_retry=true measured=true
retry_correlation index=49106 attempt_tag=49106 logical_sequence=12331 terminal=3 transaction=ORDER_STATUS attempt=1 step_tag=2 status=DEADLOCK client_will_retry=true measured=true
phase_complete=measured
phase_complete=drain
phase_start=checkpoint
=== runner stderr ===
Exception in thread "main" java.sql.SQLException: execute update failed: IO_FAILURE
	at io.riverdb.jdbc.JdbcExceptions.failure(JdbcExceptions.java:23)
	at io.riverdb.jdbc.JdbcExceptions.require(JdbcExceptions.java:15)
	at io.riverdb.jdbc.RiverJdbcStatement.completeUpdate(RiverJdbcStatement.java:411)
	at io.riverdb.jdbc.RiverJdbcStatement.executeUpdateSql(RiverJdbcStatement.java:70)
	at io.riverdb.jdbc.RiverJdbcStatement.executeUpdate(RiverJdbcStatement.java:61)
	at io.riverdb.bench.tpcc.TpccRunPhase.checkpointAndIdentify(TpccRunPhase.java:64)
	at io.riverdb.bench.tpcc.TpccRunPhase.execute(TpccRunPhase.java:30)
	at io.riverdb.bench.tpcc.TpccAcceptanceMain.main(TpccAcceptanceMain.java:14)
result=cleanup_failed phase=cleanup status=OWNED_PROCESS_UNRESPONSIVE exit_status=1
unresponsive_server_pid=57024
retained_database=/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-tps-test.BaUqm0/database
retained_evidence_dir=/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-tps-test.BaUqm0
error: owned process remained live after SIGKILL; preserving database and evidence
```

### Retained server startup log

```text
server_client_config=/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-tps-test.BaUqm0/database/security/client.properties
server_ready=61306
```

### Retained process/path observation

```text
/private/var/folders/s8/j683tdnx0hl_8jnrts2r0bkh0000gn/T/river-tps-test.BaUqm0
Server PID: 57024
Observed process state: PPID 1; Darwin ?E after SIGKILL.
Database payload left untouched.
```

### Embedded structured panic evidence

<details>
<summary>Complete implicated-process extract and normalized frame comparisons</summary>

```json
{
  "source_report_path": "/Library/Logs/DiagnosticReports/panic-full-2026-09-14-140848.0002.panic",
  "report_header": {
    "bug_type": "210",
    "timestamp": "2026-09-14 14:08:48.00 +0100",
    "os_version": "macOS 26.6.2 (25G83)",
    "roots_installed": 0,
    "incident_id": "DF8DEB35-E00D-4C6F-9673-2DAF7E354A64"
  },
  "panic_metadata": {
    "date": "2026-09-14 14:08:48.37 +0100",
    "product": "Mac17,3",
    "kernel": "Darwin Kernel Version 25.6.0: Fri Jul 31 19:16:20 PDT 2026; root:xnu-12377.161.14~5/RELEASE_ARM64_T8142",
    "build": "macOS 26.6.2 (25G83)",
    "bug_type": "210",
    "incident": "DF8DEB35-E00D-4C6F-9673-2DAF7E354A64",
    "panicFlags": "0x802",
    "panicProcessingFlags": "0x0",
    "roots_installed": 0
  },
  "panicString": "panic(cpu 0 caller 0xfffffe004153cfdc): watchdog timeout: no checkins from watchdogd in 94 seconds (1536 total checkins since monitoring last enabled)\nDebugger message: panic\nMemory ID: 0x1\nOS release type: User\nOS version: 25G83\nKernel version: Darwin Kernel Version 25.6.0: Fri Jul 31 19:16:20 PDT 2026; root:xnu-12377.161.14~5/RELEASE_ARM64_T8142\nExclaves boot status: BOOTED_EXCLAVEKIT\nFileset Kernelcache UUID: DC901C02455A8411D94142773EE435E4\nKernel UUID: 447D769E-1CB7-3086-A0B4-32226837B587\nBoot session UUID: DF8DEB35-E00D-4C6F-9673-2DAF7E354A64\niBoot version: mBoot-18000.161.10\niBoot Stage 2 version: mBoot-18000.161.10\nsecure boot?: YES\nroots installed: 0\nPaniclog version: 15\nDebug Header address: 0xfffffe001faf1000\nDebug Header entry count: 3\nTXM load address: 0xfffffe002fa4c000\nTXM UUID: F022806D-8557-38F3-97B8-35BEF392F462\nDebug Header kernelcache load address: 0xfffffe003fa4c000\nDebug Header kernelcache UUID: DC901C02-455A-8411-D941-42773EE435E4\nSPTM load address: 0xfffffe001fa4c000\nSPTM UUID: EB28BBD0-9218-3B7B-95CF-BC53E21036D4\nKernelCache slide: 0x0000000038a48000\nKernelCache base:  0xfffffe003fa4c000\nKernel slide:      0x0000000038a50000\nKernel text base:  0xfffffe003fa54000\nKernel text exec slide: 0x000000003d1ac000\nKernel text exec base:  0xfffffe00441b0000\nmach_absolute_time: 0x1ab63abf038\nEpoch Time:        sec       usec\n  Boot    : 0x6aa6c6ef 0x000418b2\n  Sleep   : 0x6aa7b541 0x000843ca\n  Wake    : 0x6aa7b55e 0x0000b713\n  Calendar: 0x6aa7f1cb 0x000025dd\n\nZone info:\n  Zone map: 0xfffffe1002000000 - 0xfffffe3602000000\n  . VM    : 0xfffffe1002000000 - 0xfffffe15ce000000\n  . RO    : 0xfffffe15ce000000 - 0xfffffe1868000000\n  . GEN0  : 0xfffffe1868000000 - 0xfffffe1e34000000\n  . GEN1  : 0xfffffe1e34000000 - 0xfffffe2400000000\n  . GEN2  : 0xfffffe2400000000 - 0xfffffe29cc000000\n  . GEN3  : 0xfffffe29cc000000 - 0xfffffe2f98000000\n  . DATA  : 0xfffffe2f98000000 - 0xfffffe3602000000\n  Metadata: 0xfffffe4f68010000 - 0xfffffe4f71810000\n  Bitmaps : 0xfffffe4f71810000 - 0xfffffe4f75d70000\n  Extra   : 0 - 0\n\nCORE 0 [EACC0] recently retired instr at 0x0000000000000000\nCORE 1 [EACC0] recently retired instr at 0x0000000000000000\nCORE 2 [EACC0] recently retired instr at 0x0000000000000000\nCORE 3 [EACC0] recently retired instr at 0x0000000000000000\nCORE 4 [EACC0] recently retired instr at 0x0000000000000000\nCORE 5 [EACC0] recently retired instr at 0x0000000000000000\nCORE 6 [PACC1] recently retired instr at 0x0000000000000000\nCORE 7 [PACC1] recently retired instr at 0x0000000000000000\nCORE 8 [PACC1] recently retired instr at 0x0000000000000000\nCORE 9 [PACC1] recently retired instr at 0x0000000000000000\nTPIDRx_ELy = {1: 0xf4fffe186a31e560  0: 0x0001000000000000  0ro: 0x0000000000000000 }\nCORE 0 is the one that panicked. Check the full backtrace for details.\nCORE 1: PC=0xfffffe0044249718, LR=0xfffffe004424973c, FP=0xfffffe51bc08b8e0\nCORE 2: PC=0xfffffe00443c2324, LR=0xfffffe00443c2320, FP=0xfffffe51bb98be40\nCORE 3: PC=0xfffffe00443c2324, LR=0xfffffe00443c2320, FP=0xfffffe51bac6be40\nCORE 4: PC=0xfffffe00443c2324, LR=0xfffffe00443c2320, FP=0xfffffe51bbfebe40\nCORE 5: PC=0xfffffe00443c2324, LR=0xfffffe00443c2320, FP=0xfffffe51ba15be40\nCORE 6: PC=0xfffffe00442706e0, LR=0xfffffe00442706e0, FP=0xfffffe51bacfbee0\nCORE 7: PC=0xfffffe00442706e0, LR=0xfffffe00442706e0, FP=0xfffffe51bc4fbee0\nCORE 8: PC=0xfffffe00442706e0, LR=0xfffffe00442706e0, FP=0xfffffe51bc52bee0\nCORE 9: PC=0xfffffe00443ca338, LR=0xfffffe004434bebc, FP=0xfffffe51b619fd70\nCompressor Info: 7% of compressed pages limit (OK) and 7% of segments limit (OK) with 0 swapfiles and OK swap space\nTotal cpu_usage: 65451529\nThread task pri cpu_usage\n0xfdfffe186a050ac0 kernel_task 91 4395966\n0xf5fffe186bb4cac0 deleted 20 7801\n0xf4fffe186a31e560 kernel_task 0 4556637\n0xf1fffe186a31ee40 kernel_task 0 4728398\n0xfafffe186a31c1e0 kernel_task 0 4855605\n\nPanicked task 0xf4fffe2402035c88: 0 pages, 623 threads: pid 0: kernel_task\nPanicked thread: 0xf4fffe186a31e560, backtrace: 0xfffffebfcc0076d0, tid: 562\n\t\t  lr: 0xfffffe0044208438  fp: 0xfffffebfcc007770\n\t\t  lr: 0xfffffe00443bdf10  fp: 0xfffffebfcc0077e0\n\t\t  lr: 0xfffffe00443bba10  fp: 0xfffffebfcc0078a0\n\t\t  lr: 0xfffffe00441b5ee4  fp: 0xfffffebfcc0078b0\n\t\t  lr: 0xfffffe0044208790  fp: 0xfffffebfcc007c50\n\t\t  lr: 0xfffffe0044207d7c  fp: 0xfffffebfcc007e20\n\t\t  lr: 0xfffffe0044b3c00c  fp: 0xfffffebfcc007e80\n\t\t  lr: 0xfffffe004153cfdc  fp: 0xfffffebfcc007ed0\n\t\t  lr: 0xfffffe004153bd80  fp: 0xfffffebfcc007f10\n\t\t  lr: 0xfffffe0041538b14  fp: 0xfffffebfcc007f30\n\t\t  lr: 0xfffffe004200b654  fp: 0xfffffebfcc007fc0\n\t\t  lr: 0xfffffe00443bfcd8  fp: 0xfffffebfcc007fe0\n\t\t  lr: 0xfffffe00441b5f88  fp: 0xfffffebfcc007ff0\n\t\t  lr: 0xfffffe00443c2320  fp: 0xfffffe61e7e6be40\n\t\t  lr: 0xfffffe00442706e0  fp: 0xfffffe61e7e6bee0\n\t\t  lr: 0xfffffe0044270974  fp: 0xfffffe61e7e6bf20\n\t\t  lr: 0xfffffe00441b6c58  fp: 0x0000000000000000\n      Kernel Extensions in backtrace:\n         com.apple.driver.AppleInterruptControllerV3(1.0d1)[17E37838-AA51-37A2-B605-871825C22626]@0xfffffe00420086a0->0xfffffe004200cb13\n            dependency: com.apple.driver.AppleARMPlatform(1.0.2)[8983F139-EB73-3E5E-9A51-231E6EB7F922]@0xfffffe00414e3ed0->0xfffffe0041537293\n         com.apple.driver.AppleARMWatchdogTimer(1.0)[BDBB2E95-0C68-3FC0-9B7F-0DB0514437BC]@0xfffffe00415372a0->0xfffffe004153d093\n            dependency: com.apple.driver.AppleARMPlatform(1.0.2)[8983F139-EB73-3E5E-9A51-231E6EB7F922]@0xfffffe00414e3ed0->0xfffffe0041537293\n\n\nlast started kext at 350323437: com.apple.filesystems.autofs\t3.0 (addr 0xfffffe0040724b80, size 5927)\nloaded kexts: (skipped, see boot kernelcache)\n\n",
  "processByPid": {
    "57024": {
      "codeSigningAuxiliaryInfo": 0,
      "jetsamCoalition": 648,
      "pageFaults": 214430,
      "userTimeTask": 97.98109625,
      "groupID": 20,
      "userID": 501,
      "procname": "java",
      "rawFlags": "0x1002088C809",
      "copyOnWriteFaults": 2095,
      "residentMemoryBytes": 82016,
      "timesThrottled": 0,
      "flags": [
        "terminatedSnapshot",
        "boosted",
        "isImpDonor",
        "isLiveImpDonor",
        "sharedRegionNone"
      ],
      "csTrustLevel": 4294967295,
      "suspendCount": 2,
      "timesDidThrottle": 0,
      "pageIns": 28976,
      "pid": 57024,
      "csFlags": 570491397,
      "systemTimeTask": 221.549408958,
      "threadById": {
        "798645": {
          "id": 798645,
          "schedPriority": 46,
          "state": [
            "TH_WAIT",
            "TH_UNINT"
          ],
          "system_usec": 208475055,
          "schedFlags": [
            "TH_SFLAG_ABORT",
            "TH_SFLAG_RW_PROMOTED",
            "TH_SFLAG_FLOOR_PROMOTED"
          ],
          "snapshotFlags": [
            "kKernel64_p",
            "kThreadSuspended"
          ],
          "user_usec": 9787047,
          "kernelFrames": [
            [
              0,
              771644
            ],
            [
              0,
              766656
            ],
            [
              0,
              1702256
            ],
            [
              0,
              1615596
            ],
            [
              0,
              6847368
            ],
            [
              0,
              2804296
            ],
            [
              0,
              2803260
            ],
            [
              0,
              2802736
            ],
            [
              11,
              536552
            ],
            [
              0,
              3146832
            ],
            [
              0,
              3094812
            ],
            [
              0,
              6749300
            ],
            [
              0,
              6750448
            ],
            [
              0,
              6751536
            ],
            [
              0,
              7999032
            ],
            [
              0,
              2145004
            ],
            [
              0,
              24292
            ],
            [
              1,
              0
            ]
          ],
          "basePriority": 31,
          "userTime": 9.787047166,
          "waitEvent": [
            0,
            15890864
          ],
          "lastRunTime": 0.03097675,
          "systemTime": 208.4750555,
          "name": "Java: ForkJoinPool-1-worker-6"
        }
      }
    },
    "1": {
      "codeSigningAuxiliaryInfo": 139586437120,
      "jetsamCoalition": 3,
      "pageFaults": 67066,
      "userTimeTask": 24.294933708,
      "groupID": 0,
      "userID": 0,
      "procname": "launchd",
      "rawFlags": "0x10040780001",
      "copyOnWriteFaults": 73,
      "residentMemoryBytes": 24854840,
      "timesThrottled": 0,
      "flags": [
        "uuidFaultFlags0x00700000",
        "sharedRegionSystem"
      ],
      "csTrustLevel": 4294967295,
      "timesDidThrottle": 0,
      "pageIns": 1642,
      "pid": 1,
      "csFlags": 570522385,
      "systemTimeTask": 226.752562916,
      "threadById": {
        "800866": {
          "id": 800866,
          "notice": "Unmapped pages caused truncated backtrace (resampling disabled)",
          "state": [
            "TH_WAIT",
            "TH_UNINT"
          ],
          "system_usec": 4940,
          "schedFlags": [
            "TH_SFLAG_RW_PROMOTED"
          ],
          "snapshotFlags": [
            "kUser64_p",
            "kKernel64_p",
            "kThreadDarwinBG",
            "kThreadTruncatedBT",
            "kThreadFaultedBT",
            "kThreadTriedFaultBT",
            "kThreadTruncUserBT"
          ],
          "user_usec": 3026,
          "kernelFrames": [
            [
              0,
              771644
            ],
            [
              0,
              766656
            ],
            [
              0,
              639292
            ],
            [
              0,
              638312
            ],
            [
              11,
              489580
            ],
            [
              11,
              722112
            ],
            [
              0,
              2884472
            ],
            [
              11,
              721240
            ],
            [
              0,
              2988640
            ],
            [
              0,
              2883080
            ],
            [
              0,
              2988432
            ],
            [
              0,
              7999032
            ],
            [
              0,
              2145004
            ],
            [
              0,
              24292
            ],
            [
              1,
              0
            ]
          ],
          "basePriority": 4,
          "userFrames": [
            [
              15,
              5026128
            ]
          ],
          "userTime": 0.003026,
          "waitEvent": [
            1,
            16999989551637169369
          ],
          "lastRunTime": 455.016082041,
          "systemTime": 0.004940541,
          "schedPriority": 46
        }
      },
      "waitInfo": [
        "thread 800866: krwlock 0x5ee9b380d4e609cd for reading owned by thread 798645"
      ]
    }
  },
  "referenced_binary_images": {
    "0": {
      "uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "load_address_hex": "0xfffffe000b768000",
      "type": "T"
    },
    "1": {
      "uuid": "00000000-0000-0000-0000-000000000000",
      "load_address_hex": "0x0",
      "type": "A"
    },
    "11": {
      "uuid": "bd0236ac-207c-3a10-991f-18d2785815af",
      "load_address_hex": "0xfffffe000b3bee90",
      "type": "T"
    },
    "15": {
      "uuid": "f2e86c53-6052-388b-ba71-5a0c9b569413",
      "load_address_hex": "0x180000000",
      "type": "S"
    }
  },
  "java_normalized_kernel_frames": [
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0xbc63c"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0xbb2c0"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x19f970"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x18a6ec"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x687b88"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x2aca48"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x2ac63c"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x2ac430"
    },
    {
      "image_uuid": "bd0236ac-207c-3a10-991f-18d2785815af",
      "offset_hex": "0x82fe8"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x300450"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x2f391c"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x66fc74"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x6700f0"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x670530"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x7a0e38"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x20baec"
    },
    {
      "image_uuid": "447d769e-1cb7-3086-a0b4-32226837b587",
      "offset_hex": "0x5ee4"
    },
    {
      "image_uuid": "00000000-0000-0000-0000-000000000000",
      "offset_hex": "0x0"
    }
  ],
  "prior_report_comparison": {
    "method": "Compare the complete ordered kernelFrames arrays after replacing each report-local image index with its binaryImages UUID; preserve every offset, including the terminal zero frame.",
    "reports": [
      {
        "report_path": "/Library/Logs/DiagnosticReports/panic-full-2026-09-13-131928.0002.panic",
        "timestamp": "2026-09-13 13:19:28.00 +0100",
        "java_pid": 11854,
        "java_thread_id": 182510,
        "prior_frame_count": 18,
        "current_frame_count": 18,
        "all_normalized_frames_equal": true,
        "differences": []
      },
      {
        "report_path": "/Library/Logs/DiagnosticReports/panic-full-2026-09-13-165327.0002.panic",
        "timestamp": "2026-09-13 16:53:27.00 +0100",
        "java_pid": 1697,
        "java_thread_id": 41723,
        "prior_frame_count": 18,
        "current_frame_count": 18,
        "all_normalized_frames_equal": true,
        "differences": []
      }
    ]
  },
  "interpretation_limits": [
    "The panic string names watchdog timeout as the panic trigger; it does not identify the initial I/O stall cause.",
    "launchd lastRunTime is time since the waiter last ran, not a measured lock-acquisition or continuous-ownership duration.",
    "The selected Java thread contains kernelFrames but no userFrames or target file, vnode, descriptor, offset or length.",
    "TH_SFLAG_ABORT and terminatedSnapshot do not identify the signal, sender, or ordering of termination against the initial stall.",
    "Frame equality establishes recurrence of the saved native stack, not that mapped WAL force or a specific Java checkpoint write caused it."
  ]
}
```

</details>

### Complete raw evidence attachment

The ticket embeds the complete failure console and implicated kernel records
above. The full, unabridged three panic reports, all retained incident files,
comparison artifacts and validation evidence are also preserved together in
[this repository attachment](../delivery/evidence/2026-09-14-tic-emeldir-crash.tar.gz),
so the investigation does not depend on temporary directories or an external
local pathname. `manifest.json` inside gives byte counts and SHA-256 for every
member. It contains no database/security-directory contents. Original external
paths are provenance only; available contents are archived here.

The archive also contains `evidence/admission-source.bundle`, preserving exact
feature commit 79c4da2e and its changed objects; it requires base 39eb104c, already
in master. `git bundle verify` passed. This retains reproducible source without
merging the unaccepted feature into master. The ticket and archive were reviewed;
repository ticket validation has 153 pre-existing issues and no newly introduced
issue. Every archived file was checked against its manifest hash.

Archive SHA-256: `ea5c8f5272b84f51089f9a7e53826f2181fec5e25bbbba222dd7bd8e1ee00785`.
