#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-p4-test.XXXXXX")
finish_test() {
  local status=$?
  trap - EXIT
  if ((status == 0)); then
    rm -rf -- "$test_root" || status=1
  else
    printf 'failed_fixture=%s\n' "$test_root" >&2
  fi
  exit "$status"
}
trap finish_test EXIT
tests=0

pass() { tests=$((tests + 1)); }

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

replace_property() {
  local file=$1 key=$2 value=$3 staged
  staged="$file.replaced"
  awk -v key="$key" -v value="$value" '
    index($0, key "=") == 1 { print key "=" value; next }
    { print }
  ' "$file" >"$staged"
  mv -- "$staged" "$file"
}

write_artifact() {
  local artifact=$1
  cat >"$artifact" <<'EOF'
artifact.schema=river-tpcc-acceptance-v2
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
  cat >"$metadata" <<'EOF'
run.result=completed
run.phase=checkpoint
run.status=OK
run.exit_status=0
run.version=feature/tps-cleanup
git.branch=feature/tps-cleanup
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
EOF
}

make_fixture() {
  local root=$1
  mkdir -p "$root"
  for index in $(seq 1 10); do
    local label sample
    label=$(printf '%02d' "$index")
    sample="$root/sample-$label"
    mkdir -p "$sample"
    write_artifact "$sample/tpcc-acceptance.properties"
    write_metadata "$sample/run-metadata.properties"
  done
}

valid="$test_root/valid"
make_fixture "$valid"
if ! "$script_dir/tps-p4.sh" --calculate="$valid" >"$test_root/valid.out" 2>"$test_root/valid.err"; then
  fail "completed workload was not accepted"
fi
[[ -f $valid/p4-result.properties ]] || fail "P4 result was not published"
grep -F 'p4_point=passed' "$test_root/valid.out" >/dev/null || fail "P4 point did not pass"
pass

printf 'existing-result\n' >"$valid/p4-result.properties"
if "$script_dir/tps-p4.sh" --calculate="$valid" >/dev/null 2>&1; then
  fail "existing result was overwritten"
fi
grep -Fx 'existing-result' "$valid/p4-result.properties" >/dev/null || fail "existing result changed"
pass

failed="$test_root/failed"
make_fixture "$failed"
replace_property "$failed/sample-01/run-metadata.properties" run.status FAILED
if "$script_dir/tps-p4.sh" --calculate="$failed" >/dev/null 2>&1; then
  fail "failed run metadata was accepted"
fi
pass

mismatched="$test_root/mismatched"
make_fixture "$mismatched"
replace_property "$mismatched/sample-02/run-metadata.properties" run.version feature/other
if "$script_dir/tps-p4.sh" --calculate="$mismatched" >/dev/null 2>&1; then
  fail "different run versions were accepted"
fi
pass

bad_workload="$test_root/bad-workload"
make_fixture "$bad_workload"
replace_property "$bad_workload/sample-02/run-metadata.properties" configuration.terminals 11
if "$script_dir/tps-p4.sh" --calculate="$bad_workload" >/dev/null 2>&1; then
  fail "different workload configurations were accepted"
fi
pass

bad_artifact="$test_root/bad-artifact"
make_fixture "$bad_artifact"
replace_property "$bad_artifact/sample-01/tpcc-acceptance.properties" measurement.payment.failed 1
if "$script_dir/tps-p4.sh" --calculate="$bad_artifact" >/dev/null 2>&1; then
  fail "failed transactions were accepted"
fi
pass

echo "PASS: $tests P4 workload and metadata tests"
