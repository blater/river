# Checkpoint kernel stall: exact-source capture plan

Owner: [tic-osgiliath](../tickets/tic-osgiliath.md). This plan replaces the
superseded call-site-only plan. It retains the historical evidence in
[the kernel-hang investigation](checkpoint-kernel-hang-20260913.md),
[the September 14 incident](../tickets/tic-emeldir.md), and
[the earlier capture plan](checkpoint-crash-reproduction.md). It does not
reinterpret the later 2026-09-15 client timeout as another kernel incident.

## Review status: not approved for execution

This document specifies proposed work, not an implemented or guaranteed crash
reproducer. The independent adversarial review and lead review found execution
blockers: sampler readiness, uniquely correlating an aggregate native sample
with one operation, and durable retention of completed native captures are not
yet demonstrated by the proposed runner. Do not execute the incident command
below until those specific gaps are resolved and reviewed. Existing synchronous
write-record persistence is implemented; the proposed collector changes are not.

No successful controlled test can guarantee that the historical kernel failure
will recur or that the same affected filesystem will retain every panic-time
record. Neither a client timeout nor a completed capture is success on the
root-cause objective.

## Objective and established evidence

The next and only risk-bearing attempt asks which River operation, file,
offset/count, Java request chain and native OS thread coincide with a recurrence
of the original macOS kernel stall. Three retained panic reports already show a
Java-owned kernel read/write lock blocking launchd and the same
`VNOP_WRITE` / `cluster_write` / VM-wait frame sequence. They do not identify
the mounted virtual thread, River call site or file arguments.

The accepted current diagnostic closes the identity gap in a controlled case.
It persists a BEGIN before the existing positional `FileChannel.write`, obtains
`pthread_threadid_np` in a JNI frame that stays active across that same Java
write, and persists RETURN or THROW afterward. A controlled virtual-thread write
recorded native thread 360079; `/usr/bin/sample` showed exact `Thread_360079`
with the JNI trampoline in that thread section. An authenticated JDBC control
then retained complete River request stacks and concrete native IDs for 984
matched writes. Reuse that proof; do not repeat the discarded JFR/carrier-join
experiments.

The current source ordering is direct and is the primary capture mechanism:
`NioDurableFile.writeOnPinnedCarrier` calls `persistedDiagnostics.begin` at line
289; `PersistedFileWriteDiagnostics.ApfsSink.persist` calls
`file.force(CONTENT_AND_METADATA)` at line 337; only after that successful
`F_FULLFSYNC` does `NioDurableFile` call the original `channel.write` at line
297. The same JNI invocation remains on the carrier throughout. External
sampling supports that durable operation record; it does not replace it.

The later current-master attempt is separate. Its final checkpoint timed out,
but all 14,165 instrumented writes had matching RETURN records and no live native
sample existed. It neither reproduces nor explains the historical kernel stall.

## Exact historical baseline

Use a detached clean worktree based on the actual incident commit
`79c4da2ef5fb5f38c712b1bb48e7121c3ff1fd04` (`wal-admission-79c4da2e`). Do not
substitute current master: checkpoint, transport and shutdown behavior has since
changed. The September 14 incident used GraalVM 25.0.4, Darwin 25.6.0 arm64,
macOS 26.6.2 build 25G83, tiny/standard, four terminals, no-wait stress,
serializable isolation, fresh load, one warehouse, batch size 32, maximum 32
attempts, two warmup seconds, 60 measured seconds and seed 42. JFR and detailed
deadlock diagnostics were disabled.

Read-only comparison establishes that this is a feasible narrow backport:

- historical `NioDurableFile.write` calls `FileChannel.write` at source line 107;
- its growth branch in `resize` calls the same API at source line 218;
- `NioDurableDirectory.openHandle` owns the exact target `Path` when it constructs
  the file handle;
- the public APFS `RiverFile` and `ApfsRiverDaemonFileSystem` facilities used to
  make records durable are unchanged between the incident commit and the
  accepted diagnostic source.

Create one capture branch from that commit and apply only the pieces below. Do
not backport current pending-JFR diagnostics, checkpoint error handling, shutdown
changes, WAL work or unrelated fixes.

## Capture-only source patch

1. Add `PersistedFileWriteDiagnostics.java`, `PersistedFileWriteNative.java` and
   `river-platform/src/main/native/macos/persisted_file_write.c` at their current
   ownership boundary. Define `POSITIONAL_WRITE` and `RESIZE_GROWTH` locally in
   the persisted diagnostic so the backport does not acquire the later pending-
   JFR implementation as a dependency.
2. In `NioDurableDirectory.openHandle`, pass its existing `path` to the
   `NioDurableFile` constructor. Register the opt-in diagnostic once in that
   constructor.
3. In `NioDurableFile.write`, route only the historical line-107 positional call
   through the JNI boundary with `POSITIONAL_WRITE`. In the growth branch of
   `resize`, route only the historical line-218 one-byte call with
   `RESIZE_GROWTH`. Preserve the original partial-write, zero-progress, buffer-
   position, result and counter semantics exactly.
4. Keep the accepted fail-closed contract: log creation is directory-durable;
   BEGIN completes `F_FULLFSYNC` before target admission; failure prevents the
   target call; RETURN failure cannot change an already returned byte count and
   fences later diagnostic writes. There is no `fsync` fallback and no unknown
   native ID in an enabled run.

No private descriptor extraction, replacement `pwrite`, file-size probe, SQL
payload or new recorder belongs in this patch.

## What each record can prove

| Evidence | Permitted conclusion | Limit |
| --- | --- | --- |
| Durable BEGIN | One named Java invocation reached the diagnostic boundary with these arguments and native ID. | It does not alone prove entry into `FileChannel.write`; an attempt blocked before durable BEGIN is invisible. |
| Same-ID native sample during the BEGIN interval | The sampled OS thread is executing the JNI-wrapped invocation. | Require the exact `Thread_<id>` section and JNI frame; an ID substring, carrier name or Java ID is insufficient. |
| RETURN | The target write returned this byte count before the RETURN record was persisted. | Absence can mean a blocked target, process loss, or a target return followed by blocked/failed RETURN persistence. |
| THROW | The target write threw the recorded exception. | It is not a kernel-stack observation. |
| Panic PID/thread plus live same-ID sample | The sampled River invocation and panic owner are the same OS thread in the same process. | The live sample supplies the user/native bridge; only the panic report supplies retained kernel ownership and kernel frames. |

An aggregate two-second sample is not an individually timestamped syscall trace.
Matching a thread ID somewhere in that report to any record from the run is not
sufficient. The analysis must establish that exactly one candidate invocation
accounts for the relevant sample interval and stack, including any BEGIN/RETURN
transitions within it. If it cannot, report an ambiguous association. A later
panic using the same thread ID is also insufficient if that thread executed
another invocation after the sampled operation; continuity to the panic must be
established rather than inferred from identity alone.

Attribution does not require an operation to remain unmatched forever. It
requires time overlap: BEGIN precedes a sample of that native ID in the JNI/write
bridge, and no RETURN/THROW had been durably observed when that sample was taken.
A later RETURN means the observed delay recovered. An unmatched BEGIN without
the same-thread live sample remains ambiguous.

The diagnostic covers these two positional-write call sites only. It does not
cover mapped stores, force, truncate, unmap, close or other checkpoint work.

## Pre-armed native collection

Add one opt-in `--checkpoint-native-capture-dir=PATH` to the historical
`tools/tps-test.sh`. Require macOS, `--evidence=diagnostic`, an absolute new
mode-0700 directory outside the source worktree, the existing `--output-dir`,
`/usr/bin/sample`, the selected JDK's `jcmd`, and a validated capture-force
helper. The ordinary runner remains unchanged when the option is absent.

After `server_pid=$!` is ready and before launching `TpccAcceptanceMain`, start
one bounded collector loop against that exact direct-child PID. Never discover a
process by name and never use a historical PID. Use the existing runner timeout
as the workload observation bound, followed by a bounded collector drain before
server teardown. Do not impose a shorter independent capture cutoff: the last
attempt lasted approximately 150 seconds. Start consecutive two-second captures:

```sh
/usr/bin/sample "$server_pid" 2 1 -mayDie \
  -file "$capture_dir/native-000001.txt"
```

Every completed, nonempty sample is forced immediately before the next sample.
Add a small macOS-only `river-platform/src/main/native/macos/fullsync_capture.c`
executable for this immediate consumer. It accepts one already-created regular
file and its already-openable parent directory, rejects symlinks, opens with
`O_NOFOLLOW`, calls `fcntl(F_FULLFSYNC)` on the file and directory, closes both,
and returns nonzero on any failure. It does not create, copy, parse or select
evidence. Build it with `clang -O2 -Wall -Wextra -Werror`; a controlled file and
directory force must pass before workload admission. If the helper is rejected
in review or cannot be proven on the incident filesystem, durable native-sample
retention is an execution blocker.

The first sampler must be active before the client workload launches. The exact
observable readiness predicate for `/usr/bin/sample` is not established by the
existing post-entry identity test. The controlled gate below must establish one
by starting the sampler first, waiting only on an observable sampler condition,
then releasing the known write. Process creation or a fixed sleep alone is not
acceptance. If this installed `sample` exposes no usable readiness condition,
do not run the incident; do not replace the oracle by guess.

Two-second files are intentional. A long sample produces its report only after
completion and is vulnerable to the host panic. Consecutive short captures leave
a small inter-file gap, which is recorded as a limitation; completed captures
become durable throughout load, measurement and final checkpoint rather than at
cleanup.

## Java dump and live phase observation

`TpccRunPhase` already writes `phase_start=checkpoint` to runner stdout before
the final invariant/checkpoint path. The incident revision does not contain the
later detailed post-run failure marker; do not describe it as present. The
pre-armed native stream is primary and does not wait for either marker.

In the existing 100 ms runner-child loop, inspect redirected stdout before
checking whether the runner is reapable, and inspect once more after leaving the
loop to close the exit race. On `phase_start=checkpoint`, run one same-JDK
command, bounded to five seconds, while the owned server is live:

```sh
"$java_runtime_home/bin/jcmd" "$server_pid" Thread.dump_to_file \
  -format=json "$capture_dir/checkpoint-start.threads.json"
```

Do not backport the newer Java failure marker merely to add a second trigger.
Do not wait for jcmd before native sampling. Java 25 documents
`Thread.dump_to_file -format=json` as including virtual threads; traditional
`jstack` / `Thread.print` is not the virtual-thread oracle. The controlled gate
must still prove the installed GraalVM output contains the named test virtual
thread and River stack. A dump does not establish carrier/native identity; the
JNI ID and native sample do.

The later failure text was already present in redirected stderr before cleanup.
The shell watched only child exit and replayed the files after the runner became
reapable; no live consumer invoked capture. This is a shell ordering gap, not
evidence of Java output buffering. The historical 79c4 source provides only its
original exception output, so the plan does not depend on a fabricated exact
timeout marker.

## Collector lifetime and cleanup

Track sampler, force-helper and jcmd PIDs as owned direct children. Collection
covers the existing runner lifetime. At runner completion, capture failure,
interruption or the existing runner deadline:

1. stop admitting new two-second samples;
2. allow only the current collector its remaining per-command budget;
3. send TERM, then KILL if necessary, and reap every collector;
4. force each completed output and the capture directory;
5. record collector exits and missing/partial files in a small forced text log;
6. only then enter the historical runner's existing server-stop and cleanup
   policy.

Collector failure makes the diagnostic attempt inconclusive and stops the
client; it must not be reported as a workload or database failure. The existing
20-second owned-server cleanup remains the only server shutdown policy. It
cannot make an uninterruptible kernel wait return.

`/usr/bin/sample` previously exposed the JNI bridge on a live same-user JVM, but
it could not inspect the historical server after Darwin placed it in `?E`
exiting state. Its live output may show the native bridge and system-call frames;
do not claim it reproduces the panic's kernel stack. `jcmd` is also a same-user
attach. Run both only with ordinary same-user access. If either is denied, retain
the exact error and make no elevation or security-setting change. Privileged
filesystem tracing is outside this plan.

The synchronous in-thread write logger requires no administrator access. Any
future exception must explain its essential purpose, exact privileges and why
ordinary access is insufficient, and receive the user's explicit agreement
before execution. A collector denial alone does not authorize elevation.

## Narrow controlled gates

Reuse the accepted native-ID control. Add only the missing ordering/persistence
proof:

1. Port the focused persisted-write tests to the 79c4 backport and rerun the real
   APFS BEGIN/RETURN, failure and disabled cases.
2. Extend the existing virtual-thread gate so `/usr/bin/sample` starts first.
   After its verified ready condition, release exactly one held real
   `FileChannel.write`. Require the completed, full-synced sample to contain the
   persisted native ID's exact thread section and JNI trampoline; require the
   matching RETURN afterward.
3. While that gate is held, run the installed GraalVM 25
   `Thread.dump_to_file` command and require the named virtual thread and River
   test stack. Its absence blocks the Java-dump claim but does not weaken the
   native identity oracle.
4. Extend `tools/tests/tps-shutdown-test.sh` with fake `sample`, capture-force and
   fake-JDK `jcmd` commands resolved through the test PATH. Prove the first
   sampler is ready before the fake runner starts; completed files are forced in
   order; phase observation happens while the exact owned server PID is live;
   the post-exit observation closes the race; hung collectors are killed and
   reaped at the capture deadline before existing cleanup begins.

Run the focused platform test, shutdown shell test, `bash -n`, the whole affected
platform suite and source/module policy checks. Do not add another workload,
sampler matrix or JFR experiment.

## Exact build and one incident command

Build the 79c4 capture branch once, sequentially and with isolated caches. This
is the historical build gate with only the worktree/cache names changed:

Before the build commands, create the new private evidence directory
`/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/`
and its `tmp` and `run` directories with mode 0700. Leave `live` and all final
output files absent. The two clang destinations below must therefore have an
existing parent; do not overwrite a previous attempt's artifacts.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
GRADLE_USER_HOME=/private/tmp/river-osgiliath-79c4-gradle \
./gradlew --no-daemon \
  --project-cache-dir /private/tmp/river-osgiliath-79c4-cache \
  clean test verifySourcePolicy verifyModuleGraph :river-bench:installTps

clang -dynamiclib -O2 -fno-optimize-sibling-calls -Wall -Wextra -Werror \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin" \
  river-platform/src/main/native/macos/persisted_file_write.c \
  -o /Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/river-persisted-write.dylib

clang -O2 -Wall -Wextra -Werror \
  river-platform/src/main/native/macos/fullsync_capture.c \
  -o /Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/fullsync-capture
```

Use the private directories created before the build, on the same persistent
APFS volume. Leave `live` absent for the proposed runner to create privately;
require every final output file to be absent. After all controlled gates
and independent review pass, run exactly once from the capture worktree:

```sh
TMPDIR=/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/tmp \
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
tools/tps-test.sh --version=wal-admission-79c4da2e-native-capture-01 \
  --profile=tiny --mix=standard --terminals=4 --scheduling=no-wait-stress \
  --evidence=diagnostic --fresh-load=true --warehouses=1 --batch-rows=32 \
  --maximum-attempts=32 --warmup-seconds=2 --measured-seconds=60 \
  --seed=42 --isolation=serializable --sample-id=admission-resolution \
  --checkpoint-native-capture-dir=/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/live \
  --server-java-option=-Driver.diagnostics.persistedFileWrites=/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/write-boundary.jsonl \
  --server-java-option=-Driver.diagnostics.persistedFileWritesNativeLibrary=/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/river-persisted-write.dylib \
  --output-dir=/Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/run \
  --keep-output \
  > /Users/blater/src/river/benchmark-results/wal-79c4-native-capture-01/console.log 2>&1
```

This preserves every historical workload argument and keeps JFR disabled. The
capture patch, full-sync records and sampling still perturb timing and file
layout. A recurrence is diagnostic evidence; a pass cannot clear the original
incident or compare performance.

Retain the actual capture-branch commit, its diff from `79c4da2e`, the built
distribution and diagnostic library, exact commands and observed OS/JDK versions
with the run. A version label alone is not proof that the intended build ran.
Do not add a separate provenance framework.

The command above is not executable as-is on today's runner. The proposed flag,
collector and full-sync helper do not exist yet. The helper's invocation contract,
runner wiring, supported directory flush semantics and bounded failure handling
must be settled in the reviewed capture patch. Failure to establish those is an
execution blocker, not permission to substitute buffered output.

## Decision and stopping rule

Accept an initiating-write attribution only when a live completed sample and
durable log establish one exact PID/native-thread/time overlap, the exact sample
thread section contains the JNI bridge and write system-call path, and the BEGIN
supplies one coherent River stack, path, kind, offset and count. A subsequent
panic must name that same PID/thread before its kernel frames are joined to the
live record.

Classify the single attempt as inconclusive if the fault does not recur, the
sampler was not demonstrably ready, BEGIN is unmatched
without a same-thread sample, RETURN persistence is ambiguous, the sample cannot
inspect the process, IDs or times do not join exactly, the relevant files are not
durable after panic, or the obstruction is outside the instrumented write
boundary. Stop after that result. Do not rerun automatically, tune the workload,
weaken durability, change host security, replace the provider or implement a
kernel-cause fix from incomplete evidence.

## Independent adversarial review disposition

The independent Luna/high reviewer accepted this as a bounded written proposal,
not as an execution-ready procedure. Their final comments and dispositions are:

1. **Build directory ordering:** the first draft compiled into a directory it
   only instructed the operator to create afterward. Corrected above by moving
   directory creation before both compiler commands.
2. **Sampler readiness (execution blocker):** no concrete observable readiness
   predicate is yet established. Process creation or a delay is insufficient.
3. **Native sample persistence (execution blocker):** the proposed full-sync
   helper's invocation, runner wiring, failure handling and retained status are
   not implemented or validated.
4. **Aggregate sample attribution (execution blocker):** retain sample intervals
   and enough operation state to establish one candidate invocation and its
   continuity to the kernel evidence. Multiple possible writes on a carrier
   make the result ambiguous.
5. **Checkpoint marker:** it is only a collection cue. The captured stack must
   independently establish actual checkpoint execution.
6. **Historical-source identity:** retain the actual patched commit, diff, build
   and environment. Do not interpret the old source using current shutdown or
   response diagnostics.

The reviewer accepted removal of the premature 120-second cutoff, preservation
of paired records, explicit timing perturbation and uncovered-operation limits,
and the distinction between native userspace samples and kernel panic evidence.
No workload is authorized by this review disposition; this document records the
proposed method and the specific remaining admission blockers.
