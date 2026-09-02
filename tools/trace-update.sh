#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/trace-update.sh [options]

Trace one prepared JDBC UPDATE plus COMMIT through a managed River loopback
server. The trace directory is retained for inspection.

Options:
  --port=N                    Managed loopback port (default: 0, auto-select)
  --output-dir=PATH           Trace output directory (default: temporary)
  -h, --help                  Show this help

The report includes per-step wall/CPU/non-CPU time, thread allocation,
protocol round trips, bytes, and selected client/server JFR views.
EOF
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
river_root=$(cd -- "$script_dir/.." && pwd)

port=0
output_dir=
while (($# > 0)); do
  case $1 in
    --port=*) port=${1#*=} ;;
    --output-dir=*) output_dir=${1#*=} ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

java_bin=${RIVER_JAVA:-java}
if ! command -v "$java_bin" >/dev/null 2>&1; then
  echo "error: Java launcher not found: $java_bin" >&2
  exit 1
fi
jfr_bin=${RIVER_JFR:-jfr}
if ! command -v "$jfr_bin" >/dev/null 2>&1; then
  echo "error: JFR tool not found: $jfr_bin" >&2
  exit 1
fi

trace_main_class="$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccUpdateTraceMain.class"
required_classes=(
  "$trace_main_class"
  "$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccTraceRecording.class"
  "$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccTraceStep.class"
  "$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccServerMain.class"
  "$river_root/river-engine/build/classes/java/main/io/riverdb/engine/EmbeddedRiver.class"
  "$river_root/river-server/build/classes/java/main/io/riverdb/server/LoopbackRiverServer.class"
  "$river_root/river-jdbc/build/classes/java/main/io/riverdb/jdbc/RiverDriver.class"
)
build_required=false
for required_class in "${required_classes[@]}"; do
  if [[ ! -f $required_class ]]; then
    build_required=true
    break
  fi
done
if [[ $build_required == false ]]; then
  for source in \
      "$river_root/river-bench/src/main/java/io/riverdb/bench/tpcc/TpccUpdateTraceMain.java" \
      "$river_root/river-bench/src/main/java/io/riverdb/bench/tpcc/TpccTraceRecording.java" \
      "$river_root/river-bench/src/main/java/io/riverdb/bench/tpcc/TpccTraceStep.java" \
      "$river_root/river-bench/src/main/java/io/riverdb/bench/tpcc/TpccServerMain.java"; do
    if [[ $source -nt $trace_main_class ]]; then
      build_required=true
      break
    fi
  done
fi
if [[ $build_required == true ]]; then
  if [[ ${RIVER_TPS_SKIP_BUILD:-false} == true ]]; then
    echo "error: trace classes are missing/stale and RIVER_TPS_SKIP_BUILD=true" >&2
    exit 1
  fi
  gradle_bin=${RIVER_GRADLE:-$river_root/gradlew}
  echo "Building River update trace classes (required)"
  "$gradle_bin" :river-bench:classes
fi

class_path=("$river_root/river-bench/build/classes/java/main")
for module in \
    river-base river-observability-api river-platform river-format river-tx-api river-wal \
    river-buffer river-storage river-tx river-recovery river-backup river-catalog river-sql \
    river-planner river-exec river-engine-api river-engine river-protocol river-client \
    river-server river-jdbc; do
  class_dir="$river_root/$module/build/classes/java/main"
  if [[ -d $class_dir ]]; then class_path+=("$class_dir"); fi
  for jar in "$river_root/$module"/build/libs/*.jar; do
    if [[ -f $jar && $jar != *-sources.jar ]]; then class_path+=("$jar"); fi
  done
done
classpath=$(IFS=:; echo "${class_path[*]}")

if [[ -z $output_dir ]]; then
  output_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-update-trace.XXXXXX")
else
  if [[ -e $output_dir ]]; then
    echo "error: refusing to overwrite existing trace directory: $output_dir" >&2
    exit 1
  fi
  mkdir -p -- "$output_dir"
fi
trace_settings="$output_dir/trace.jfc"

"$jfr_bin" configure --output "$trace_settings" \
  jdk.SocketRead#threshold=0ns \
  jdk.SocketRead#stackTrace=true \
  jdk.SocketWrite#threshold=0ns \
  jdk.SocketWrite#stackTrace=true \
  jdk.ExecutionSample#period=1ms \
  jdk.FileRead#threshold=0ns \
  jdk.FileWrite#threshold=0ns \
  jdk.FileForce#threshold=0ns \
  jdk.ThreadPark#threshold=0ns \
  jdk.JavaMonitorEnter#threshold=0ns \
  jdk.ObjectAllocationInNewTLAB#enabled=true \
  jdk.ObjectAllocationInNewTLAB#stackTrace=true \
  jdk.ObjectAllocationOutsideTLAB#enabled=true \
  jdk.ObjectAllocationOutsideTLAB#stackTrace=true \
  >/dev/null

server_pid=
server_ready="$output_dir/server.ready"
server_start="$output_dir/server.trace.start"
server_started="$output_dir/server.trace.started"
server_stop="$output_dir/server.stop"
server_log="$output_dir/server.log"
trace_output="$output_dir/trace-output.log"
client_jfr="$output_dir/client.jfr"
server_jfr="$output_dir/server.jfr"

print_jfr_summary() {
  "$jfr_bin" summary "$1" | rg \
    '^( Version:| Chunks:| Start:| Duration:| Event Type|=+| (jdk\.(CPUTimeSample|ExecutionSample|SocketRead|SocketWrite|FileRead|FileWrite|FileForce|ThreadPark|JavaMonitorEnter|ObjectAllocationInNewTLAB|ObjectAllocationOutsideTLAB)|io\.riverdb\.bench\.tpcc\.JdbcTraceStep))'
}

print_execution_sample_tops() {
  "$jfr_bin" print --events jdk.ExecutionSample --stack-depth 8 "$1" | awk '
    /^jdk\.ExecutionSample/ {
      if (thread != "" && top != "") print "sample_thread=" thread " top=" top
      thread = ""
      top = ""
    }
    /^  sampledThread = / {
      line = $0
      sub(/^  sampledThread = /, "", line)
      thread = line
    }
    /^    [^ ]/ && top == "" {
      top = $0
      sub(/^    /, "", top)
    }
    END {
      if (thread != "" && top != "") print "sample_thread=" thread " top=" top
    }
  ' | rg 'top=(io\.riverdb|sun\.nio|java\.nio|java\.net)' | sed -n '1,20p' || true
}

print_file_io() {
  "$jfr_bin" print --events jdk.FileForce,jdk.FileWrite --stack-depth 0 "$1" \
    | rg '^(jdk\.(FileForce|FileWrite)|  startTime =|  duration =|  path =|  bytesWritten =|  eventThread =)' \
    || true
}

print_jdbc_trace_steps() {
  "$jfr_bin" print --events io.riverdb.bench.tpcc.JdbcTraceStep "$1" \
    | rg '^(io\.riverdb\.bench\.tpcc\.JdbcTraceStep|  startTime =|  step =|  outcome =|  wallNanos =|  cpuNanos =|  nonCpuNanos =|  allocatedBytes =|  protocolRequests =|  bytesSent =|  bytesReceived =)' \
    || true
}

stop_server() {
  if [[ -n ${server_pid} ]]; then
    if [[ ! -f $server_stop ]]; then : >"$server_stop"; fi
    if kill -0 "$server_pid" 2>/dev/null; then
      for ((attempt = 0; attempt < 100; attempt++)); do
        if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
        sleep 0.1
      done
      if kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
      fi
    fi
    wait "$server_pid" 2>/dev/null || true
    server_pid=
  fi
}
trap stop_server EXIT

"$java_bin" -cp "$classpath" \
  io.riverdb.bench.tpcc.TpccServerMain \
  "--directory=$output_dir/database" \
  "--port=$port" \
  "--maximum-connections=16" \
  "--ready-file=$server_ready" \
  "--jfr=$server_jfr" \
  "--trace-start-file=$server_start" \
  "--trace-started-file=$server_started" \
  "--stop-file=$server_stop" \
  >"$server_log" 2>&1 &
server_pid=$!
for ((attempt = 0; attempt < 100; attempt++)); do
  if [[ -f $server_ready ]]; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "error: managed trace server exited during startup" >&2
    sed -n '1,160p' "$server_log" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ ! -f $server_ready ]]; then
  echo "error: managed trace server did not become ready" >&2
  sed -n '1,160p' "$server_log" >&2
  exit 1
fi
managed_port=$(<"$server_ready")
url="jdbc:river://localhost:$managed_port"
echo "managed_server=started port=$managed_port"
echo "trace_directory=$output_dir"

set +e
"$java_bin" \
  "-XX:StartFlightRecording=filename=$client_jfr,settings=$trace_settings,dumponexit=true" \
  -cp "$classpath" \
  io.riverdb.bench.tpcc.TpccUpdateTraceMain \
  "--url=$url" \
  "--jfr=$client_jfr" \
  "--external-jfr=true" \
  "--server-start-file=$server_start" \
  "--server-started-file=$server_started" \
  2>&1 | tee "$trace_output"
client_status=${PIPESTATUS[0]}
set -e

if ((client_status != 0)); then
  echo "trace_result=failed" >&2
  sed -n '1,240p' "$server_log" >&2
else
  echo
  echo "=== trace interpretation ==="
  echo "protocol_note=execute_update includes lazy BEGIN plus EXECUTE_PREPARED"
  echo "request_mapping=connect:HELLO+OPEN_SESSION; prepare:PREPARE; execute_update:BEGIN+EXECUTE_PREPARED; commit:COMMIT; close_statement:CLOSE_PREPARED; close_connection:CLOSE_SESSION"
  echo "wait_note=non_cpu_ms is wall time minus client-thread CPU; JFR socket views show network wait"
  echo "allocation_note=step_allocated_bytes is direct thread allocation; JFR allocation views include recorder overhead"
  echo "cpu_note=JFR CPUTimeSample is platform-dependent; exact step CPU uses ThreadMXBean and where uses ExecutionSample"
  echo "jfr_scope_note=aggregate JFR views cover JVM recording lifetime; per-step rows are transaction-scoped"
  echo
  echo "=== client JDBC trace events ==="
  print_jdbc_trace_steps "$client_jfr"
  echo
  echo "=== client JFR summary ==="
  print_jfr_summary "$client_jfr"
  echo
  echo "=== client CPU hot methods ==="
  "$jfr_bin" view --width 140 cpu-time-hot-methods "$client_jfr" | sed -n '1,80p'
  "$jfr_bin" view --width 140 cpu-time-statistics "$client_jfr" | sed -n '1,80p'
  echo
  echo "=== client sampled execution tops (River/JDK I/O frames) ==="
  print_execution_sample_tops "$client_jfr"
  echo
  echo "=== client socket waits ==="
  "$jfr_bin" view --width 140 latencies-by-type "$client_jfr" | sed -n '1,80p'
  "$jfr_bin" view --width 140 socket-reads-by-host "$client_jfr" | sed -n '1,80p'
  "$jfr_bin" view --width 140 socket-writes-by-host "$client_jfr" | sed -n '1,80p'
  echo
  echo "=== client allocation ==="
  "$jfr_bin" view --width 140 thread-allocation "$client_jfr" | sed -n '1,60p'
  "$jfr_bin" view --width 140 allocation-by-class "$client_jfr" | sed -n '1,30p'
fi

: >"$server_stop"
stop_server
if [[ -f $server_jfr ]]; then
  echo
  echo "=== server JFR summary ==="
  print_jfr_summary "$server_jfr"
  echo
  echo "=== server CPU hot methods ==="
  "$jfr_bin" view --width 140 cpu-time-hot-methods "$server_jfr" | sed -n '1,100p'
  "$jfr_bin" view --width 140 cpu-time-statistics "$server_jfr" | sed -n '1,80p'
  echo
  echo "=== server sampled execution tops (River/JDK I/O frames) ==="
  print_execution_sample_tops "$server_jfr"
  echo
  echo "=== server socket waits ==="
  "$jfr_bin" view --width 140 latencies-by-type "$server_jfr" | sed -n '1,80p'
  "$jfr_bin" view --width 140 socket-reads-by-host "$server_jfr" | sed -n '1,80p'
  "$jfr_bin" view --width 140 socket-writes-by-host "$server_jfr" | sed -n '1,80p'
  echo
  echo "=== server file I/O (durable path) ==="
  print_file_io "$server_jfr"
  echo "lock_note=JFR monitor/park waits are reported above; logical River lock waits are not present in this uncontended single-transaction run, and lock escalation is not implemented in the current engine"
  echo
  echo "=== server allocation ==="
  "$jfr_bin" view --width 140 thread-allocation "$server_jfr" | sed -n '1,60p'
  "$jfr_bin" view --width 140 allocation-by-class "$server_jfr" | sed -n '1,30p'
fi

exit "$client_status"
