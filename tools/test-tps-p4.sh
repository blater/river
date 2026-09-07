#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/tps-provenance.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-p4-test.XXXXXX")
finish_test() {
  local status=$?
  trap - EXIT
  if ((status == 0)); then
    rm -rf -- "$test_root" || status=1
  else
    printf 'failed_fixture=%s\n' "$test_root" >&2
    printf 'validator_stderr=%s/valid.err\n' "$test_root" >&2
  fi
  exit "$status"
}
trap finish_test EXIT
tests=0

pass() {
  tests=$((tests + 1))
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

replace_property() {
  local file=$1
  local key=$2
  local value=$3
  local staged="$file.replaced"
  awk -v key="$key" -v value="$value" '
    index($0, key "=") == 1 { print key "=" value; next }
    { print }
  ' "$file" >"$staged"
  mv -- "$staged" "$file"
}

write_artifact() {
  local artifact=$1
  local run_id=$2
  cat >"$artifact" <<EOF
artifact.schema=river-tpcc-acceptance-v2
run.id=$run_id
database.digest.sha256=$(printf '%064d' 7)
config.seed=42
config.warehouses=1
config.standard_scale=true
config.standard_one_warehouse=true
config.districts=10
config.customers_per_district=3000
config.items=100000
config.orders_per_district=3000
config.terminals=10
config.terminal_homes=10
config.scheduling=NO_WAIT_STRESS
config.mix=STANDARD
config.isolation_contract=SERIALIZABLE
config.jdbc_isolation=TRANSACTION_SERIALIZABLE
config.program_isolation=SERIALIZABLE
config.evidence=ALPHA3
config.warmup_seconds=300
config.measured_seconds=100
config.batch_rows=32
config.maximum_attempts=4
config.retry_base_nanos=1000
config.retry_maximum_nanos=1000000
measurement.completed_transactions_at_cutoff=100000
measurement.new_order.committed=20000
measurement.new_order.failed=0
measurement.new_order.retry_exhausted=0
measurement.payment.committed=20000
measurement.payment.failed=0
measurement.payment.retry_exhausted=0
measurement.order_status.committed=20000
measurement.order_status.failed=0
measurement.order_status.retry_exhausted=0
measurement.delivery.committed=20000
measurement.delivery.failed=0
measurement.delivery.retry_exhausted=0
measurement.stock_level.committed=20000
measurement.stock_level.failed=0
measurement.stock_level.retry_exhausted=0
EOF
}

write_metadata() {
  local metadata=$1
  local artifact=$2
  local label=$3
  local evidence_run_id=$4
  local terminal=$5
  local owner_pid=4242 owner_start='Fri Sep 4 12:00:00 2026'
  local owner_identity commitment
  owner_identity=$(provenance_owner_identity_hash \
    "$evidence_run_id" "$owner_pid" "$owner_start")
  commitment=$(provenance_terminal_commitment_hash \
    "$evidence_run_id" "$owner_identity" "$(printf '%064d' 8)")
  cat >"$metadata" <<EOF
tool.schema=river-tps-tool-v4
run.result=provisional
run.phase=terminal_pending
run.status=TERMINAL_RECEIPT_REQUIRED
run.exit_status=1
run.provisional_result=completed
run.provisional_phase=checkpoint
run.provisional_status=OK
run.provisional_exit_status=0
run.sample_id=p4-$label
evidence.run_id=$evidence_run_id
terminal.required=true
terminal.path=$terminal
terminal.commitment_sha256=$commitment
publisher.pid=$owner_pid
publisher.start=$owner_start
publisher.identity_sha256=$owner_identity
git.commit_sha=$(printf '%064d' 9)
git.dirty_state=clean
git.status_sha256=$(printf '%064d' 1)
environment.java_version=25
environment.java_launcher_sha256=$(printf '%064d' 5)
host.guarantee=qualified
host.release_outcome=pending
host.lease.evidence_run_id=$evidence_run_id
host.lease.owner_pid=$owner_pid
host.lease.owner_start=$owner_start
host.lease.owner_identity_sha256=$owner_identity
host.lease.nonce=$(printf '%064d' 8)
host.lease.terminal_commitment_sha256=$commitment
provenance.host_exclusion_valid=true
provenance.build_valid=true
provenance.build_id=$(printf '%064d' 5)
provenance.source_manifest_sha256=$(provenance_sha256_file "$build_fixture/source.before.tsv")
provenance.classpath_sha256=$(provenance_sha256_file "$build_fixture/classpath.tsv")
environment.host=fixture
configuration.fingerprint=$(printf '%064d' 2)
configuration.backend=river
configuration.profile=standard
configuration.mix=standard
configuration.isolation=serializable
configuration.scheduling=no-wait-stress
configuration.evidence=alpha3
configuration.fresh_load=true
configuration.warehouses=1
configuration.terminals=10
configuration.batch_rows=32
configuration.maximum_attempts=4
configuration.warmup_seconds=300
configuration.measured_seconds=100
configuration.runner_timeout_seconds=2400
configuration.server_start_timeout_seconds=30
configuration.server_stop_timeout_seconds=20
configuration.seed=42
artifact.run_id=$(provenance_property_once run.id "$artifact")
artifact.database_digest_sha256=$(provenance_property_once database.digest.sha256 "$artifact")
artifact.sha256=$(provenance_sha256_file "$artifact")
output.stdout_sha256=$(printf '%064d' 3)
output.stderr_sha256=$(printf '%064d' 4)
output.combined_sha256=$(printf '%064d' 5)
output.server_log_sha256=$(printf '%064d' 6)
output.server_metrics_sha256=$(printf '%064d' 7)
EOF
}

build_fixture="$test_root/build-record"
mkdir "$build_fixture"
for file in "${PROVENANCE_BUILD_PAYLOAD_FILES[@]}"; do
  case $file in
    host-observations.tsv) {
      printf '1\tbuild-pre\n'
      printf '2\tbuild-post\n'
    } >"$build_fixture/$file" ;;
    host-processes.tsv) {
      printf '1\t4242\t1\tFri Sep 4 11:00:00 2026\tnone\n'
      printf '2\t4242\t1\tFri Sep 4 11:00:00 2026\tnone\n'
    } >"$build_fixture/$file" ;;
    host-classifications.tsv) {
      printf '1\tbuild-pre\tclean\t-\n'
      printf '2\tbuild-post\tclean\t-\n'
    } >"$build_fixture/$file" ;;
    host-violations.tsv) : >"$build_fixture/$file" ;;
    *) printf 'fixture\n' >"$build_fixture/$file" ;;
  esac
done
{
  printf 'schema=river-tps-runtime-v3\nbuild.id=%064d\n' 5
  printf 'build.inputs=workspace_declared\nbuild.cache_trust=gradle_declared_inputs\n'
  printf 'gradle.user.home=/fake/gradle-user-home\n'
  printf 'compiler.fixture.home=/fake/java\ncompiler.fixture.version=25\n'
  printf 'compiler.fixture.executable=/fake/java/bin/javac\n'
  printf 'compiler.fixture.launcher_sha256=%064d\n' 1
  printf 'compiler.fixture.selected_options_sha256=%064d\n' 2
} >"$build_fixture/runtime.properties"
build_lease_run_id=$(printf '%064d' 4)
build_lease_start='Fri Sep 4 11:00:00 2026'
build_lease_identity=$(provenance_owner_identity_hash "$build_lease_run_id" 4242 "$build_lease_start")
build_lease_nonce=$(printf '%064d' 6)
build_lease_commitment=$(provenance_terminal_commitment_hash "$build_lease_run_id" "$build_lease_identity" "$build_lease_nonce")
provenance_seal_build_record "$build_fixture" "$(printf '%064d' 5)"
provenance_complete_build_record "$build_fixture" "$(printf '%064d' 5)" \
  "$build_lease_run_id" 4242 "$build_lease_start" "$build_lease_identity" \
  "$build_lease_nonce" "$build_lease_commitment"

make_fixture() {
  local root=$1
  local terminal_result=${2:-success}
  local terminal_status=OK
  local release_outcome=released
  mkdir -p "$root"
  for index in $(seq 1 10); do
    local label sample artifact metadata terminal run_id evidence_run_id owner_identity
    local owner_pid=4242 owner_start='Fri Sep 4 12:00:00 2026'
    label=$(printf '%02d' "$index")
    sample="$root/sample-$label"
    artifact="$sample/tpcc-acceptance.properties"
    metadata="$sample/run-metadata.properties"
    terminal="$metadata.terminal-receipt"
    run_id=$(printf '%064d' "$((100 + index))")
    evidence_run_id=$(printf '%064d' "$((200 + index))")
    mkdir -p "$sample"
    cp -R "$build_fixture" "$sample/build-record"
    {
      printf '1\tpre-source\n'
      printf '2\tpre-client\n'
      printf '3\tpost-cleanup\n'
      printf '4\tpre-publication\n'
    } >"$sample/host-observations.tsv"
    {
      printf '1\t4242\t1\tFri Sep 4 12:00:00 2026\tnone\n'
      printf '2\t4242\t1\tFri Sep 4 12:00:00 2026\tnone\n'
      printf '3\t4242\t1\tFri Sep 4 12:00:00 2026\tnone\n'
      printf '4\t4242\t1\tFri Sep 4 12:00:00 2026\tnone\n'
    } >"$sample/host-processes.tsv"
    {
      printf '1\tpre-source\tclean\t-\n'
      printf '2\tpre-client\tclean\t-\n'
      printf '3\tpost-cleanup\tclean\t-\n'
      printf '4\tpre-publication\tclean\t-\n'
    } >"$sample/host-classifications.tsv"
    : >"$sample/host-violations.tsv"
    : >"$sample/provenance-checkpoints.tsv"
    for checkpoint in startup server client_start client_finish result publication metadata terminal; do
      printf '%s\t1\t%s\t%s\t%s\t%s\n' "$checkpoint" \
        "$(printf '%064d' 1)" "$(printf '%064d' 2)" \
        "$(printf '%064d' 3)" "$(printf '%064d' 4)" \
        >>"$sample/provenance-checkpoints.tsv"
    done
    write_artifact "$artifact" "$run_id"
    write_metadata "$metadata" "$artifact" "$label" "$evidence_run_id" "$terminal"
    owner_identity=$(provenance_property_once publisher.identity_sha256 "$metadata")
    if [[ $terminal_result != success ]]; then
      terminal_status=FIXTURE_INVALID
    fi
    provenance_write_terminal_receipt "$terminal" "$terminal_result" "$terminal_status" \
      "$evidence_run_id" "$run_id" "$(provenance_sha256_file "$metadata")" \
      4242 'Fri Sep 4 12:00:00 2026' "$owner_identity" "$(printf '%064d' 8)" \
      "$(provenance_property_once terminal.commitment_sha256 "$metadata")" "$sample" \
      "$release_outcome" "$evidence_run_id" "$owner_pid" "$owner_start" \
      "$owner_identity" "$(provenance_property_once terminal.commitment_sha256 "$metadata")" qualified
  done
}

valid="$test_root/valid"
make_fixture "$valid"
if ! "$script_dir/tps-p4.sh" --calculate="$valid" >"$test_root/valid.out" 2>"$test_root/valid.err"; then
  fail "qualified host ownership was not promoted"
fi
[[ -f $valid/p4-result.properties ]] || fail "qualified diagnostics did not publish a P4 result"
grep -F 'p4_point=passed' "$test_root/valid.out" >/dev/null ||
  fail "qualified diagnostics did not pass the P4 point"
pass

# Rejection must preserve an existing result, even when it cannot consume the new inputs.
printf 'existing-result\n' >"$valid/p4-result.properties"
valid_hash=$(provenance_sha256_file "$valid/p4-result.properties")
if "$script_dir/tps-p4.sh" --calculate="$valid" >/dev/null 2>&1; then
  fail "existing result was overwritten"
fi
[[ $(provenance_sha256_file "$valid/p4-result.properties") == "$valid_hash" ]] ||
  fail "existing result bytes changed"
pass

legacy="$test_root/legacy"
make_fixture "$legacy"
replace_property "$legacy/sample-01/run-metadata.properties" tool.schema river-tps-tool-v1
if "$script_dir/tps-p4.sh" --calculate="$legacy" >/dev/null 2>&1; then
  fail "v1 metadata was accepted"
fi
pass

missing="$test_root/missing"
make_fixture "$missing"
rm -- "$missing/sample-01/run-metadata.properties.terminal-receipt"
if "$script_dir/tps-p4.sh" --calculate="$missing" >/dev/null 2>&1; then
  fail "missing terminal receipt was accepted"
fi
pass

mutated="$test_root/mutated"
make_fixture "$mutated"
replace_property "$mutated/sample-01/run-metadata.properties" environment.host changed
if "$script_dir/tps-p4.sh" --calculate="$mutated" >/dev/null 2>&1; then
  fail "metadata changed after terminal publication was accepted"
fi
pass

failed="$test_root/failed"
make_fixture "$failed" evidence_invalid
if "$script_dir/tps-p4.sh" --calculate="$failed" >/dev/null 2>&1; then
  fail "failure terminal receipt was accepted as success"
fi
pass

noncanonical="$test_root/noncanonical"
make_fixture "$noncanonical"
printf 'unexpected=duplicate\n' >>"$noncanonical/sample-01/run-metadata.properties.terminal-receipt"
if "$script_dir/tps-p4.sh" --calculate="$noncanonical" >/dev/null 2>&1; then
  fail "noncanonical terminal receipt was accepted"
fi
pass

echo "PASS: $tests P4 current-evidence boundary tests"
