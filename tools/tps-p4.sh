#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

usage() {
  cat <<'EOF'
Usage:
  tools/tps-p4.sh --run --output-dir=PATH [options]
  tools/tps-p4.sh --calculate=PATH

Run or validate the partial River P4 point calculation. A run executes exactly
ten independent standard-scale River samples using alpha3 workload settings.
This remains a non-normative local calculator; it is not an Alpha3 promotion
claim or a complete capacity gate.

Run options:
  --output-dir=PATH             New empty evidence directory (required for run)
  --warmup-seconds=N             Default: 300
  --measured-seconds=N           Default: 1800
  --warehouses=N                 Default: 1
  --terminals=N                  Default: 10
  --batch-rows=N                 Default: 32
  --maximum-attempts=N           Default: 4
  --seed=N                       Default: 123456789
  --isolation=serializable|repeatable-read
                                 Common isolation contract (default: serializable)
  --runner-timeout-seconds=N     Default: warmup + measured + 300
  --server-start-timeout-seconds=N
                                 Default: 30
  --server-stop-timeout-seconds=N
                                 Default: 20
  --client-java-option=OPTION    Repeatable
  --server-java-option=OPTION    Repeatable
  --samples=10                   Any value other than 10 is rejected
  --calculate=PATH               Calculate from sample-01 through sample-10
  --run                          Run samples (default)
  -h, --help                     Show this help

The partial point requires exactly ten authoritative v2 terminal receipts,
>=100000 completed transactions in each sample, no failed or retry-exhausted
family outcome, identical persisted configuration/provenance, and a one-sided
95% lower confidence bound for committed TPS of at least 1000. The calculator
uses t(0.95,9)=1.8331129.
EOF
}

fail() {
  echo "p4_point=failed reason=$*" >&2
  exit 1
}

require_uint() {
  [[ $2 =~ ^[0-9]+$ ]] || fail "$1 is not a non-negative integer: $2"
}

require_positive() {
  require_uint "$1" "$2"
  (( $2 > 0 )) || fail "$1 must be greater than zero"
}

property() {
  local key=$1
  local file=$2
  provenance_property_once "$key" "$file" 2>/dev/null || true
}

hash_file() {
  [[ -f $1 ]] || { printf '%s\n' unavailable; return; }
  shasum -a 256 "$1" | awk '{print $1}'
}

absolute_path() {
  case $1 in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

tool_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tps_test="$tool_dir/tps-test.sh"
[[ -x $tps_test ]] || fail "tps-test.sh is not executable"
source "$tool_dir/tps-provenance.sh"

mode=run
calculate_dir=
output_dir=
sample_count=10
warmup_seconds=300
measured_seconds=1800
warehouses=1
terminals=10
batch_rows=32
maximum_attempts=4
seed=123456789
isolation=serializable
runner_timeout_seconds=
server_start_timeout_seconds=30
server_stop_timeout_seconds=20
client_java_options=()
server_java_options=()

while (($# > 0)); do
  case $1 in
    --calculate=*) mode=calculate; calculate_dir=${1#*=} ;;
    --run) mode=run ;;
    --output-dir=*) output_dir=${1#*=} ;;
    --samples=*) sample_count=${1#*=} ;;
    --warmup-seconds=*) warmup_seconds=${1#*=} ;;
    --measured-seconds=*) measured_seconds=${1#*=} ;;
    --warehouses=*) warehouses=${1#*=} ;;
    --terminals=*) terminals=${1#*=} ;;
    --batch-rows=*) batch_rows=${1#*=} ;;
    --maximum-attempts=*) maximum_attempts=${1#*=} ;;
    --seed=*) seed=${1#*=} ;;
    --isolation=*) isolation=${1#*=} ;;
    --runner-timeout-seconds=*) runner_timeout_seconds=${1#*=} ;;
    --server-start-timeout-seconds=*) server_start_timeout_seconds=${1#*=} ;;
    --server-stop-timeout-seconds=*) server_stop_timeout_seconds=${1#*=} ;;
    --client-java-option=*) client_java_options+=( "${1#*=}" ) ;;
    --server-java-option=*) server_java_options+=( "${1#*=}" ) ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; fail "unknown option: $1" ;;
  esac
  shift
done

require_uint samples "$sample_count"
((sample_count == 10)) || fail "the partial P4 point requires exactly 10 samples"
if [[ $mode == run ]]; then
  [[ -n $output_dir ]] || fail "--output-dir is required with --run"
  output_dir=$(absolute_path "$output_dir")
  [[ ! -e $output_dir || -d $output_dir ]] || fail "output-dir is not a directory: $output_dir"
  mkdir -p "$output_dir"
  [[ -z $(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
    fail "output-dir must be empty: $output_dir"
  require_positive warmup_seconds "$warmup_seconds"
  require_positive measured_seconds "$measured_seconds"
  require_positive warehouses "$warehouses"
  require_positive terminals "$terminals"
  require_positive batch_rows "$batch_rows"
  require_positive maximum_attempts "$maximum_attempts"
  require_uint seed "$seed"
  case $isolation in serializable|repeatable-read) ;; *) fail "P4 isolation must be serializable or repeatable-read" ;; esac
  if [[ -n $runner_timeout_seconds ]]; then require_positive runner_timeout_seconds "$runner_timeout_seconds";
  else runner_timeout_seconds=$((warmup_seconds + measured_seconds + 300)); fi
  require_positive server_start_timeout_seconds "$server_start_timeout_seconds"
  require_positive server_stop_timeout_seconds "$server_stop_timeout_seconds"
  for index in $(seq 1 10); do
    label=$(printf '%02d' "$index")
    sample_dir="$output_dir/sample-$label"
    echo "p4_sample=$label/10 starting"
    set +e
    "$tps_test" \
      --backend=river --profile=standard --mix=standard \
      --isolation="$isolation" \
      --scheduling=no-wait-stress --evidence=alpha3 --fresh-load=true \
      --warehouses="$warehouses" --terminals="$terminals" --batch-rows="$batch_rows" \
      --maximum-attempts="$maximum_attempts" --warmup-seconds="$warmup_seconds" \
      --measured-seconds="$measured_seconds" --runner-timeout-seconds="$runner_timeout_seconds" \
      --server-start-timeout-seconds="$server_start_timeout_seconds" \
      --server-stop-timeout-seconds="$server_stop_timeout_seconds" \
      --seed="$seed" --sample-id="p4-$label" --output-dir="$sample_dir" \
      "${client_java_options[@]/#/--client-java-option=}" \
      "${server_java_options[@]/#/--server-java-option=}"
    sample_status=$?
    set -e
    if ((sample_status != 0)); then
      echo "p4_sample=$label failed status=$sample_status" >&2
      exit "$sample_status"
    fi
    echo "p4_sample=$label/10 completed"
  done
  exec "$0" --calculate="$output_dir"
fi

[[ -n $calculate_dir ]] || fail "--calculate=PATH is required"
calculate_dir=$(absolute_path "$calculate_dir")
[[ -d $calculate_dir ]] || fail "calculation directory does not exist: $calculate_dir"
if [[ -n $output_dir ]]; then fail "--output-dir is valid only with --run"; fi

metadata_keys=(
  tool.schema run.result run.phase run.status run.exit_status
  run.provisional_result run.provisional_phase run.provisional_status
  run.provisional_exit_status run.sample_id evidence.run_id terminal.required terminal.path
  terminal.commitment_sha256 lease.owner_pid lease.owner_start lease.owner_identity_sha256
  git.commit_sha git.dirty_state git.status_sha256 environment.java_version
  environment.host configuration.fingerprint configuration.backend configuration.profile
  configuration.mix configuration.isolation configuration.scheduling configuration.evidence configuration.fresh_load
  configuration.warehouses configuration.terminals configuration.batch_rows
  configuration.maximum_attempts configuration.warmup_seconds configuration.measured_seconds
  configuration.runner_timeout_seconds configuration.server_start_timeout_seconds
  configuration.server_stop_timeout_seconds configuration.seed
  artifact.run_id artifact.database_digest_sha256 artifact.sha256
  output.stdout_sha256 output.stderr_sha256 output.combined_sha256
  output.server_log_sha256 output.server_metrics_sha256
)
common_metadata_keys=(
  tool.schema run.result run.phase run.status run.exit_status
  run.provisional_result run.provisional_phase run.provisional_status run.provisional_exit_status
  git.commit_sha git.dirty_state git.status_sha256 environment.java_version
  environment.host configuration.fingerprint configuration.backend configuration.profile
  configuration.mix configuration.scheduling configuration.evidence configuration.fresh_load
  configuration.isolation
  configuration.warehouses configuration.terminals configuration.batch_rows
  configuration.maximum_attempts configuration.warmup_seconds configuration.measured_seconds
  configuration.runner_timeout_seconds configuration.server_start_timeout_seconds
  configuration.server_stop_timeout_seconds configuration.seed
)
artifact_keys=(
  artifact.schema run.id database.digest.sha256 config.seed config.warehouses
  config.standard_scale config.standard_one_warehouse config.districts
  config.customers_per_district config.items config.orders_per_district
  config.terminals config.terminal_homes config.scheduling config.mix
  config.isolation_contract config.jdbc_isolation config.program_isolation config.evidence
  config.warmup_seconds config.measured_seconds config.batch_rows
  config.maximum_attempts config.retry_base_nanos config.retry_maximum_nanos
)
common_artifact_keys=(
  artifact.schema config.seed config.warehouses config.standard_scale
  config.standard_one_warehouse config.districts config.customers_per_district
  config.items config.orders_per_district config.terminals config.terminal_homes
  config.scheduling config.mix config.isolation_contract config.jdbc_isolation
  config.program_isolation config.evidence config.warmup_seconds config.measured_seconds
  config.batch_rows config.maximum_attempts config.retry_base_nanos
  config.retry_maximum_nanos
)
families=(new_order payment order_status delivery stock_level)
first_metadata=
first_artifact=
sample_tsv=$(mktemp "${TMPDIR:-/tmp}/river-tps-p4.XXXXXX")
cleanup_calculation() { rm -f -- "$sample_tsv"; }
trap cleanup_calculation EXIT
seen_run_ids=

require_value() {
  local key=$1
  local file=$2
  local value
  value=$(property "$key" "$file")
  [[ -n $value && $value != unavailable ]] || fail "$file missing usable $key"
  printf '%s\n' "$value"
}

for index in $(seq 1 10); do
  label=$(printf '%02d' "$index")
  sample_dir="$calculate_dir/sample-$label"
  artifact="$sample_dir/tpcc-acceptance.properties"
  metadata="$sample_dir/run-metadata.properties"
  terminal="$metadata.terminal-receipt"
  [[ -f $artifact ]] || fail "missing sample artifact: $artifact"
  [[ -f $metadata ]] || fail "missing sample metadata: $metadata"
  [[ -f $terminal ]] || fail "missing sample terminal receipt: $terminal"
  if [[ -z $first_metadata ]]; then first_metadata=$metadata; first_artifact=$artifact; fi

  for key in "${metadata_keys[@]}"; do require_value "$key" "$metadata" >/dev/null; done
  [[ $(property tool.schema "$metadata") == river-tps-tool-v2 ]] || fail "$metadata has wrong tool schema"
  [[ $(property run.result "$metadata") == provisional ]] || fail "$metadata is not provisional"
  [[ $(property run.status "$metadata") == TERMINAL_RECEIPT_REQUIRED ]] ||
    fail "$metadata does not require terminal validation"
  [[ $(property run.provisional_result "$metadata") == completed ]] ||
    fail "$metadata provisional run did not complete"
  [[ $(property run.provisional_phase "$metadata") == checkpoint ]] ||
    fail "$metadata provisional run did not reach checkpoint"
  [[ $(property run.provisional_status "$metadata") == OK ]] ||
    fail "$metadata provisional status is not OK"
  [[ $(property run.provisional_exit_status "$metadata") == 0 ]] ||
    fail "$metadata provisional exit status is not zero"
  [[ $(property run.sample_id "$metadata") == p4-$label ]] || fail "$metadata has wrong sample identity"
  run_id=$(property run.id "$artifact")
  [[ -n $run_id ]] || fail "$artifact has no run.id"
  [[ $seen_run_ids != *"|$run_id|"* ]] || fail "duplicate run.id: $run_id"
  seen_run_ids="$seen_run_ids|$run_id|"
  [[ $(property artifact.run_id "$metadata") == "$run_id" ]] || fail "$metadata does not bind artifact run.id"
  [[ $(property artifact.database_digest_sha256 "$metadata") == "$(property database.digest.sha256 "$artifact")" ]] ||
    fail "$metadata does not bind database identity"
  [[ $(property artifact.sha256 "$metadata") == "$(hash_file "$artifact")" ]] || fail "$metadata artifact hash mismatch"
  provenance_validate_terminal_receipt "$metadata" "$artifact" "$terminal" \
    "$sample_dir" success || fail "$terminal is not an authoritative success receipt"

  for key in "${artifact_keys[@]}"; do require_value "$key" "$artifact" >/dev/null; done
  [[ $(property artifact.schema "$artifact") == river-tpcc-acceptance-v2 ]] || fail "$artifact has wrong schema"
  [[ $(property config.standard_scale "$artifact") == true ]] || fail "$artifact is not standard scale"
  [[ $(property config.scheduling "$artifact") == NO_WAIT_STRESS ]] || fail "$artifact is not no-wait"
  [[ $(property config.evidence "$artifact") == ALPHA3 ]] || fail "$artifact is not alpha3 evidence"
  completed=$(property measurement.completed_transactions_at_cutoff "$artifact")
  [[ $completed =~ ^[0-9]+$ && $completed -ge 100000 ]] ||
    fail "$artifact completed transactions below 100000: $completed"
  committed=0
  failures=0
  for family in "${families[@]}"; do
    family_committed=$(property "measurement.$family.committed" "$artifact")
    family_failed=$(property "measurement.$family.failed" "$artifact")
    family_exhausted=$(property "measurement.$family.retry_exhausted" "$artifact")
    [[ $family_committed =~ ^[0-9]+$ ]] || fail "$artifact missing committed count for $family"
    [[ $family_failed =~ ^[0-9]+$ && $family_exhausted =~ ^[0-9]+$ ]] ||
      fail "$artifact missing failure counts for $family"
    committed=$((committed + family_committed))
    failures=$((failures + family_failed + family_exhausted))
  done
  ((failures == 0)) || fail "$artifact has failed/retry-exhausted transactions: $failures"
  seconds=$(property config.measured_seconds "$artifact")
  [[ $seconds =~ ^[0-9]+$ && $seconds -gt 0 ]] || fail "$artifact has invalid measured duration: $seconds"
  sample_tps=$(awk -v commits="$committed" -v seconds="$seconds" 'BEGIN { printf "%.9f", commits / seconds }')
  printf '%s %s %s %s\n' "$label" "$completed" "$committed" "$sample_tps" >>"$sample_tsv"
  echo "p4_sample=$label completed=$completed committed=$committed committed_tps=$sample_tps"
done

for key in "${common_metadata_keys[@]}"; do
  reference=$(property "$key" "$first_metadata")
  for index in $(seq 2 10); do
    metadata="$calculate_dir/sample-$(printf '%02d' "$index")/run-metadata.properties"
    [[ $(property "$key" "$metadata") == "$reference" ]] ||
      fail "metadata configuration/provenance mismatch for $key"
  done
done
for key in "${common_artifact_keys[@]}"; do
  reference=$(property "$key" "$first_artifact")
  for index in $(seq 2 10); do
    artifact="$calculate_dir/sample-$(printf '%02d' "$index")/tpcc-acceptance.properties"
    [[ $(property "$key" "$artifact") == "$reference" ]] ||
      fail "artifact configuration mismatch for $key"
  done
done

stats=$(awk '
  { n++; sum += $4; values[n] = $4 }
  END {
    if (n != 10) exit 2
    mean = sum / n
    ss = 0
    for (i = 1; i <= n; i++) ss += (values[i] - mean) * (values[i] - mean)
    sd = sqrt(ss / (n - 1))
    lower = mean - 1.8331129 * sd / sqrt(n)
    printf "%.9f %.9f %.9f\n", mean, sd, lower
  }
' "$sample_tsv") || fail "unable to calculate ten-sample confidence interval"
read -r mean standard_deviation lower_bound <<<"$stats"
echo "p4_samples_observed=10"
echo "p4_completed_minimum=100000"
echo "p4_mean_committed_tps=$mean"
echo "p4_sample_standard_deviation_tps=$standard_deviation"
echo "p4_95ci_lower_bound_committed_tps=$lower_bound"
if awk -v lower="$lower_bound" 'BEGIN { exit !(lower >= 1000.0) }'; then
  p4_result=partial_point_passed
  echo "p4_point=passed"
  exit_code=0
else
  p4_result=partial_point_failed
  echo "p4_point=failed reason=95ci_lower_bound_below_1000" >&2
  exit_code=1
fi
result_file="$calculate_dir/p4-result.properties"
[[ ! -e $result_file ]] || fail "refusing to overwrite $result_file"
staged="$result_file.staged.$$"
{
  printf 'tool.schema=river-tps-p4-v2\n'
  printf 'p4.scope=partial-river-point-calculator\n'
  printf 'p4.result=%s\n' "$p4_result"
  printf 'p4.samples_expected=10\n'
  printf 'p4.samples_observed=10\n'
  printf 'p4.required_completed_per_sample=100000\n'
  printf 'p4.required_lower_bound_tps=1000\n'
  printf 'p4.confidence=one-sided-95-percent-t9\n'
  printf 'p4.mean_committed_tps=%s\n' "$mean"
  printf 'p4.sample_standard_deviation_tps=%s\n' "$standard_deviation"
  printf 'p4.lower_bound_committed_tps=%s\n' "$lower_bound"
  printf 'p4.git_commit_sha=%s\n' "$(property git.commit_sha "$first_metadata")"
  printf 'p4.configuration_fingerprint=%s\n' "$(property configuration.fingerprint "$first_metadata")"
  for index in $(seq 1 10); do
    label=$(printf '%02d' "$index")
    artifact="$calculate_dir/sample-$label/tpcc-acceptance.properties"
    metadata="$calculate_dir/sample-$label/run-metadata.properties"
    printf 'p4.sample.%s.artifact=%s\n' "$label" "$artifact"
    printf 'p4.sample.%s.artifact_sha256=%s\n' "$label" "$(hash_file "$artifact")"
    printf 'p4.sample.%s.metadata=%s\n' "$label" "$metadata"
    printf 'p4.sample.%s.metadata_sha256=%s\n' "$label" "$(hash_file "$metadata")"
    printf 'p4.sample.%s.terminal=%s\n' "$label" "$metadata.terminal-receipt"
    printf 'p4.sample.%s.terminal_sha256=%s\n' "$label" \
      "$(hash_file "$metadata.terminal-receipt")"
  done
} >"$staged"
provenance_publish_file "$staged" "$result_file" || {
  rm -f -- "$staged"
  fail "refusing to overwrite $result_file"
}
rm -f -- "$staged"
exit "$exit_code"
