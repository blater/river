#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

usage() {
  cat <<'EOF'
Usage: tools/tps-test.sh [options]

Run one River JDBC TPC-C engineering sample. Run ./make.sh first to compile
River and prepare its runtime classpath. This tool never builds. It owns a
temporary database and loopback server,
keeps output safe on every exit path, and reports load, preflight, warmup,
measured, drain, and checkpoint failures distinctly.

Options:
  --backend=river|mariadb       Backend (current Java path: river only)
  --profile=tiny|standard       Workload scale (default: tiny)
  --mix=standard|new-order|payment|new-order-payment-50-50|new-order-delivery-50-50|new-order-stock-level-50-50
                                Transaction-family mix (default: standard)
  --scheduling=standard|no-wait-stress
                                Scheduling profile (default: no-wait-stress)
  --evidence=diagnostic|alpha3 Evidence mode (default: diagnostic)
  --fresh-load=true|false       Load database before run (default: true)
  --port=N                      Managed loopback port (default: 0)
  --warehouses=N                Warehouses (default: 1)
  --terminals=N                 Concurrent terminals (default: 10)
  --batch-rows=N                Load batch size (default: 32)
  --maximum-attempts=N          Attempts per transaction (default: 32)
  --warmup-seconds=N            Warmup interval (default: 1)
  --measured-seconds=N          Measured interval (default: 10)
  --runner-timeout-seconds=N    Hard runner timeout (default: interval + 300)
  --server-start-timeout-seconds=N
                                Server readiness timeout (default: 30)
  --server-stop-timeout-seconds=N
                                Graceful server-stop timeout (default: 20)
  --resource-maximum-bytes=N    Managed database root budget (default: 1073741824)
  --resource-delivery-bytes=N   Aggregate transaction/WAL budget (default: 268435456)
  --resource-lock-provider-bytes=N
                                Lock-provider budget (default: 67108864)
  --resource-version-workspace-bytes=N
                                Version-operation workspace budget (default: 67108864)
  --resource-page-cache-bytes=N Page-cache budget (default: 268435456)
  --resource-staging-frame-bytes=N
                                Page-cache staging budget (default: 67108864)
  --resource-staged-page-capacity=N
                                Aggregate staged-page admission (default: 4096)
  --deadlock-diagnostics-bytes=N
                                Retained diagnostic payload budget; 0 disables (default: 0)
  --deadlock-diagnostics-epochs=N
                                Retained metrics epochs; required when enabled
  --deadlock-diagnostics-signatures-per-epoch=N
                                Cycle fingerprints per epoch; required when enabled
  --deadlock-diagnostics-events-per-epoch=N
                                Correlated victim events per epoch; required when enabled
  --deadlock-diagnostics-exemplars-per-signature=N
                                Full cycle exemplars per fingerprint; required when enabled
  --deadlock-diagnostics-maximum-cycle-edges=N
                                Edges retained in one exemplar; required when enabled
  --retry-base-micros=N         Retry base delay
  --retry-maximum-millis=N      Retry maximum delay
  --seed=N                      Workload random seed
  --jfr=PATH                    Client measured-phase JFR
  --server-jfr=PATH             Managed-server JFR
  --client-java-option=OPTION   Client JVM option; repeatable
  --server-java-option=OPTION   Server JVM option; repeatable
  --output-dir=PATH             Preserve evidence; must be empty or absent
  --artifact=PATH               Acceptance artifact path
  --metadata=PATH               Tool metadata path
  --sample-id=ID                Persisted sample identity
  --keep-output                 Keep temporary run directory
  --isolation=serializable|repeatable-read|mixed-diagnostic
                                Declared JDBC/program isolation (default: serializable)
  -h, --help                    Show this help

The current Java path supports jdbc:river, all listed diagnostic mixes,
and explicit serializable, repeatable-read, or mixed-diagnostic isolation.
MariaDB remains unavailable because the Java acceptance path validates
jdbc:river. Java-emitted metrics are printed verbatim when present; unavailable
engine-private metrics are not fabricated.

EOF
}

die() {
  echo "error: $*" >&2
  exit 2
}

require_uint() {
  local name=$1
  local value=$2
  [[ $value =~ ^[0-9]+$ ]] || die "$name must be a non-negative integer: $value"
}

require_positive() {
  local name=$1
  local value=$2
  require_uint "$name" "$value"
  ((value > 0)) || die "$name must be greater than zero"
}

absolute_path() {
  case $1 in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

hash_file() {
  if [[ -f $1 ]]; then
    provenance_sha256_file "$1"
  else
    printf '%s\n' unavailable
  fi
}

hash_text() {
  provenance_sha256_text "$1"
}

property() {
  local key=$1
  local file=$2
  [[ -f $file ]] || return 0
  sed -n "s/^$key=//p" "$file" | head -1
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
river_root=$(cd -- "$script_dir/.." && pwd)
source "$script_dir/tps-provenance.sh"

backend=river
profile=tiny
mix=standard
scheduling=no-wait-stress
evidence=diagnostic
fresh_load=true
port=0
warehouses=1
terminals=10
batch_rows=32
maximum_attempts=32
warmup_seconds=1
measured_seconds=10
runner_timeout_seconds=
server_start_timeout_seconds=30
server_stop_timeout_seconds=20
resource_maximum_bytes=1073741824
resource_delivery_bytes=268435456
resource_lock_provider_bytes=67108864
resource_version_workspace_bytes=67108864
resource_page_cache_bytes=268435456
resource_staging_frame_bytes=67108864
resource_staged_page_capacity=4096
deadlock_diagnostics_bytes=0
deadlock_diagnostics_epochs=0
deadlock_diagnostics_signatures_per_epoch=0
deadlock_diagnostics_events_per_epoch=0
deadlock_diagnostics_exemplars_per_signature=0
deadlock_diagnostics_maximum_cycle_edges=0
retry_base_micros=
retry_maximum_millis=
seed=
client_jfr=
server_jfr=
output_dir=
artifact=
metadata=
sample_id=single
keep_output=false
isolation=serializable
client_java_options=()
server_java_options=()
original_arguments=("$@")
for original_argument in "${original_arguments[@]}"; do
  [[ $original_argument != *$'\n'* && $original_argument != *$'\r'* ]] ||
    die "arguments must not contain line breaks"
done

while (($# > 0)); do
  case $1 in
    --backend=*) backend=${1#*=} ;;
    --profile=*) profile=${1#*=} ;;
    --mix=*) mix=${1#*=} ;;
    --scheduling=*) scheduling=${1#*=} ;;
    --evidence=*) evidence=${1#*=} ;;
    --fresh-load=*) fresh_load=${1#*=} ;;
    --port=*) port=${1#*=} ;;
    --warehouses=*) warehouses=${1#*=} ;;
    --terminals=*) terminals=${1#*=} ;;
    --batch-rows=*) batch_rows=${1#*=} ;;
    --maximum-attempts=*) maximum_attempts=${1#*=} ;;
    --warmup-seconds=*) warmup_seconds=${1#*=} ;;
    --measured-seconds=*) measured_seconds=${1#*=} ;;
    --runner-timeout-seconds=*) runner_timeout_seconds=${1#*=} ;;
    --server-start-timeout-seconds=*) server_start_timeout_seconds=${1#*=} ;;
    --server-stop-timeout-seconds=*) server_stop_timeout_seconds=${1#*=} ;;
    --resource-maximum-bytes=*) resource_maximum_bytes=${1#*=} ;;
    --resource-delivery-bytes=*) resource_delivery_bytes=${1#*=} ;;
    --resource-lock-provider-bytes=*) resource_lock_provider_bytes=${1#*=} ;;
    --resource-version-workspace-bytes=*) resource_version_workspace_bytes=${1#*=} ;;
    --resource-page-cache-bytes=*) resource_page_cache_bytes=${1#*=} ;;
    --resource-staging-frame-bytes=*) resource_staging_frame_bytes=${1#*=} ;;
    --resource-staged-page-capacity=*) resource_staged_page_capacity=${1#*=} ;;
    --deadlock-diagnostics-bytes=*) deadlock_diagnostics_bytes=${1#*=} ;;
    --deadlock-diagnostics-epochs=*) deadlock_diagnostics_epochs=${1#*=} ;;
    --deadlock-diagnostics-signatures-per-epoch=*) deadlock_diagnostics_signatures_per_epoch=${1#*=} ;;
    --deadlock-diagnostics-events-per-epoch=*) deadlock_diagnostics_events_per_epoch=${1#*=} ;;
    --deadlock-diagnostics-exemplars-per-signature=*) deadlock_diagnostics_exemplars_per_signature=${1#*=} ;;
    --deadlock-diagnostics-maximum-cycle-edges=*) deadlock_diagnostics_maximum_cycle_edges=${1#*=} ;;
    --retry-base-micros=*) retry_base_micros=${1#*=} ;;
    --retry-maximum-millis=*) retry_maximum_millis=${1#*=} ;;
    --seed=*) seed=${1#*=} ;;
    --jfr=*) client_jfr=${1#*=} ;;
    --server-jfr=*) server_jfr=${1#*=} ;;
    --client-java-option=*) client_java_options+=( "${1#*=}" ) ;;
    --server-java-option=*) server_java_options+=( "${1#*=}" ) ;;
    --output-dir=*) output_dir=${1#*=} ;;
    --artifact=*) artifact=${1#*=} ;;
    --metadata=*) metadata=${1#*=} ;;
    --sample-id=*) sample_id=${1#*=} ;;
    --keep-output) keep_output=true ;;
    --isolation=*) isolation=${1#*=} ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

case $backend in river) ;; *) die "backend=$backend is unsupported; only backend=river is available" ;; esac
case $profile in tiny|standard) ;; *) die "profile must be tiny or standard" ;; esac
case $mix in
  standard|new-order|payment|new-order-payment-50-50|new-order-delivery-50-50|new-order-stock-level-50-50) ;;
  *) die "unknown workload mix: $mix" ;;
esac
case $scheduling in standard|no-wait-stress) ;; *) die "unknown scheduling profile: $scheduling" ;; esac
case $evidence in diagnostic|alpha3) ;; *) die "unknown evidence mode: $evidence" ;; esac
case $fresh_load in true|false) ;; *) die "fresh-load must be true or false" ;; esac
case $isolation in serializable|repeatable-read|mixed-diagnostic) ;; *) die "unknown isolation contract: $isolation" ;; esac
[[ $sample_id =~ ^[A-Za-z0-9._-]+$ ]] ||
  die "sample-id must contain only letters, digits, dot, underscore, or hyphen"

require_uint port "$port"; ((port <= 65535)) || die "port is outside 0..65535"
require_positive warehouses "$warehouses"
require_positive terminals "$terminals"
require_positive batch_rows "$batch_rows"
require_positive maximum_attempts "$maximum_attempts"
require_positive warmup_seconds "$warmup_seconds"
require_positive measured_seconds "$measured_seconds"
require_positive server_start_timeout_seconds "$server_start_timeout_seconds"
require_positive server_stop_timeout_seconds "$server_stop_timeout_seconds"
require_positive resource_maximum_bytes "$resource_maximum_bytes"
require_positive resource_delivery_bytes "$resource_delivery_bytes"
require_positive resource_lock_provider_bytes "$resource_lock_provider_bytes"
require_positive resource_version_workspace_bytes "$resource_version_workspace_bytes"
require_positive resource_page_cache_bytes "$resource_page_cache_bytes"
require_positive resource_staging_frame_bytes "$resource_staging_frame_bytes"
require_positive resource_staged_page_capacity "$resource_staged_page_capacity"
require_uint deadlock_diagnostics_bytes "$deadlock_diagnostics_bytes"
require_uint deadlock_diagnostics_epochs "$deadlock_diagnostics_epochs"
require_uint deadlock_diagnostics_signatures_per_epoch "$deadlock_diagnostics_signatures_per_epoch"
require_uint deadlock_diagnostics_events_per_epoch "$deadlock_diagnostics_events_per_epoch"
require_uint deadlock_diagnostics_exemplars_per_signature "$deadlock_diagnostics_exemplars_per_signature"
require_uint deadlock_diagnostics_maximum_cycle_edges "$deadlock_diagnostics_maximum_cycle_edges"
if [[ -n $runner_timeout_seconds ]]; then require_positive runner_timeout_seconds "$runner_timeout_seconds";
else runner_timeout_seconds=$((warmup_seconds + measured_seconds + 300)); fi
if [[ -n $retry_base_micros ]]; then require_positive retry_base_micros "$retry_base_micros"; fi
if [[ -n $retry_maximum_millis ]]; then require_positive retry_maximum_millis "$retry_maximum_millis"; fi
if [[ -n $seed ]]; then require_uint seed "$seed"; fi

java_bin=${RIVER_JAVA:-java}
command -v "$java_bin" >/dev/null 2>&1 || die "Java launcher not found: $java_bin"
java_launcher_path=$(command -v "$java_bin")
java_launcher_sha256=$(hash_file "$java_launcher_path")
java_runtime_home=$(
  "$java_bin" -XshowSettings:properties -version 2>&1 |
    sed -n 's/^[[:space:]]*java\.home = //p' | head -1
)
if [[ -n $client_jfr && -z $server_jfr ]]; then
  case $client_jfr in
    *.jfr) server_jfr="${client_jfr%.jfr}.server.jfr" ;;
    *) server_jfr="$client_jfr.server.jfr" ;;
  esac
fi
if [[ -n $client_jfr && -n $server_jfr && $client_jfr == "$server_jfr" ]]; then
  die "client and server JFR destinations must differ"
fi

if [[ -n $output_dir ]]; then
  output_dir=$(absolute_path "$output_dir")
  case $output_dir/ in
    "$river_root"/*) die "output-dir must be outside the source workspace" ;;
  esac
  [[ ! -e $output_dir || -d $output_dir ]] || die "output-dir is not a directory: $output_dir"
  mkdir -p "$output_dir"
  [[ -z $(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
    die "output-dir must be empty to prevent overwriting evidence: $output_dir"
fi

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-test.XXXXXX")
server_pid=
runner_pid=
server_stop=
runner_status=125
runner_timed_out=false
run_result=startup_failed
run_phase=startup
run_status=NOT_STARTED
run_exit_status=1
started_epoch=$(date +%s)
source_stable=true
host_exclusion_valid=true
publication_valid=true
persistence_valid=true
artifact_published=false
temp_cleanup_valid=true
terminal_publication_valid=false
workspace_start_sha256=unavailable
workspace_finish_sha256=unavailable
evidence_run_id=$(provenance_random_hex) || die "unable to generate evidence run identity"
terminal_nonce=$(provenance_random_hex) || die "unable to generate terminal commitment nonce"
if [[ -z $artifact ]]; then
  if [[ -n $output_dir ]]; then artifact_destination="$output_dir/tpcc-acceptance.properties";
  else artifact_destination="$temp_dir/tpcc-acceptance.properties"; fi
else artifact_destination=$(absolute_path "$artifact"); fi
artifact="$temp_dir/tpcc-acceptance.staged.properties"
if [[ -z $metadata ]]; then
  if [[ -n $output_dir ]]; then metadata="$output_dir/run-metadata.properties";
  else metadata="$temp_dir/run-metadata.properties"; fi
else metadata=$(absolute_path "$metadata"); fi
terminal_receipt_destination="${metadata}.terminal-receipt"
if [[ -n $output_dir ]]; then
  invalid_status_destination="$output_dir/evidence-invalid.status"
else
  invalid_status_destination="${metadata}.evidence-invalid.status"
fi
case $artifact_destination in "$river_root"/*) die "artifact must be outside the source workspace" ;; esac
case $metadata in "$river_root"/*) die "metadata must be outside the source workspace" ;; esac
case $terminal_receipt_destination in "$river_root"/*) die "terminal receipt must be outside the source workspace" ;; esac
[[ ! -e $artifact_destination ]] || die "refusing to overwrite acceptance artifact: $artifact_destination"
[[ ! -e $metadata ]] || die "refusing to overwrite tool metadata: $metadata"
[[ ! -e $terminal_receipt_destination ]] ||
  die "refusing to overwrite terminal receipt: $terminal_receipt_destination"
[[ ! -e $invalid_status_destination ]] ||
  die "refusing to overwrite evidence status: $invalid_status_destination"

stdout_log="$temp_dir/tpcc.stdout.log"
stderr_log="$temp_dir/tpcc.stderr.log"
combined_log="$temp_dir/tpcc-output.log"
server_log="$temp_dir/server.log"
server_metrics="$temp_dir/server-metrics.log"
metrics_start="$temp_dir/performance-capture-start"
metrics_started="$temp_dir/performance-capture-started"
metrics_stop="$temp_dir/performance-capture-stop"
metrics_stopped="$temp_dir/performance-capture-stopped"
server_ready="$temp_dir/server.ready"
server_stop="$temp_dir/server.stop"
runtime_descriptor="$river_root/river-bench/build/tps-runtime-classpath.properties"
source_manifest_start="$temp_dir/source-manifest.start.tsv"
source_manifest_check="$temp_dir/source-manifest.check.tsv"
git_status_start="$temp_dir/git-status.start.txt"
git_status_check="$temp_dir/git-status.check.txt"
git_commit_start="$temp_dir/git-commit.txt"
host_evidence_dir="$temp_dir/host-exclusion"
provisional_daemons="$host_evidence_dir/host-provisional-daemons.tsv"
provenance_checkpoints="$host_evidence_dir/provenance-checkpoints.tsv"
mkdir -p "$host_evidence_dir"
: >"$provenance_checkpoints"

persist_file() {
  local source=$1
  local destination=$2
  [[ -f $source ]] || {
    echo "warning: required retained evidence is missing: $source" >&2
    publication_valid=false
    persistence_valid=false
    return 1
  }
  [[ ! -e $destination ]] || {
    echo "warning: refusing to overwrite retained evidence $destination" >&2
    publication_valid=false
    persistence_valid=false
    return 1
  }
  provenance_publish_file "$source" "$destination" 2>/dev/null
  local status=$?
  if ((status != 0)); then
    publication_valid=false
    persistence_valid=false
    echo "warning: unable to preserve $source at $destination" >&2
  fi
  return "$status"
}

persist_if_present() {
  local source=$1
  local destination=$2
  [[ ! -e $source ]] || persist_file "$source" "$destination"
}

redacted_command_line() {
  local result= argument
  local arguments=( "$0" "${original_arguments[@]}" )
  for argument in "${arguments[@]}"; do
    case ${argument,,} in
      *password*|*secret*|*token*|*credential*|--client-java-option=*|--server-java-option=*)
        argument="${argument%%=*}=<redacted>"
        ;;
    esac
    result+=$(printf '%q ' "$argument")
  done
  printf '%s\n' "$result"
}

persist_checkpoint_files() {
  local destination=$1
  local source_file checkpoint_list="$temp_dir/checkpoint-files.list"
  [[ ! -e $destination ]] || {
    echo "warning: refusing to overwrite checkpoint evidence $destination" >&2
    publication_valid=false
    persistence_valid=false
    return 1
  }
  mkdir "$destination" || { publication_valid=false; persistence_valid=false; return 1; }
  if ! find "$temp_dir" -maxdepth 1 -type f \
      \( -name 'source-manifest.*.tsv' -o -name 'git-status.*.txt' \) -print | LC_ALL=C sort \
      >"$checkpoint_list"; then
    publication_valid=false
    persistence_valid=false
    return 1
  fi
  while IFS= read -r source_file; do
    persist_file "$source_file" "$destination/$(basename -- "$source_file")" || return 1
  done <"$checkpoint_list"
  rm -f -- "$checkpoint_list" || {
    publication_valid=false; persistence_valid=false; return 1;
  }
}

verify_provenance_checkpoint() {
  local stage=$1
  local source_check="$temp_dir/source-manifest.$stage.tsv"
  local status_check="$temp_dir/git-status.$stage.txt"
  local checkpoint_valid=true source_hash=unavailable status_hash=unavailable
  if ! provenance_write_source_manifest "$river_root" "$source_check" ||
      ! provenance_write_git_status "$river_root" "$status_check"; then
    source_stable=false
    checkpoint_valid=false
  else
    source_hash=$(hash_file "$source_check")
    status_hash=$(hash_file "$status_check")
  fi
  if [[ $checkpoint_valid == true ]] && {
      ! cmp -s "$source_manifest_start" "$source_check" ||
      ! cmp -s "$git_status_start" "$status_check"
    }; then
    source_stable=false
    checkpoint_valid=false
  fi
  if ! printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$stage" "$(date +%s)" \
      "$source_hash" "$status_hash" unavailable unavailable \
      >>"$provenance_checkpoints"; then
    publication_valid=false
    persistence_valid=false
    checkpoint_valid=false
  fi
  [[ $checkpoint_valid == true ]] || publication_valid=false
  [[ $checkpoint_valid == true ]]
}

write_metadata() {
  local destination=$metadata
  local parent=$(dirname -- "$destination")
  mkdir -p "$parent" 2>/dev/null || {
    publication_valid=false
    persistence_valid=false
    echo "warning: cannot create metadata parent $parent" >&2
    return 1
  }
  local staged="$temp_dir/run-metadata.staged.properties"
  local git_commit=unavailable git_dirty=unknown
  local final_status_file="$temp_dir/git-status.metadata.txt"
  local final_source_file="$temp_dir/source-manifest.metadata.tsv"
  local workspace_stable
  local java_version=unavailable run_id=unavailable database_digest=unavailable
  [[ -f $git_commit_start ]] && git_commit=$(tr -d '\r\n' <"$git_commit_start")
  [[ -s $final_status_file ]] && git_dirty=dirty || git_dirty=clean
  workspace_finish_sha256=$(hash_file "$final_source_file")
  workspace_stable=$source_stable
  java_version=$("$java_bin" -version 2>&1 | head -1 || true)
  local artifact_evidence=$artifact
  [[ $artifact_published == true ]] && artifact_evidence=$artifact_destination
  if [[ -f $artifact_evidence ]]; then
    run_id=$(property run.id "$artifact_evidence"); database_digest=$(property database.digest.sha256 "$artifact_evidence")
    [[ -n $run_id ]] || run_id=unavailable; [[ -n $database_digest ]] || database_digest=unavailable
  fi
  local command_line
  command_line=$(redacted_command_line)
  {
    printf 'tool.schema=river-tps-tool-v2\n'
    printf 'run.result=provisional\n'
    printf 'run.phase=terminal_pending\n'
    printf 'run.status=TERMINAL_RECEIPT_REQUIRED\n'
    printf 'run.exit_status=1\n'
    printf 'run.provisional_result=%s\n' "$run_result"
    printf 'run.provisional_phase=%s\n' "$run_phase"
    printf 'run.provisional_status=%s\n' "$run_status"
    printf 'run.provisional_exit_status=%s\n' "$run_exit_status"
    printf 'run.sample_id=%s\n' "$sample_id"
    printf 'run.started_epoch=%s\n' "$started_epoch"
    printf 'run.finished_epoch=%s\n' "$(date +%s)"
    printf 'run.command_line=%s\n' "$command_line"
    printf 'run.command_sha256=%s\n' "$(hash_text "$command_line")"
    printf 'git.commit_sha=%s\n' "$git_commit"
    printf 'git.dirty_state=%s\n' "$git_dirty"
    printf 'git.status_sha256=%s\n' "$(hash_file "$final_status_file")"
    printf 'git.workspace_start_sha256=%s\n' "$workspace_start_sha256"
    printf 'git.workspace_finish_sha256=%s\n' "$workspace_finish_sha256"
    printf 'git.workspace_stable_during_run=%s\n' "$workspace_stable"
    printf 'environment.java_launcher=%s\n' "$java_bin"
    printf 'environment.java_launcher_path=%s\n' "$java_launcher_path"
    printf 'environment.java_launcher_sha256=%s\n' "$java_launcher_sha256"
    printf 'environment.java_home=%s\n' "${java_runtime_home:-unavailable}"
    printf 'environment.java_version=%s\n' "$java_version"
    printf 'environment.os=%s\n' "$(uname -srm 2>/dev/null || printf unavailable)"
    printf 'environment.host=%s\n' "$(hostname 2>/dev/null || printf unavailable)"
    printf 'environment.java_tool_options=%s\n' "$([[ -n ${JAVA_TOOL_OPTIONS:-} ]] && printf redacted || printf unset)"
    printf 'configuration.backend=%s\n' "$backend"
    printf 'configuration.profile=%s\n' "$profile"
    printf 'configuration.mix=%s\n' "$mix"
    printf 'configuration.isolation=%s\n' "$isolation"
    printf 'configuration.scheduling=%s\n' "$scheduling"
    printf 'configuration.evidence=%s\n' "$evidence"
    printf 'configuration.fresh_load=%s\n' "$fresh_load"
    printf 'configuration.port=%s\n' "$port"
    printf 'configuration.warehouses=%s\n' "$warehouses"
    printf 'configuration.terminals=%s\n' "$terminals"
    printf 'configuration.batch_rows=%s\n' "$batch_rows"
    printf 'configuration.maximum_attempts=%s\n' "$maximum_attempts"
    printf 'configuration.warmup_seconds=%s\n' "$warmup_seconds"
    printf 'configuration.measured_seconds=%s\n' "$measured_seconds"
    printf 'configuration.runner_timeout_seconds=%s\n' "$runner_timeout_seconds"
    printf 'configuration.server_start_timeout_seconds=%s\n' "$server_start_timeout_seconds"
    printf 'configuration.server_stop_timeout_seconds=%s\n' "$server_stop_timeout_seconds"
    printf 'configuration.resource_maximum_bytes=%s\n' "$resource_maximum_bytes"
    printf 'configuration.resource_delivery_bytes=%s\n' "$resource_delivery_bytes"
    printf 'configuration.resource_lock_provider_bytes=%s\n' "$resource_lock_provider_bytes"
    printf 'configuration.resource_version_workspace_bytes=%s\n' "$resource_version_workspace_bytes"
    printf 'configuration.resource_page_cache_bytes=%s\n' "$resource_page_cache_bytes"
    printf 'configuration.resource_staging_frame_bytes=%s\n' "$resource_staging_frame_bytes"
    printf 'configuration.resource_staged_page_capacity=%s\n' "$resource_staged_page_capacity"
    printf 'configuration.deadlock_diagnostics_bytes=%s\n' "$deadlock_diagnostics_bytes"
    printf 'configuration.deadlock_diagnostics_epochs=%s\n' "$deadlock_diagnostics_epochs"
    printf 'configuration.deadlock_diagnostics_signatures_per_epoch=%s\n' "$deadlock_diagnostics_signatures_per_epoch"
    printf 'configuration.deadlock_diagnostics_events_per_epoch=%s\n' "$deadlock_diagnostics_events_per_epoch"
    printf 'configuration.deadlock_diagnostics_exemplars_per_signature=%s\n' "$deadlock_diagnostics_exemplars_per_signature"
    printf 'configuration.deadlock_diagnostics_maximum_cycle_edges=%s\n' "$deadlock_diagnostics_maximum_cycle_edges"
    printf 'configuration.seed=%s\n' "${seed:-java_default}"
    printf 'configuration.retry_base_micros=%s\n' "${retry_base_micros:-java_default}"
    printf 'configuration.retry_maximum_millis=%s\n' "${retry_maximum_millis:-java_default}"
    printf 'configuration.client_jfr=%s\n' "${client_jfr:-disabled}"
    printf 'configuration.server_jfr=%s\n' "${server_jfr:-disabled}"
    printf 'configuration.client_java_option_count=%s\n' "${#client_java_options[@]}"
    printf 'configuration.server_java_option_count=%s\n' "${#server_java_options[@]}"
    printf 'configuration.fingerprint=%s\n' "$(hash_text "$backend|$profile|$mix|$isolation|$scheduling|$evidence|$fresh_load|$warehouses|$terminals|$batch_rows|$maximum_attempts|$warmup_seconds|$measured_seconds|${seed:-java_default}|${retry_base_micros:-java_default}|${retry_maximum_millis:-java_default}|$resource_maximum_bytes|$resource_delivery_bytes|$resource_lock_provider_bytes|$resource_version_workspace_bytes|$resource_page_cache_bytes|$resource_staging_frame_bytes|$resource_staged_page_capacity|$deadlock_diagnostics_bytes|$deadlock_diagnostics_epochs|$deadlock_diagnostics_signatures_per_epoch|$deadlock_diagnostics_events_per_epoch|$deadlock_diagnostics_exemplars_per_signature|$deadlock_diagnostics_maximum_cycle_edges")"
    printf 'artifact.path=%s\n' "$artifact_destination"
    printf 'artifact.published=%s\n' "$artifact_published"
    printf 'artifact.run_id=%s\n' "$run_id"
    printf 'artifact.database_digest_sha256=%s\n' "$database_digest"
    printf 'artifact.sha256=%s\n' "$(hash_file "$artifact_evidence")"
    printf 'output.stdout_sha256=%s\n' "$(hash_file "$stdout_log")"
    printf 'output.stderr_sha256=%s\n' "$(hash_file "$stderr_log")"
    printf 'output.combined_sha256=%s\n' "$(hash_file "$combined_log")"
    printf 'output.server_log_sha256=%s\n' "$(hash_file "$server_log")"
    printf 'output.server_metrics_sha256=%s\n' "$(hash_file "$server_metrics")"
    printf 'provenance.source_manifest_sha256=%s\n' "$workspace_start_sha256"
    printf 'provenance.source_stable=%s\n' "$source_stable"
    printf 'provenance.host_exclusion_valid=%s\n' "$host_exclusion_valid"
    printf 'provenance.host_observations_sha256=pending_terminal_receipt\n'
    printf 'provenance.host_processes_sha256=pending_terminal_receipt\n'
    printf 'provenance.host_classifications_sha256=pending_terminal_receipt\n'
    printf 'provenance.host_violations_sha256=pending_terminal_receipt\n'
    printf 'provenance.host_provisional_daemons_sha256=pending_terminal_receipt\n'
    printf 'provenance.host_evidence_bytes=pending_terminal_receipt\n'
    printf 'provenance.checkpoints_sha256=pending_terminal_receipt\n'
    printf 'provenance.publication_valid=%s\n' "$publication_valid"
    printf 'provenance.persistence_valid=%s\n' "$persistence_valid"
    printf 'evidence.run_id=%s\n' "$evidence_run_id"
    printf 'lease.owner_pid=%s\n' "${PROVENANCE_LEASE_OWNER_PID:-unavailable}"
    printf 'lease.owner_start=%s\n' "${PROVENANCE_LEASE_OWNER_START:-unavailable}"
    printf 'lease.owner_identity_sha256=%s\n' "${PROVENANCE_LEASE_OWNER_IDENTITY_SHA256:-unavailable}"
    printf 'terminal.required=true\n'
    printf 'terminal.path=%s\n' "$terminal_receipt_destination"
    printf 'terminal.commitment_sha256=%s\n' "${PROVENANCE_TERMINAL_COMMITMENT_SHA256:-unavailable}"
    printf 'tool.tps_test_sha256=%s\n' "$(hash_file "$script_dir/tps-test.sh")"
    printf 'tool.provenance_sha256=%s\n' "$(hash_file "$script_dir/tps-provenance.sh")"
  } >"$staged" 2>/dev/null || {
    publication_valid=false
    persistence_valid=false
    echo "warning: unable to write metadata: $destination" >&2
    return 1
  }
  if ! provenance_publish_file "$staged" "$destination" 2>/dev/null; then
    publication_valid=false
    persistence_valid=false
    echo "warning: refusing to overwrite tool metadata: $destination" >&2
    return 1
  fi
  rm -f -- "$staged"
}

stop_server() {
  [[ -n ${server_pid:-} ]] || return 0
  [[ -e $server_stop ]] || : >"$server_stop"
  local attempt=0
  while kill -0 "$server_pid" 2>/dev/null && ((attempt < server_stop_timeout_seconds * 10)); do
    sleep 0.1
    ((attempt += 1))
  done
  if kill -0 "$server_pid" 2>/dev/null; then kill "$server_pid" 2>/dev/null || true; fi
  wait "$server_pid" 2>/dev/null || true
  server_pid=
}

stop_runner() {
  [[ -n ${runner_pid:-} ]] || return 0
  if kill -0 "$runner_pid" 2>/dev/null; then
    kill "$runner_pid" 2>/dev/null || true
  fi
  wait "$runner_pid" 2>/dev/null || true
  runner_pid=
}

cleanup() {
  local requested_status=$?
  local metadata_hash=unavailable artifact_run_id=unavailable release_outcome=not_acquired
  local released_epoch=0 receipt_result=evidence_invalid receipt_status=NOT_STARTED
  local receipt_evidence_dir=$host_evidence_dir receipt_parent receipt_staged
  set +e
  stop_runner
  stop_server
  verify_provenance_checkpoint publication || true
  if [[ $source_stable != true || $publication_valid != true ]]; then
    if [[ $run_result != evidence_invalid ]]; then
      run_result=evidence_invalid
      run_phase=provenance
      run_status=PROVENANCE_CHANGED_OR_HOST_BUSY
      run_exit_status=1
    fi
  fi
  if [[ -f $artifact ]]; then
    if ! mkdir -p "$(dirname -- "$artifact_destination")"; then
      publication_valid=false
      persistence_valid=false
    elif persist_file "$artifact" "$artifact_destination"; then
      artifact_published=true
    fi
  fi
  if [[ -n ${output_dir:-} ]]; then
    persist_if_present "$stdout_log" "$output_dir/tpcc.stdout.log"
    persist_if_present "$stderr_log" "$output_dir/tpcc.stderr.log"
    persist_if_present "$combined_log" "$output_dir/tpcc-output.log"
    persist_if_present "$server_log" "$output_dir/server.log"
    persist_if_present "$server_metrics" "$output_dir/server-metrics.log"
    persist_if_present "$runtime_descriptor" "$output_dir/runtime-classpath.properties"
    persist_if_present "$source_manifest_start" "$output_dir/source-manifest.tsv"
    persist_if_present "$git_status_start" "$output_dir/git-status.txt"
  fi
  verify_provenance_checkpoint metadata || true
  if [[ $publication_valid != true ]]; then
    if [[ $run_result != evidence_invalid ]]; then
      run_result=evidence_invalid
      run_phase=provenance
      run_status=EVIDENCE_PUBLICATION_FAILED
      run_exit_status=1
    fi
  fi
  if ! write_metadata; then
    run_result=evidence_invalid
    run_phase=provenance
    run_status=EVIDENCE_PUBLICATION_FAILED
    run_exit_status=1
  fi
  [[ -f $metadata ]] && metadata_hash=$(hash_file "$metadata")

  verify_provenance_checkpoint terminal || true
  if [[ $source_stable != true || $publication_valid != true ]]; then
    if [[ $run_result != evidence_invalid ]]; then
      run_result=evidence_invalid
      run_phase=provenance
      run_status=PROVENANCE_CHANGED_OR_HOST_BUSY
      run_exit_status=1
    fi
  fi
  if [[ -n ${output_dir:-} ]]; then
    persist_if_present "$host_evidence_dir/host-observations.tsv" "$output_dir/host-observations.tsv"
    persist_if_present "$host_evidence_dir/host-processes.tsv" "$output_dir/host-processes.tsv"
    persist_if_present "$host_evidence_dir/host-classifications.tsv" "$output_dir/host-classifications.tsv"
    persist_if_present "$host_evidence_dir/host-violations.tsv" "$output_dir/host-violations.tsv"
    persist_if_present "$provisional_daemons" "$output_dir/host-provisional-daemons.tsv"
    persist_file "$provenance_checkpoints" "$output_dir/provenance-checkpoints.tsv"
    persist_checkpoint_files "$output_dir/checkpoints"
    receipt_evidence_dir=$output_dir
  fi
  if [[ $publication_valid != true ]]; then
    if [[ $run_result != evidence_invalid ]]; then
      run_result=evidence_invalid
      run_phase=provenance
      run_status=EVIDENCE_PUBLICATION_FAILED
      run_exit_status=1
    fi
    echo "evidence_status=evidence_invalid reason=publication_failed" >&2
  fi
  if [[ $run_result == evidence_invalid ]]; then
    {
      printf 'schema=river-tps-evidence-status-v1\n'
      printf 'result=evidence_invalid\n'
      printf 'status=%s\n' "$run_status"
    } >"$temp_dir/evidence-invalid.status" || publication_valid=false
    if [[ $invalid_status_destination != "$temp_dir/evidence-invalid.status" ]]; then
      persist_file "$temp_dir/evidence-invalid.status" "$invalid_status_destination" || true
    fi
  fi
  if [[ -n $output_dir && $persistence_valid == true ]]; then
    if rm -rf -- "$temp_dir"; then
      temp_cleanup_valid=true
    else
      temp_cleanup_valid=false
      run_result=evidence_invalid
      run_phase=provenance
      run_status=TEMPORARY_CLEANUP_FAILED
      run_exit_status=1
    fi
  fi
  release_outcome=released
  released_epoch=$(date +%s)
  [[ -f $artifact_destination ]] && artifact_run_id=$(property run.id "$artifact_destination")
  [[ -n $artifact_run_id ]] || artifact_run_id=unavailable
  receipt_status=$run_status
  if [[ $requested_status -eq 0 && $run_result == completed &&
      $source_stable == true &&
      $publication_valid == true && $persistence_valid == true &&
      $temp_cleanup_valid == true && $release_outcome == released ]]; then
    receipt_result=success
    receipt_status=OK
  fi
  if [[ $metadata_hash != unavailable && -d $receipt_evidence_dir ]]; then
    receipt_parent=$(dirname -- "$terminal_receipt_destination")
    mkdir -p "$receipt_parent" 2>/dev/null || terminal_publication_valid=false
    receipt_staged=$(mktemp "$receipt_parent/.river-tps-terminal.XXXXXX" 2>/dev/null)
    if [[ -n $receipt_staged ]] && provenance_write_terminal_receipt "$receipt_staged" \
        "$receipt_result" "$receipt_status" "$evidence_run_id" "$artifact_run_id" \
        "$metadata_hash" "${PROVENANCE_LEASE_OWNER_PID:-unavailable}" \
        "${PROVENANCE_LEASE_OWNER_START:-unavailable}" \
        "${PROVENANCE_LEASE_OWNER_IDENTITY_SHA256:-unavailable}" "$terminal_nonce" \
        "${PROVENANCE_TERMINAL_COMMITMENT_SHA256:-unavailable}" "$receipt_evidence_dir" \
        "$release_outcome" "$released_epoch" &&
        provenance_publish_file "$receipt_staged" "$terminal_receipt_destination"; then
      if provenance_validate_terminal_receipt "$metadata" "$artifact_destination" \
          "$terminal_receipt_destination" "$receipt_evidence_dir" "$receipt_result"; then
        terminal_publication_valid=true
      fi
    fi
    [[ -z $receipt_staged ]] || rm -f -- "$receipt_staged"
  fi
  if [[ $terminal_publication_valid != true ]]; then
    publication_valid=false
    receipt_result=evidence_invalid
    run_result=evidence_invalid
    run_phase=provenance
    run_status=TERMINAL_RECEIPT_PUBLICATION_FAILED
    run_exit_status=1
    echo "evidence_status=evidence_invalid reason=terminal_receipt_publication_failed" >&2
    if [[ ! -e $invalid_status_destination ]]; then
      receipt_staged=$(mktemp "$(dirname -- "$invalid_status_destination")/.river-tps-status.XXXXXX" 2>/dev/null)
      if [[ -n $receipt_staged ]]; then
        {
          printf 'schema=river-tps-evidence-status-v1\n'
          printf 'result=evidence_invalid\n'
          printf 'status=TERMINAL_RECEIPT_PUBLICATION_FAILED\n'
        } >"$receipt_staged" &&
          provenance_publish_file "$receipt_staged" "$invalid_status_destination" || true
        rm -f -- "$receipt_staged"
      fi
    fi
  fi
  if [[ -d $temp_dir ]]; then
    if [[ $keep_output == true ]]; then
      echo "temporary_run_dir=$temp_dir" >&2
    elif [[ -z $output_dir || ($persistence_valid == true &&
        $terminal_publication_valid == true) ]]; then
      rm -rf -- "$temp_dir" || temp_cleanup_valid=false
    else
      echo "temporary_run_dir=$temp_dir" >&2
    fi
  fi
  if [[ $receipt_result != success || $terminal_publication_valid != true ||
      $source_stable != true ||
      $publication_valid != true || $temp_cleanup_valid != true ]]; then
    trap - EXIT
    ((requested_status != 0)) && exit "$requested_status"
    exit 1
  fi
  return "$requested_status"
}
trap cleanup EXIT
trap 'run_result=interrupted; run_phase=interrupted; run_status=INTERRUPTED; run_exit_status=130; exit 130' INT
trap 'run_result=interrupted; run_phase=interrupted; run_status=INTERRUPTED; run_exit_status=143; exit 143' TERM

: >"$host_evidence_dir/host-observations.tsv"
: >"$host_evidence_dir/host-processes.tsv"
: >"$host_evidence_dir/host-classifications.tsv"
: >"$host_evidence_dir/host-violations.tsv"
: >"$provisional_daemons"
PROVENANCE_LEASE_OWNER_PID=$$
PROVENANCE_LEASE_OWNER_START=$started_epoch
PROVENANCE_LEASE_OWNER_IDENTITY_SHA256=$(provenance_owner_identity_hash \
  "$evidence_run_id" "$PROVENANCE_LEASE_OWNER_PID" "$PROVENANCE_LEASE_OWNER_START")
PROVENANCE_TERMINAL_COMMITMENT_SHA256=$(provenance_terminal_commitment_hash \
  "$evidence_run_id" "$PROVENANCE_LEASE_OWNER_IDENTITY_SHA256" "$terminal_nonce")
provenance_write_source_manifest "$river_root" "$source_manifest_start" ||
  die "unable to capture source manifest"
provenance_write_git_status "$river_root" "$git_status_start" ||
  die "unable to capture Git status"
git -C "$river_root" rev-parse HEAD >"$git_commit_start" ||
  die "unable to capture Git commit"
workspace_start_sha256=$(hash_file "$source_manifest_start")

[[ -f $runtime_descriptor ]] || die "runtime classpath is missing; run ./make.sh first"
[[ $(property schema "$runtime_descriptor") == river-tps-runtime-v1 ]] ||
  die "runtime classpath descriptor has an unsupported schema"
classpath=$(provenance_classpath_value "$runtime_descriptor") ||
  die "runtime classpath descriptor has no entries"
verify_provenance_checkpoint startup || die "source changed before the workload"

((terminals <= 2147483643)) || die "terminals leave no addressable server control slots"
server_connections=$((terminals + 4))
server_args=( "--directory=$temp_dir/database" "--port=$port"
  "--maximum-connections=$server_connections" "--ready-file=$server_ready"
  "--resource-maximum-bytes=$resource_maximum_bytes"
  "--resource-delivery-bytes=$resource_delivery_bytes"
  "--resource-lock-provider-bytes=$resource_lock_provider_bytes"
  "--resource-version-workspace-bytes=$resource_version_workspace_bytes"
  "--resource-page-cache-bytes=$resource_page_cache_bytes"
  "--resource-staging-frame-bytes=$resource_staging_frame_bytes"
  "--resource-staged-page-capacity=$resource_staged_page_capacity"
  "--stop-file=$server_stop" "--metrics-file=$server_metrics"
  "--metrics-start-file=$metrics_start" "--metrics-started-file=$metrics_started"
  "--metrics-stop-file=$metrics_stop" "--metrics-stopped-file=$metrics_stopped"
  "--deadlock-diagnostics-bytes=$deadlock_diagnostics_bytes"
  "--deadlock-diagnostics-epochs=$deadlock_diagnostics_epochs"
  "--deadlock-diagnostics-signatures-per-epoch=$deadlock_diagnostics_signatures_per_epoch"
  "--deadlock-diagnostics-events-per-epoch=$deadlock_diagnostics_events_per_epoch"
  "--deadlock-diagnostics-exemplars-per-signature=$deadlock_diagnostics_exemplars_per_signature"
  "--deadlock-diagnostics-maximum-cycle-edges=$deadlock_diagnostics_maximum_cycle_edges" )
if [[ -n $server_jfr ]]; then
  server_jfr=$(absolute_path "$server_jfr")
  [[ ! -e $server_jfr ]] || die "refusing to overwrite server JFR: $server_jfr"
  mkdir -p "$(dirname -- "$server_jfr")"
  server_args+=( "--jfr=$server_jfr" )
fi
if [[ -n $client_jfr ]]; then
  client_jfr=$(absolute_path "$client_jfr")
  [[ ! -e $client_jfr ]] || die "refusing to overwrite client JFR: $client_jfr"
  mkdir -p "$(dirname -- "$client_jfr")"
fi

echo "managed_server=starting port=$port"
"$java_bin" "${server_java_options[@]}" -cp "$classpath" io.riverdb.bench.tpcc.TpccServerMain \
  "${server_args[@]}" >"$server_log" 2>&1 &
server_pid=$!
server_ready_status=false
for ((attempt = 0; attempt < server_start_timeout_seconds * 10; attempt++)); do
  if [[ -s $server_ready ]]; then server_ready_status=true; break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  sleep 0.1
done
if [[ $server_ready_status != true ]]; then
  run_result=startup_failed; run_phase=startup; run_status=SERVER_NOT_READY; run_exit_status=1
  echo "=== TPS result ==="
  echo "result=$run_result"; echo "phase=$run_phase"; echo "status=$run_status"; echo "tps=unavailable"
  echo "=== managed server log ===" >&2
  [[ -f $server_log ]] && sed -n '1,240p' "$server_log" >&2 || true
  exit 1
fi
managed_port=$(tr -d '\r\n' <"$server_ready")
require_uint managed_port "$managed_port"
((managed_port > 0 && managed_port <= 65535)) || die "managed server returned invalid port: $managed_port"
url="jdbc:river://localhost:$managed_port"
echo "managed_server=started port=$managed_port"
verify_provenance_checkpoint server || {
  run_result=evidence_invalid; run_phase=server
  run_status=PROVENANCE_CHANGED_OR_HOST_BUSY; run_exit_status=1
  exit 1
}
echo "managed_server_resources=explicit maximum_bytes=$resource_maximum_bytes delivery_bytes=$resource_delivery_bytes lock_provider_bytes=$resource_lock_provider_bytes version_workspace_bytes=$resource_version_workspace_bytes page_cache_bytes=$resource_page_cache_bytes staging_frame_bytes=$resource_staging_frame_bytes staged_page_capacity=$resource_staged_page_capacity"
[[ -n $server_jfr ]] && echo "managed_server_jfr=$server_jfr"
if [[ $deadlock_diagnostics_bytes =~ ^0+$ ]]; then
  echo "managed_server_deadlock_diagnostics=disabled budget_bytes=0"
else
  echo "managed_server_deadlock_diagnostics=enabled budget_bytes=$deadlock_diagnostics_bytes epochs=$deadlock_diagnostics_epochs signatures_per_epoch=$deadlock_diagnostics_signatures_per_epoch events_per_epoch=$deadlock_diagnostics_events_per_epoch exemplars_per_signature=$deadlock_diagnostics_exemplars_per_signature maximum_cycle_edges=$deadlock_diagnostics_maximum_cycle_edges"
fi

runner_args=( "--url=$url" "--fresh-load=$fresh_load" "--warmup-seconds=$warmup_seconds"
  "--measured-seconds=$measured_seconds" "--scheduling=$scheduling" "--mix=$mix"
  "--isolation=$isolation" "--warehouses=$warehouses"
  "--terminals=$terminals" "--batch-rows=$batch_rows" "--maximum-attempts=$maximum_attempts"
  "--artifact=$artifact" "--evidence=$evidence"
  "--metrics-start-file=$metrics_start" "--metrics-started-file=$metrics_started"
  "--metrics-stop-file=$metrics_stop" "--metrics-stopped-file=$metrics_stopped" )
[[ $profile == tiny ]] && runner_args+=( "--tiny" )
[[ -n $seed ]] && runner_args+=( "--seed=$seed" )
[[ -n $retry_base_micros ]] && runner_args+=( "--retry-base-micros=$retry_base_micros" )
[[ -n $retry_maximum_millis ]] && runner_args+=( "--retry-maximum-millis=$retry_maximum_millis" )
[[ -n $client_jfr ]] && runner_args+=( "--jfr=$client_jfr" )

echo "Running $measured_seconds seconds of River TPS testing against $url"
echo "profile=$profile mix=$mix warmup_seconds=$warmup_seconds measured_seconds=$measured_seconds scheduling=$scheduling evidence=$evidence"

verify_provenance_checkpoint client_start || {
  run_result=evidence_invalid; run_phase=client
  run_status=PROVENANCE_CHANGED_OR_HOST_BUSY; run_exit_status=1
  exit 1
}

"$java_bin" "${client_java_options[@]}" -cp "$classpath" io.riverdb.bench.tpcc.TpccAcceptanceMain \
  "${runner_args[@]}" >"$stdout_log" 2>"$stderr_log" &
runner_pid=$!
runner_started=$SECONDS
while kill -0 "$runner_pid" 2>/dev/null; do
  if ((SECONDS - runner_started >= runner_timeout_seconds)); then
    runner_timed_out=true; kill "$runner_pid" 2>/dev/null || true; break
  fi
  sleep 0.1
done
set +e; wait "$runner_pid"; runner_status=$?; set -e
runner_pid=
  verify_provenance_checkpoint client_finish || {
  source_stable=${source_stable:-false}
}
{ cat "$stdout_log"; if [[ -s $stderr_log ]]; then echo "=== runner stderr ==="; cat "$stderr_log"; fi; } >"$combined_log"
echo "=== TPS runner output ==="; cat "$stdout_log"
if [[ -s $stderr_log ]]; then echo "=== runner stderr ===" >&2; cat "$stderr_log" >&2; fi

stop_server
diagnostic_status=SERVER_METRICS_MISSING
performance_capture_status=SERVER_METRICS_MISSING
server_measured_deadlocks=0
server_capture_deadlocks=-1
client_deadlock_outcomes=0
server_active_transactions=-1
server_active_locks=-1
server_waiting_locks=-1
if [[ -f $server_metrics ]]; then
  echo; echo "=== managed server metrics (Java-emitted) ==="; sed -n '1,240p' "$server_metrics"
  diagnostic_enabled=$(awk -F= '/^server_deadlock_diagnostics_enabled=/{print $2}' "$server_metrics")
  diagnostic_budget=$(awk -F= '/^server_deadlock_diagnostics_budget_bytes=/{print $2}' "$server_metrics")
  diagnostic_valid=$(awk -F= '/^server_deadlock_diagnostics_valid=/{print $2}' "$server_metrics")
  diagnostic_engine_status=$(awk -F= '/^server_deadlock_diagnostics_status=/{print $2}' "$server_metrics")
  performance_capture_enabled=$(awk -F= '/^server_performance_capture_enabled=/{print $2}' "$server_metrics")
  performance_capture_engine_status=$(awk -F= '/^server_performance_capture_status=/{print $2}' "$server_metrics")
  performance_capture_valid=$(awk -F= '/^server_performance_capture_valid=/{print $2}' "$server_metrics")
  server_active_transactions=$(awk -F= '/^server_active_transactions_at_capture=/{print $2}' "$server_metrics")
  server_active_locks=$(awk -F= '/^server_active_locks_at_capture=/{print $2}' "$server_metrics")
  server_waiting_locks=$(awk -F= '/^server_waiting_locks_at_capture=/{print $2}' "$server_metrics")
  server_capture_deadlocks=$(awk -F= '/^server_capture_lock_waits_deadlocked=/{print $2}' "$server_metrics")
  server_measured_deadlocks=$(awk '
    /^deadlock_event / {
      epoch=""; outcome=""; cleanup=""
      for (i=1; i<=NF; i++) {
        split($i, f, "=")
        if (f[1]=="epoch") epoch=f[2]
        if (f[1]=="outcome") outcome=f[2]
        if (f[1]=="cleanup_valid") cleanup=f[2]
      }
      if (epoch==2 && outcome=="DEADLOCK" && cleanup=="true") count++
      else if (epoch==2) invalid++
    }
    END { if (invalid) print -invalid; else print count+0 }
  ' "$server_metrics")
  client_deadlock_outcomes=$(awk '
    /^retry_correlation / {
      status=""
      for (i=1; i<=NF; i++) {
        split($i, f, "=")
        if (f[1]=="status") status=f[2]
      }
      if (status=="DEADLOCK") count++
    }
    END { print count+0 }
  ' "$combined_log")
  if [[ ! $server_active_transactions =~ ^[0-9]+$
      || ! $server_active_locks =~ ^[0-9]+$
      || ! $server_waiting_locks =~ ^[0-9]+$
      || ! $server_capture_deadlocks =~ ^[0-9]+$ ]]; then
    diagnostic_status=INVALID_TERMINAL_CLEANUP_METRICS
  elif ((server_active_transactions != 0
      || server_active_locks != 0
      || server_waiting_locks != 0)); then
    diagnostic_status=INCOMPLETE_TERMINAL_CLEANUP
  elif [[ $diagnostic_engine_status != OK ]]; then
    diagnostic_status=INVALID_SERVER_DIAGNOSTICS
  elif ((server_capture_deadlocks != client_deadlock_outcomes)); then
    diagnostic_status=DEADLOCK_RECONCILIATION_MISMATCH
  elif [[ $deadlock_diagnostics_bytes =~ ^0+$ ]]; then
    if [[ $diagnostic_enabled != false || $diagnostic_budget != 0 ]]; then
      diagnostic_status=DIAGNOSTIC_CONFIGURATION_MISMATCH
    else
      diagnostic_status=OK
    fi
  elif [[ $diagnostic_enabled != true || $diagnostic_valid != true
      || $diagnostic_budget != "$deadlock_diagnostics_bytes" ]]; then
    diagnostic_status=INVALID_SERVER_DIAGNOSTICS
  elif ((server_measured_deadlocks < 0)); then
    diagnostic_status=INVALID_MEASURED_DEADLOCK_EVENT
  elif ((server_measured_deadlocks != server_capture_deadlocks)); then
    diagnostic_status=DEADLOCK_RECONCILIATION_MISMATCH
  else
    diagnostic_status=OK
  fi
  if [[ $performance_capture_enabled != true ]]; then
    performance_capture_status=PERFORMANCE_CAPTURE_DISABLED
  elif [[ $performance_capture_engine_status != OK ]]; then
    performance_capture_status=$performance_capture_engine_status
  elif [[ $performance_capture_valid != true ]]; then
    performance_capture_status=INVALID_PERFORMANCE_CAPTURE
  else
    performance_capture_status=OK
  fi
  echo "deadlock_reconciliation=$diagnostic_status server_capture_deadlocks=$server_capture_deadlocks server_epoch_2_events=$server_measured_deadlocks client_deadlock_outcomes=$client_deadlock_outcomes"
  echo "performance_capture=$performance_capture_status"
  echo "terminal_cleanup active_transactions=$server_active_transactions active_locks=$server_active_locks waiting_locks=$server_waiting_locks"
else echo "server_metrics=unavailable"; fi

if [[ -n $client_jfr && -f $client_jfr ]]; then
  echo; echo "=== client JFR summary ==="
  "$script_dir/jfr-flamegraph.sh" --jfr="$client_jfr" --top=25 || echo "warning: unable to render client JFR summary" >&2
fi
if [[ -n $server_jfr && -f $server_jfr ]]; then
  echo; echo "=== managed-server JFR summary ==="
  "$script_dir/jfr-flamegraph.sh" --jfr="$server_jfr" --top=25 || echo "warning: unable to render managed-server JFR summary" >&2
fi

summary=$(awk -v seconds="$measured_seconds" '
  /^whole_transaction_retries=/ { split($0, f, "="); retries=f[2]+0 }
  /^transaction_attempts=/ { split($0, f, "="); attempts=f[2]+0 }
  /^completed_transactions=/ { split($0, f, "="); completed=f[2]+0 }
  /^in_flight_at_cutoff=/ { split($0, f, "="); in_flight=f[2]+0 }
  /^transaction=/ {
    for (i=1; i<=NF; i++) { split($i, f, "=")
      if (f[1]=="committed") commits+=f[2]+0
      if (f[1]=="retry_exhausted" || f[1]=="failed") errors+=f[2]+0
    }
  }
  END { printf "%d %d %d %d %d %d %.3f\n", retries,errors,commits,completed,attempts,in_flight,commits/seconds }
' "$combined_log")
read -r retries errors commits completed attempts in_flight tps <<<"$summary"

phase_state=$(awk '
  /^phase_start=/ { split($0, f, "="); active=f[2] }
  /^phase_complete=/ { split($0, f, "="); if (active==f[2]) active="" }
  END { print (active == "" ? "none" : active) }
' "$combined_log")
if [[ $runner_timed_out == true ]]; then
  run_result="${phase_state}_failed"; [[ $phase_state == none ]] && run_result=startup_failed
  run_phase=${phase_state/none/startup}; run_status=TIMEOUT; run_exit_status=124
elif ((runner_status != 0)); then
  run_phase=${phase_state/none/startup}; run_result="${run_phase}_failed"
  run_status=$(sed -nE 's/.*: (RESOURCE_EXHAUSTED|IO_FAILURE|DEADLOCK|LOCK_TIMEOUT|TIMEOUT|CANCELLED|INVALID_ARGUMENT|NOT_OWNER|[A-Z][A-Z0-9_]{2,})$/\1/p' "$combined_log" | tail -1)
  [[ -n $run_status ]] || run_status=EXCEPTION; run_exit_status=$runner_status
elif [[ $diagnostic_status != OK ]]; then
  run_phase=diagnostics; run_result=diagnostics_failed
  run_status=$diagnostic_status; run_exit_status=1
elif [[ $performance_capture_status != OK ]]; then
  run_phase=diagnostics; run_result=diagnostics_failed
  run_status=$performance_capture_status; run_exit_status=1
elif [[ $phase_state != none && $phase_state != checkpoint ]]; then
  run_phase=$phase_state; run_result="${phase_state}_failed"; run_status=INCOMPLETE_PHASE; run_exit_status=1
elif ((commits <= 0)); then
  run_phase=measured; run_result=measured_failed; run_status=NO_COMMITTED_TRANSACTIONS; run_exit_status=1
elif ((errors > 0)); then
  run_phase=measured; run_result=measured_failed; run_status=TRANSACTION_ERRORS; run_exit_status=1
else
  run_phase=checkpoint; run_result=completed; run_status=OK; run_exit_status=0
fi

if ! verify_provenance_checkpoint result; then
  run_result=evidence_invalid
  run_phase=provenance
  run_status=PROVENANCE_CHANGED_OR_HOST_BUSY
  run_exit_status=1
fi

echo; echo "=== TPS result ==="
echo "result=$run_result"; echo "phase=$run_phase"; echo "status=$run_status"
echo "duration_seconds=$measured_seconds"; echo "retries=$retries"; echo "errors=$errors"
echo "commits=$commits"; echo "completed=$completed"; echo "attempts=$attempts"
echo "in_flight_at_cutoff=$in_flight"
echo "deadlock_reconciliation=$diagnostic_status"
echo "performance_capture=$performance_capture_status"
if [[ $run_result == completed || $commits -gt 0 ]]; then echo "tps=$tps"; else echo "tps=unavailable"; fi
exit "$run_exit_status"
