# Checkpoint crash reproduction for later isolated execution

Owner: [tic-osgiliath](../tickets/tic-osgiliath.md). Original recipe, source bundle,
and all three raw panics: [tic-emeldir](../tickets/tic-emeldir.md).

The user selected preparation for later execution on 2026-09-14, then authorized
step 1: rehearse capture against the controlled held-write test and revise the
plan under independent review. Two controlled rehearsals are complete. **The
actual host-crash workload below remains unexecuted and deferred.** The fixture
pauses before the native write; it does not reproduce the kernel fault.

## Rehearsal evidence and revised order

Both attempts used accepted source `e2642598`, GraalVM Java 25.0.4, and the existing
`RiverDaemonCheckpointJdbcTest.heldCheckpointWriteTimesOutAtJdbcClientThenServerClosesAndReopens`
test. Its child owns the authenticated server and injected page-write gate; the
parent verifies normal child exit before reopening and checking the committed row.
No production or test source was changed for capture.

| Observation | Evidence | Consequence |
| --- | --- | --- |
| A process-start trigger was too early | Attempt 1 PID 4693: first JSON at 16:47:42 UTC showed startup/WAL recovery. A second JSON at 16:48:10 showed the held checkpoint. | Do not label artifacts as checkpoint captures merely because Java started or a fixed interval elapsed. |
| State-triggered capture worked | Attempt 2 PID 4762: probe 0 was early; probe 1 at 16:49:19.326706 UTC showed virtual thread 48, `river-connection-0`, TIMED_WAITING in the test latch and checkpoint chain. lsof/native/JFR capture followed at 16:49:19.35–21.64. All collectors returned zero. | Confirm the checkpoint stack before the coordinated capture. |
| The direct wait evidence is Java JSON | `CountDownLatch.await → CheckpointPageFile.write → IndexedPageFrameIo.write → IndexedCheckpointCoordinator.writeDirtyPages/flush → EmbeddedCheckpoint.commit → TransactionManager.commitMaintenance`. | Native samples can establish collector usability here, but cannot show a blocked native file write that the fixture has not entered. |
| Open-file inventory is not active-call attribution | lsof listed pages FD 44, rows FD 45, versions FD 46, and WAL FD 41. | Do not assign FD 44 or its displayed offset to the held invocation without an independent association. Positional writes need their explicit argument. |
| JFR events arrive after operations complete | During-hold JFR: 27 FileWrite and 64 ThreadPark events; final: 45 and 112. Neither contained the checkpoint latch park. After release, FileWrite events on virtual thread 48 showed the test wrapper, `river.indexed.pages`, and 16,384 bytes. | The later completed writes corroborate the fixture path, not the missing during-hold offset. The roughly 30-second carrier park is unrelated and must not be substituted for the virtual-thread latch. |
| Native write arguments are still missing | FileWrite fields were startTime, duration, eventThread, stackTrace, path, bytesWritten; no positional offset. The virtual event had osThreadId 0. | We cannot yet associate the held Java invocation with a native thread/descriptor/offset/count before completion. |
| Filesystem tracing was unavailable | `sudo -n fs_usage ...` returned `sudo: a password is required`. | This was an OS authentication limit, not a negative trace result or an automatic approval rejection. Validate tracing on the isolated host before running its workload. |
| Capture did not break cleanup | Both controlled tests passed. Attempt 2 reported IO_FAILURE after 30,004 ms, with the test asserting zero completed responses before release, normal child exit, and recovered data. | These are controlled-capture results, not evidence that native stuck I/O can be terminated. |

Raw evidence, exact collector commands/timestamps, scripts, Java/native dumps,
JFR files, decoded events, and test XML are retained under
`/Users/blater/src/river/benchmark-results/checkpoint-capture-20260914/`;
`attempt-2/` contains the revised rehearsal. `collectors.json` records scripted
collector commands and exit results; the additional first-attempt Java dump is
retained as `second.threads.json`, with its own JVM timestamp. The first startup capture is retained as a failed
checkpoint-timing attempt, not relabelled as a successful held capture.

The revised next steps are:

1. **Close the in-flight argument gap in another controlled rehearsal.** Prefer
   a small diagnostic at the existing file-I/O owner that exposes the current
   operation, file identity, explicit position/count, Java thread and start time
   before entering the provider call, and completion afterward. It must remain
   inspectable without acquiring the transaction-manager or blocked I/O lock.
   Prove it against independently known fixture arguments while a controlled
   gate is held **after snapshot publication and before the underlying I/O call**,
   including cleanup after returned failure. The current external
   `CheckpointPageFile` gate runs before `delegate.write`, so it cannot validate
   a snapshot inside `NioDurableFile`. A downstream test seam belongs to that
   separately proposed implementation, not this completed rehearsal.
   Do not add a second I/O path, executor,
   per-write log stream, or general tracing framework. This diagnostic is a
   proposed next implementation boundary, **not delivered in this rehearsal**.
2. Establish working filesystem/native capture on the disposable host. Treat
   different OS/JDK/filesystem configurations as separate experiments.
3. Only then consider one instrumented run of the original workload. Require a
   captured in-flight operation and its ordering against cancellation/signals;
   stop on missing capture rather than repeat an unchanged experiment.
4. Minimize the identified operation sequence; choose one evidence-driven
   comparison afterward. No workload sweep or provider replacement is justified.

Independent `execution_admission_review` inspected both attempts and independently
decoded the JFR files. It accepted the second controlled capture and required the
attribution, virtual-thread, timing, and filesystem-authentication limits above.


## What the next run must distinguish

1. A completed server error response versus no completed CHECKPOINT response.
   The runner records existing transport request counters immediately around
   CHECKPOINT; one completed response identifies a server response. Zero does
   not distinguish timeout, EOF, or another transport error. The current client
   read timeout is 30 seconds; do not increase it to hide the symptom.
2. The mounted Java operation and native stack while the server is still alive.
3. Its target file/descriptor, offset and byte count, with timestamped ordering
   against the first stall, stop request and signals.

Filesystem tracing may show only completed calls. JFR FileWrite events may not
include an operation that never returns. A trace without the blocked operation
is not proof that the last completed write caused the hang. If neither stack
nor trace identifies it, stop and add the smallest in-flight diagnostic at the
existing file owner before another run; do not repeatedly run an unchanged
capture recipe.

## Prepare once on the disposable Mac or macOS VM

Use a checkout containing this fix and record its exact commit. The incident
occurred on macOS 26.6.2 / 25G83, Darwin 25.6.0 arm64, GraalVM Java 25.0.4.
A different OS or virtual filesystem is a different experiment; a pass there
cannot clear the original host. Keep one Java workload active, with builds
completed before starting tracing. Store evidence on a persistent path outside
`/private/tmp`; copy it off the disposable environment afterward.

In each terminal below, select the same absolute `incident_dir` and source
checkout. The example directory must be new for each attempt.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
export incident_dir="$PWD/benchmark-results/checkpoint-isolated-01"
mkdir -p "$incident_dir"
git rev-parse HEAD > "$incident_dir/source.txt"
sw_vers > "$incident_dir/os.txt"
uname -a >> "$incident_dir/os.txt"
"$JAVA_HOME/bin/java" -version 2> "$incident_dir/java.txt"
./gradlew --no-daemon :river-bench:installTps > "$incident_dir/build.log" 2>&1
```

For exact historical-source replay, extract the ticket's attachment and use its
`evidence/admission-source.bundle` in a separate checkout based on `39eb104c`.
The historical feature commit is `79c4da2e`; it lacks the new response-origin
and shutdown-order diagnostics. Do not merge that unaccepted optimization to
obtain a reproducer. Current master is the preferred first diagnostic target:
the same panic signature already occurred before the admission feature.

## Start observation before the workload

In a second terminal, start filesystem tracing before Java starts. This command
requires the isolated host's administrator access. Confirm it actually records
Java activity; a denied or empty trace is a capture failure, not negative evidence.

```sh
sudo /usr/bin/fs_usage -w -f filesys -t 180 java > "$incident_dir/filesystem.txt" 2>&1
```

Run the following in the workload terminal after the build has completed. It
retains the incident workload configuration and adds server JFR; tracing changes
its timing, so this is diagnostic evidence and not a performance comparison.

```sh
tools/tps-test.sh --version=checkpoint-isolated-01 --profile=tiny \
  --mix=standard --terminals=4 --scheduling=no-wait-stress \
  --evidence=diagnostic --fresh-load=true --warehouses=1 --batch-rows=32 \
  --maximum-attempts=32 --warmup-seconds=2 --measured-seconds=60 \
  --seed=42 --isolation=serializable --sample-id=checkpoint-isolated-01 \
  --server-jfr="$incident_dir/server.jfr" \
  --output-dir="$incident_dir/run" > "$incident_dir/console.log" 2>&1
```

In a third terminal, identify the single owned server after startup. Do not use
PID 57024 from the incident or send jcmd commands to PID 0 (all JVMs).

```sh
pgrep -fl 'io.riverdb.bench.tpcc.TpccServerMain'
```

Set `server_pid` to that single observed PID. Retain a healthy capture separately.
Use the workload phase as a cue, then confirm an actual checkpoint stack or the
required in-flight operation record before describing a capture as checkpoint
evidence. Do not use process startup or a fixed sleep as proof. For the controlled
fixture the criterion was `CheckpointPageFile.write` under `CountDownLatch.await`;
that test-only frame must not be expected in the real workload.

Take Java probe dumps to distinct files; if a probe is early, retain it and retry
within the pre-timeout observation window. Once checkpoint entry is observed,
start native sampling and descriptor capture independently, then dump JFR while
work is still pending. If attach stops responding, collect native/filesystem
evidence immediately without waiting for a successful Java probe. Such a capture
still needs its actual operation association before attribution.

The commands below show the individual collectors, not a requirement to run them
serially behind a potentially blocked attach. Use distinct `capture` names.

```sh
server_pid=REPLACE_WITH_OBSERVED_PID
capture=healthy
date -u > "$incident_dir/$capture.time.txt"
ps -p "$server_pid" -o pid,ppid,state,etime,command > "$incident_dir/$capture.process.txt"
"$JAVA_HOME/bin/jcmd" "$server_pid" Thread.dump_to_file -format=json \
  "$incident_dir/$capture.threads.json" > "$incident_dir/$capture.jcmd.txt" 2>&1
/usr/sbin/lsof -nP -o -p "$server_pid" > "$incident_dir/$capture.files.txt" 2>&1
/usr/bin/sample "$server_pid" 2 1 -file "$incident_dir/$capture.native.txt"
"$JAVA_HOME/bin/jcmd" "$server_pid" JFR.dump \
  filename="$incident_dir/$capture.jfr" > "$incident_dir/$capture.jfr-dump.txt" 2>&1
```

Run native sampling and descriptor capture from separate terminals if a jcmd
attach stops responding; do not wait for attach to finish before collecting
other evidence. A JVM already in exiting state may be unsampleable. The existing
runner keeps its shutdown policy; this procedure adds no process-tree killer,
new deadline, or assumption that SIGKILL releases a kernel wait.

## Retain and decide

Preserve the complete console, runner artifacts, server logs/JFR, thread dumps,
filesystem trace and any new panic report. On failure the runner prints retained
paths: copy their available logs and database before reboot or temporary-file
cleanup, restricting database/security material to private incident storage.
Do not publish generated credentials in a ticket. Record missing files explicitly.

A useful reproduction requires the original CHECKPOINT symptom plus identified
server operation and cleanup outcome. A clean process exit requires the server
and its workers to terminate normally, followed by restart and committed-data
verification. A watchdog panic, forced exit, unreaped process, or missing recovery
verification is a failed run. Do not proceed to another run automatically.
