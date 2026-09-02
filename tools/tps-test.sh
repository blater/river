#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/tps-test.sh [options]

Run River's JDBC TPC-C workload for a configurable warmup and measured
no-wait throughput interval. By default this tool builds
the bench classes when their compiled outputs are missing, creates a temporary
database, starts a loopback River server, and removes the temporary database
when it exits.

Options:
  --port=N                    Managed loopback port (default: 0, auto-select)
  --warehouses=N              Tiny workload warehouses (default: 1)
  --terminals=N               Concurrent workload terminals (default: 10)
  --batch-rows=N              JDBC batch size (default: 32)
  --maximum-attempts=N        Maximum attempts for one transaction (default: 32)
  --warmup-seconds=N          Warmup interval (default: 1)
  --measured-seconds=N        Measured interval (default: 10)
  --jfr=PATH                  Write a client measured-phase JFR recording
  --server-jfr=PATH           Write a managed-server JFR recording
  --seed=N                    Workload random seed
  --artifact=PATH             Keep the acceptance properties at PATH
  -h, --help                  Show this help

The summary reports committed transactions, retry attempts, errors
(retry-exhausted plus failed transactions), and committed transactions/second.
When --jfr is supplied without --server-jfr, the managed-server recording is
written beside it with a .server.jfr suffix and covers server startup through
shutdown, including load, preflight, and the measured interval.
EOF
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
river_root=$(cd -- "$script_dir/.." && pwd)

port=0
warehouses=1
terminals=10
batch_rows=32
maximum_attempts=32
warmup_seconds=1
measured_seconds=10
seed=
artifact=
jfr=
server_jfr=

while (($# > 0)); do
  case $1 in
    --port=*) port=${1#*=} ;;
    --warehouses=*) warehouses=${1#*=} ;;
    --terminals=*) terminals=${1#*=} ;;
    --batch-rows=*) batch_rows=${1#*=} ;;
    --maximum-attempts=*) maximum_attempts=${1#*=} ;;
    --warmup-seconds=*) warmup_seconds=${1#*=} ;;
    --measured-seconds=*) measured_seconds=${1#*=} ;;
    --jfr=*) jfr=${1#*=} ;;
    --server-jfr=*) server_jfr=${1#*=} ;;
    --seed=*) seed=${1#*=} ;;
    --artifact=*) artifact=${1#*=} ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -n ${jfr} && -z ${server_jfr} ]]; then
  case $jfr in
    *.jfr) server_jfr="${jfr%.jfr}.server.jfr" ;;
    *) server_jfr="$jfr.server.jfr" ;;
  esac
fi

java_bin=${RIVER_JAVA:-java}
if ! command -v "$java_bin" >/dev/null 2>&1; then
  echo "error: Java launcher not found: $java_bin" >&2
  exit 1
fi

required_classes=(
  "$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccAcceptanceMain.class"
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
  build_marker=${required_classes[0]}
  for source_root in "$river_root"/river-*/src/main; do
    if [[ ! -d $source_root ]]; then
      continue
    fi
    newer_source=$(find "$source_root" -type f -newer "$build_marker" -print -quit)
    if [[ -n $newer_source ]]; then
      build_required=true
      break
    fi
  done
fi
if [[ $build_required == true ]]; then
  if [[ ${RIVER_TPS_SKIP_BUILD:-false} == true ]]; then
    echo "error: compiled River TPS classes are missing and RIVER_TPS_SKIP_BUILD=true" >&2
    exit 1
  fi
  gradle_bin=${RIVER_GRADLE:-$river_root/gradlew}
  echo "Building River TPS classes (required)"
  "$gradle_bin" :river-bench:classes
fi

class_path=("$river_root/river-bench/build/classes/java/main")
bench_main_found=false
if [[ -f "$river_root/river-bench/build/classes/java/main/io/riverdb/bench/tpcc/TpccAcceptanceMain.class" ]]; then
  bench_main_found=true
fi
for jar in "$river_root/river-bench"/build/libs/*.jar; do
  if [[ -f $jar && $jar != *-sources.jar ]]; then
    class_path+=("$jar")
    bench_main_found=true
  fi
done
modules=(
  river-base
  river-observability-api
  river-platform
  river-format
  river-tx-api
  river-wal
  river-buffer
  river-storage
  river-tx
  river-recovery
  river-backup
  river-catalog
  river-sql
  river-planner
  river-exec
  river-engine-api
  river-engine
  river-protocol
  river-client
  river-server
  river-jdbc
)
for module in "${modules[@]}"; do
  class_dir="$river_root/$module/build/classes/java/main"
  if [[ -d $class_dir ]]; then
    class_path+=("$class_dir")
  fi
  for jar in "$river_root/$module"/build/libs/*.jar; do
    if [[ -f $jar && $jar != *-sources.jar ]]; then
      class_path+=("$jar")
    fi
  done
done

if [[ $bench_main_found != true ]]; then
  echo "error: compiled TPC-C classes or JAR not found under river-bench/build" >&2
  echo "hint: build the River classes separately, then rerun this tool" >&2
  exit 1
fi

classpath=$(IFS=:; echo "${class_path[*]}")

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-test.XXXXXX")
server_pid=
server_stop=
stop_server() {
  if [[ -n ${server_pid} ]]; then
    if [[ -n ${server_stop} && ! -f ${server_stop} ]]; then
      : >"$server_stop"
    fi
    if kill -0 "$server_pid" 2>/dev/null; then
      for ((attempt = 0; attempt < 100; attempt++)); do
        if ! kill -0 "$server_pid" 2>/dev/null; then
          break
        fi
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
cleanup() {
  stop_server
  rm -rf -- "$temp_dir"
}
trap cleanup EXIT

if [[ -z ${artifact} ]]; then
  artifact="$temp_dir/tpcc-acceptance.properties"
fi

server_ready="$temp_dir/server.ready"
server_log="$temp_dir/server.log"
server_stop="$temp_dir/server.stop"
server_metrics="$temp_dir/server-metrics.log"
maximum_connections=$((terminals + 4))
if ((maximum_connections < 16)); then
  maximum_connections=16
elif ((maximum_connections > 1024)); then
  maximum_connections=1024
fi
server_args=(
  "--directory=$temp_dir/database"
  "--port=$port"
  "--maximum-connections=$maximum_connections"
  "--ready-file=$server_ready"
  "--stop-file=$server_stop"
  "--metrics-file=$server_metrics"
)
if [[ -n ${server_jfr} ]]; then
  server_args+=("--jfr=$server_jfr")
fi
"$java_bin" \
  -cp "$classpath" \
  io.riverdb.bench.tpcc.TpccServerMain \
  "${server_args[@]}" \
  >"$server_log" 2>&1 &
server_pid=$!
server_ready_status=false
for ((attempt = 0; attempt < 100; attempt++)); do
  if [[ -f $server_ready ]]; then
    server_ready_status=true
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "error: managed TPS server exited during startup" >&2
    sed -n '1,120p' "$server_log" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ $server_ready_status != true ]]; then
  echo "error: managed TPS server did not become ready" >&2
  sed -n '1,120p' "$server_log" >&2
  exit 1
fi
managed_port=$(<"$server_ready")
url="jdbc:river://localhost:$managed_port"
echo "managed_server=started port=$managed_port"
if [[ -n ${server_jfr} ]]; then
  echo "managed_server_jfr=$server_jfr"
fi

runner_args=(
  "--url=$url"
  "--tiny"
  "--fresh-load=true"
  "--warmup-seconds=$warmup_seconds"
  "--measured-seconds=$measured_seconds"
  "--scheduling=no-wait-stress"
  "--warehouses=$warehouses"
  "--terminals=$terminals"
  "--batch-rows=$batch_rows"
  "--maximum-attempts=$maximum_attempts"
  "--artifact=$artifact"
)
if [[ -n ${seed} ]]; then
  runner_args+=("--seed=$seed")
fi
if [[ -n ${jfr} ]]; then
  runner_args+=("--jfr=$jfr")
fi

log_file="$temp_dir/tpcc-output.log"
runner_main=io.riverdb.bench.tpcc.TpccAcceptanceMain

echo "Running $measured_seconds seconds of River TPS testing against $url"
echo "warmup_seconds=$warmup_seconds measured_seconds=$measured_seconds scheduling=no-wait-stress"

set +e
"$java_bin" \
  -cp "$classpath" \
  "$runner_main" \
  "${runner_args[@]}" 2>&1 | tee "$log_file"
runner_status=${PIPESTATUS[0]}
set -e

if ((runner_status != 0)); then
  echo "managed_server_status=failed" >&2
  if [[ -f $server_log ]]; then
    echo "=== managed server log ===" >&2
    sed -n '1,240p' "$server_log" >&2
    echo "=== end managed server log ===" >&2
  else
    echo "managed server log unavailable: $server_log" >&2
  fi
fi

stop_server

if [[ -f $server_metrics ]]; then
  echo
  echo "=== managed server lock metrics ==="
  sed -n '1,40p' "$server_metrics"
fi

if [[ -n ${jfr} && -f ${jfr} ]]; then
  echo
  echo "=== TPS execution profile ==="
  if ! "$script_dir/jfr-flamegraph.sh" --jfr="$jfr" --top=25; then
    echo "warning: unable to render the TPS JFR execution profile" >&2
  fi
fi

if [[ -n ${server_jfr} && -f ${server_jfr} ]]; then
  echo
  echo "=== managed server execution profile ==="
  if ! "$script_dir/jfr-flamegraph.sh" --jfr="$server_jfr" --top=25; then
    echo "warning: unable to render the managed-server JFR execution profile" >&2
  fi
fi

measurement_reported=false
if [[ -f $log_file ]] && rg -q '^transaction=' "$log_file"; then
  measurement_reported=true
fi
if ((runner_status != 0)) && [[ $measurement_reported != true ]]; then
  failure_phase=load
  if [[ -f $log_file ]] && rg -q '^pre_run_invariants=passed$' "$log_file"; then
    failure_phase=preflight
  fi
  if [[ -f $log_file ]] && rg -q '^post_run_invariants=passed' "$log_file"; then
    failure_phase=checkpoint
  fi
  failure_status=
  if [[ -f $log_file ]]; then
    failure_status=$(sed -nE 's/.*SQLException: .*: ([A-Z_]+)$/\1/p' "$log_file" | tail -n 1)
  fi
  if [[ -z $failure_status ]]; then
    failure_status=UNKNOWN
  fi
  echo
  echo "=== TPS result ==="
  echo "result=${failure_phase}_failed"
  echo "phase=$failure_phase"
  echo "status=$failure_status"
  echo "duration_seconds=$measured_seconds"
  echo "tps=unavailable"
  exit "$runner_status"
fi

if [[ -f $log_file ]]; then
  summary=$(awk -v seconds="$measured_seconds" '
  /^whole_transaction_retries=/ {
    split($0, field, "=")
    retries = field[2] + 0
  }
  /^transaction=/ {
    for (field_index = 1; field_index <= NF; field_index++) {
      split($field_index, field, "=")
      if (field[1] == "committed") commits += field[2] + 0
      if (field[1] == "retry_exhausted") errors += field[2] + 0
      if (field[1] == "failed") errors += field[2] + 0
    }
  }
  END {
    printf "%d %d %d %.3f\n", retries, errors, commits, commits / seconds
  }
' "$log_file")
else
  echo "error: TPS runner did not produce its output log: $log_file" >&2
  summary="0 1 0 0.000"
fi
read -r retries errors commits tps <<<"$summary"

echo
echo "=== TPS result ==="
if ((runner_status == 0)); then
  echo "result=completed"
  echo "phase=checkpoint"
  echo "status=OK"
else
  echo "result=failed"
  echo "phase=measured_or_post_measurement"
  echo "status=UNKNOWN"
fi
echo "duration_seconds=$measured_seconds"
echo "retries=$retries"
echo "errors=$errors"
echo "commits=$commits"
echo "tps=$tps"

exit "$runner_status"
