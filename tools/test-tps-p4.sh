#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/tps-provenance.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-p4-test.XXXXXX")
trap 'rm -rf -- "$test_root"' EXIT
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
tool.schema=river-tps-tool-v2
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
lease.owner_pid=$owner_pid
lease.owner_start=$owner_start
lease.owner_identity_sha256=$owner_identity
git.commit_sha=$(printf '%064d' 9)
git.dirty_state=clean
git.status_sha256=$(printf '%064d' 1)
environment.java_version=25
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

make_fixture() {
  local root=$1
  local terminal_result=${2:-success}
  local terminal_status=OK
  local release_outcome=released
  mkdir -p "$root"
  for index in $(seq 1 10); do
    local label sample artifact metadata terminal run_id evidence_run_id owner_identity
    label=$(printf '%02d' "$index")
    sample="$root/sample-$label"
    artifact="$sample/tpcc-acceptance.properties"
    metadata="$sample/run-metadata.properties"
    terminal="$metadata.terminal-receipt"
    run_id=$(printf '%064d' "$((100 + index))")
    evidence_run_id=$(printf '%064d' "$((200 + index))")
    mkdir -p "$sample"
    : >"$sample/host-observations.tsv"
    : >"$sample/host-processes.tsv"
    : >"$sample/host-classifications.tsv"
    : >"$sample/host-violations.tsv"
    : >"$sample/host-provisional-daemons.tsv"
    : >"$sample/provenance-checkpoints.tsv"
    write_artifact "$artifact" "$run_id"
    write_metadata "$metadata" "$artifact" "$label" "$evidence_run_id" "$terminal"
    owner_identity=$(provenance_property_once lease.owner_identity_sha256 "$metadata")
    if [[ $terminal_result != success ]]; then
      terminal_status=FIXTURE_INVALID
    fi
    provenance_write_terminal_receipt "$terminal" "$terminal_result" "$terminal_status" \
      "$evidence_run_id" "$run_id" "$(provenance_sha256_file "$metadata")" \
      4242 'Fri Sep 4 12:00:00 2026' "$owner_identity" "$(printf '%064d' 8)" \
      "$(provenance_property_once terminal.commitment_sha256 "$metadata")" "$sample" \
      "$release_outcome" 1777777777
  done
}

valid="$test_root/valid"
make_fixture "$valid"
"$script_dir/tps-p4.sh" --calculate="$valid" >"$test_root/valid.out" 2>"$test_root/valid.err"
grep -Fx 'p4_point=passed' "$test_root/valid.out" >/dev/null ||
  fail "valid v2 terminal evidence did not pass"
grep -Fx 'tool.schema=river-tps-p4-v2' "$valid/p4-result.properties" >/dev/null ||
  fail "v2 result schema was not written"
grep -Fx 'p4.scope=partial-river-point-calculator' "$valid/p4-result.properties" >/dev/null ||
  fail "partial scope was not declared"
grep -Fx 'p4.result=partial_point_passed' "$valid/p4-result.properties" >/dev/null ||
  fail "partial point result was not recorded"
pass

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

echo "PASS: $tests P4 v2 boundary tests"
