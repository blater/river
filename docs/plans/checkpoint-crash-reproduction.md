# Checkpoint crash reproduction for later isolated execution

Owner: [tic-osgiliath](../tickets/tic-osgiliath.md). Original recipe, source bundle,
and all three raw panics: [tic-emeldir](../tickets/tic-emeldir.md).

The user selected preparation for later execution on 2026-09-14. This procedure
has **not** been executed. The controlled JDBC tests exercise returned I/O errors
and a releasable delayed write; they do not reproduce the kernel fault.

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

Set `server_pid` to that single observed PID. Capture once while work is healthy,
and again as the measured interval ends, before the 30-second client timeout.
Use distinct `capture` names, for example `healthy` and `checkpoint`.

```sh
server_pid=REPLACE_WITH_OBSERVED_PID
capture=healthy
date -u > "$incident_dir/$capture.time.txt"
ps -p "$server_pid" -o pid,ppid,state,etime,command > "$incident_dir/$capture.process.txt"
"$JAVA_HOME/bin/jcmd" "$server_pid" Thread.dump_to_file -format=json \
  "$incident_dir/$capture.threads.json" > "$incident_dir/$capture.jcmd.txt" 2>&1
/usr/sbin/lsof -nP -p "$server_pid" > "$incident_dir/$capture.files.txt" 2>&1
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
