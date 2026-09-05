#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/tps-provenance.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-provenance-test.XXXXXX")
trap 'rm -rf -- "$test_root"' EXIT
tests=0

pass() {
  tests=$((tests + 1))
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_equal() {
  [[ $1 == "$2" ]] || fail "expected '$1' to equal '$2'"
}

assert_contains() {
  grep -F -- "$2" "$1" >/dev/null || fail "$1 does not contain $2"
}

replace_property() {
  local file=$1 key=$2 value=$3
  local staged="$file.replaced"
  awk -v key="$key" -v value="$value" '
    index($0, key "=") == 1 { print key "=" value; next }
    { print }
  ' "$file" >"$staged"
  mv -- "$staged" "$file"
}

retained_temp_dir() {
  local output_dir=$1
  local descriptor
  descriptor=$(tr '\0' '\n' <"$output_dir/build-command.argv" |
    sed -n 's/^-PriverTpsClasspathOutput=//p' | head -1)
  [[ -n $descriptor ]] || fail "cannot recover owned temporary path from retained build argv"
  dirname -- "$descriptor"
}

classpath_root="$test_root/classpath"
mkdir -p "$classpath_root/classes/a" "$classpath_root/resources"
printf 'class-a\n' >"$classpath_root/classes/a/A.class"
printf 'resource\n' >"$classpath_root/resources/example.txt"
printf 'jar\n' >"$classpath_root/dependency.jar"
descriptor="$test_root/runtime.properties"
{
  printf 'schema=river-tps-runtime-v1\n'
  printf 'classpath=%s\n' "$classpath_root/classes"
  printf 'classpath=%s\n' "$classpath_root/resources"
  printf 'classpath=%s\n' "$classpath_root/dependency.jar"
} >"$descriptor"

provenance_write_classpath_manifest "$descriptor" "$test_root/manifest-a.tsv"
provenance_write_classpath_manifest "$descriptor" "$test_root/manifest-b.tsv"
cmp -s "$test_root/manifest-a.tsv" "$test_root/manifest-b.tsv" ||
  fail "classpath manifest is not deterministic"
assert_contains "$test_root/manifest-a.tsv" $'entry\t000003\tfile'
assert_contains "$test_root/manifest-a.tsv" $'file\t000001'
pass

printf 'mutated\n' >"$classpath_root/classes/a/A.class"
provenance_write_classpath_manifest "$descriptor" "$test_root/manifest-mutated.tsv"
cmp -s "$test_root/manifest-a.tsv" "$test_root/manifest-mutated.tsv" &&
  fail "classpath byte mutation was not detected"
pass

printf 'classpath=%s\n' "$classpath_root/missing" >"$test_root/missing.properties"
if provenance_write_classpath_manifest \
    "$test_root/missing.properties" "$test_root/missing.tsv" 2>/dev/null; then
  fail "missing classpath entry was accepted"
fi
printf 'classpath=%s\n' "$classpath_root/dependency.jar" >"$test_root/mismatch.properties"
provenance_write_classpath_manifest \
  "$test_root/mismatch.properties" "$test_root/mismatch.tsv"
cmp -s "$test_root/manifest-mutated.tsv" "$test_root/mismatch.tsv" &&
  fail "mismatched classpath entries were accepted as identical"
pass

git_root="$test_root/git"
mkdir -p "$git_root"
git -C "$git_root" init -q
git -C "$git_root" config user.name test
git -C "$git_root" config user.email test@example.invalid
printf 'source\n' >"$git_root/source.txt"
git -C "$git_root" add source.txt
git -C "$git_root" commit -qm initial
provenance_write_source_manifest "$git_root" "$test_root/source-a.tsv"
printf 'changed\n' >"$git_root/source.txt"
provenance_write_source_manifest "$git_root" "$test_root/source-b.tsv"
cmp -s "$test_root/source-a.tsv" "$test_root/source-b.tsv" &&
  fail "source mutation was not detected"
pass

snapshot="$test_root/processes.txt"
cat >"$snapshot" <<'EOF'
 100   90 Fri Sep  4 12:00:00 2026 bash tools/tps-test.sh
 101  100 Fri Sep  4 12:00:01 2026 java GradleWrapperMain test
 200    1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0
 300    1 Fri Sep  4 12:00:02 2026 java GradleWrapperMain test
 400    1 Fri Sep  4 12:00:03 2026 /work/river-harness/benchmark run river
 500    1 Fri Sep  4 12:00:04 2026 async-profiler start
 600    1 Fri Sep  4 12:00:05 2026 pgbench -c 8
 700    1 Fri Sep  4 12:00:06 2026 unrelated --password=do-not-retain
EOF
provenance_normalize_snapshot "$snapshot" "$test_root/processes.tsv"
provenance_classify_snapshot "$test_root/processes.tsv" 100 >"$test_root/classification.tsv"
assert_contains "$test_root/classification.tsv" $'allowed_idle_gradle_daemon\t200'
assert_contains "$test_root/classification.tsv" $'violation\tgradle_activity\t300'
assert_contains "$test_root/classification.tsv" $'violation\tdatabase_harness\t400'
assert_contains "$test_root/classification.tsv" $'violation\tprofile\t500'
assert_contains "$test_root/classification.tsv" $'violation\tdatabase_workload\t600'
if grep -F $'\t101' "$test_root/classification.tsv" >/dev/null; then
  fail "owned child process was classified as overlap"
fi
if grep -F 'do-not-retain' "$test_root/processes.tsv" >/dev/null; then
  fail "secret process argument entered retained observations"
fi
assert_contains "$test_root/processes.tsv" $'700\t1\tFri Sep 4 12:00:06 2026\tnone'
sed 's/do-not-retain/different-low-entropy-secret/' "$snapshot" >"$test_root/processes-secret-changed.txt"
provenance_normalize_snapshot \
  "$test_root/processes-secret-changed.txt" "$test_root/processes-secret-changed.tsv"
cmp -s "$test_root/processes.tsv" "$test_root/processes-secret-changed.tsv" ||
  fail "unrelated secret influenced retained process evidence"
pass

own_monitor="$test_root/own-monitor"
mkdir "$own_monitor"
: >"$own_monitor/host-observations.tsv"
: >"$own_monitor/host-violations.tsv"
: >"$own_monitor/owned-build"
: >"$own_monitor/provisional.tsv"
printf 'build\n' >"$own_monitor/phase"
provenance_capture_processes() { printf '%s\n' ' 200    1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'; }
provenance_gradle_daemon_state() { printf 'busy\n'; }
provenance_gradle_daemon_home() { printf '/owned/home\n'; }
provenance_process_start() { printf 'Fri Sep 4 11:00:00 2026\n'; }
sleep() { : >"$own_monitor/stop"; }
provenance_monitor_host "$own_monitor" 100 "$own_monitor/stop" 0 \
  /owned/home "$own_monitor/owned-build" "$own_monitor/phase" 65536 1 \
  "$own_monitor/provisional.tsv"
[[ ! -s $own_monitor/host-violations.tsv ]] || fail "owned busy Gradle daemon was rejected"
assert_contains "$own_monitor/host-classifications.tsv" $'provisional_owned_gradle_daemon\t200'
printf 'gradle.process.pid=200\n' >"$own_monitor/descriptor"
provenance_validate_gradle_daemons "$own_monitor/descriptor" \
  "$own_monitor/provisional.tsv" || fail "exact owned Gradle daemon was rejected"
printf 'gradle.process.pid=201\n' >"$own_monitor/foreign-descriptor"
if provenance_validate_gradle_daemons "$own_monitor/foreign-descriptor" \
    "$own_monitor/provisional.tsv"; then
  fail "different Gradle daemon was accepted by home and marker alone"
fi
printf 'gradle.process.pid=200\ngradle.process.pid=201\n' >"$own_monitor/duplicate-descriptor"
if provenance_validate_gradle_daemons "$own_monitor/duplicate-descriptor" \
    "$own_monitor/provisional.tsv"; then
  fail "duplicate Gradle-owned daemon identity was accepted"
fi
[[ -z $(find "$own_monitor" \( -name 'host-processes.0*' -o \
  -name 'host-classification.0*' \) -print -quit) ]] ||
  fail "per-sample host evidence was retained without a bound"
pass

foreign_monitor="$test_root/foreign-monitor"
mkdir "$foreign_monitor"
: >"$foreign_monitor/host-observations.tsv"
: >"$foreign_monitor/host-violations.tsv"
: >"$foreign_monitor/owned-build"
: >"$foreign_monitor/provisional.tsv"
printf 'build\n' >"$foreign_monitor/phase"
provenance_gradle_daemon_home() { printf '/foreign/home\n'; }
sleep() { : >"$foreign_monitor/stop"; }
provenance_monitor_host "$foreign_monitor" 100 "$foreign_monitor/stop" 0 \
  /owned/home "$foreign_monitor/owned-build" "$foreign_monitor/phase" 65536 1 \
  "$foreign_monitor/provisional.tsv"
assert_contains "$foreign_monitor/host-violations.tsv" $'violation\tbusy_gradle_daemon\t200'
pass

provenance_process_start() {
  ps -p "$1" -o lstart= 2>/dev/null |
    awk 'NF >= 5 {print $1 " " $2 " " $3 " " $4 " " $5}'
}

lease="$test_root/lease"
lease_run_id=$(printf '%064d' 1)
lease_nonce=$(printf '%064d' 2)
write_lease_owner() {
  local destination=$1 run_id=$2 pid=$3 started=$4 nonce=$5
  local identity commitment
  identity=$(provenance_owner_identity_hash "$run_id" "$pid" "$started")
  commitment=$(provenance_terminal_commitment_hash "$run_id" "$identity" "$nonce")
  {
    printf 'schema=river-tps-host-lease-v2\n'
    printf 'evidence_run_id=%s\n' "$run_id"
    printf 'pid=%s\n' "$pid"
    printf 'start=%s\n' "$started"
    printf 'owner_identity_sha256=%s\n' "$identity"
    printf 'terminal_commitment_sha256=%s\n' "$commitment"
  } >"$destination"
}
provenance_process_start() {
  [[ $1 != 999 ]] && printf 'Fri Sep  4 12:00:00 2026\n'
}
mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce" ||
  fail "stale PID-reuse lease was not reclaimed"
provenance_release_lease "$lease" || fail "owned lease was not released"
mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" "${BASHPID:-$$}" \
  "$(provenance_process_start "${BASHPID:-$$}")" "$lease_nonce"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "live lease owner was displaced"
fi
rm -rf -- "$lease"
mkdir "$lease"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "ownerless crash-before-owner lease was reclaimed"
fi
rm -rf -- "$lease"
mkdir "$lease"
printf 'not-an-owner-record\n' >"$lease/owner"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "malformed lease owner was reclaimed"
fi
rm -rf -- "$lease"
mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
sed -i.bak 's/^owner_identity_sha256=.*/owner_identity_sha256=0000000000000000000000000000000000000000000000000000000000000000/' "$lease/owner"
rm -f -- "$lease/owner.bak"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease with mismatched owner token was reclaimed"
fi
rm -rf -- "$lease"
mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
printf 'pid=998\n' >>"$lease/owner"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease with duplicate or extra owner fields was reclaimed"
fi
rm -rf -- "$lease"
mkdir "$lease"
write_lease_owner "$lease/owner.canonical" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
{
  sed -n '2p' "$lease/owner.canonical"
  sed -n '1p' "$lease/owner.canonical"
  sed -n '3,6p' "$lease/owner.canonical"
} >"$lease/owner"
rm -f -- "$lease/owner.canonical"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease with reordered canonical owner fields was reclaimed"
fi
rm -rf -- "$lease"
ln() { return 1; }
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease acquisition accepted owner-record publication failure"
fi
unset -f ln
[[ -d $lease && ! -e $lease/owner ]] ||
  fail "owner publication failure did not remain fail-closed for manual recovery"
rm -rf -- "$lease"
pass

lease_target="$test_root/lease-target"
mkdir "$lease_target"
ln -s "$lease_target" "$lease"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "symlinked lease directory was reclaimed"
fi
[[ -L $lease && -d $lease_target ]] || fail "symlinked lease ambiguity was modified"
rm -- "$lease"
rmdir "$lease_target"

mkdir "$lease"
owner_target="$test_root/owner-target"
write_lease_owner "$owner_target" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
ln -s "$owner_target" "$lease/owner"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "symlinked lease owner was reclaimed"
fi
[[ -L $lease/owner && -f $owner_target ]] || fail "symlinked owner ambiguity was modified"
rm -- "$lease/owner" "$owner_target"
rmdir "$lease"

mkdir "$lease"
owner_target="$test_root/hardlinked-owner"
write_lease_owner "$owner_target" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
ln "$owner_target" "$lease/owner"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "multiply linked lease owner was reclaimed"
fi
[[ -f $lease/owner && -f $owner_target ]] || fail "hardlink ambiguity was modified"
rm -- "$lease/owner" "$owner_target"
rmdir "$lease"

mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
: >"$lease/unexpected"
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease directory with an unexpected entry was reclaimed"
fi
[[ -f $lease/owner && -f $lease/unexpected ]] || fail "unexpected lease entries were modified"
rm -- "$lease/owner" "$lease/unexpected"
rmdir "$lease"

mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
rmdir() { return 1; }
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "stale lease with failed directory removal was reclaimed"
fi
unset -f rmdir
[[ -d $lease && ! -e $lease/owner ]] ||
  fail "failed stale directory removal was not preserved for manual recovery"
rmdir "$lease"

mkdir "$lease"
write_lease_owner "$lease/owner" "$lease_run_id" 999 definitely-not-this-process "$lease_nonce"
lease_hash_calls="$test_root/lease-hash-calls"
: >"$lease_hash_calls"
provenance_sha256_file() {
  if [[ $1 == "$lease/owner" ]]; then
    printf x >>"$lease_hash_calls"
    if [[ $(wc -c <"$lease_hash_calls") -gt 1 ]]; then
      printf '%064d\n' 0
      return
    fi
  fi
  shasum -a 256 "$1" | awk '{print $1}'
}
if provenance_acquire_lease "$lease" "$lease_run_id" "$lease_nonce"; then
  fail "lease owner changed during stale verification was reclaimed"
fi
source "$script_dir/tps-provenance.sh"
[[ -f $lease/owner ]] || fail "raced lease owner was removed"
rm -- "$lease/owner"
rmdir "$lease"
pass

provenance_process_start() {
  ps -p "$1" -o lstart= 2>/dev/null |
    awk 'NF >= 5 {print $1 " " $2 " " $3 " " $4 " " $5}'
}
race_lease="$test_root/simultaneous-lease"
race_results="$test_root/simultaneous-results"
race_release="$test_root/simultaneous-release"
: >"$race_results"
for contender in 1 2; do
  bash -c '
    set -euo pipefail
    source "$1"
    provenance_process_start() { printf "fixture-start-%s\n" "$1"; }
    if provenance_acquire_lease "$2" "$6" "$7"; then
      printf "success\t%s\n" "$5" >>"$3"
      while [[ ! -e $4 ]]; do /bin/sleep 0.01; done
      provenance_release_lease "$2"
    else
      printf "failed\t%s\n" "$5" >>"$3"
    fi
  ' bash "$script_dir/tps-provenance.sh" "$race_lease" "$race_results" \
    "$race_release" "$contender" "$(printf '%064d' "$contender")" \
    "$lease_nonce" &
done
for _ in {1..200}; do
  [[ $(wc -l <"$race_results") -eq 2 ]] && break
  /bin/sleep 0.01
done
assert_equal "$(awk '$1 == "success" {count++} END {print count+0}' "$race_results")" 1
: >"$race_release"
wait
pass

printf 'first\n' >"$test_root/publication-source"
provenance_publish_file "$test_root/publication-source" "$test_root/published"
printf 'second\n' >"$test_root/publication-source"
if provenance_publish_file "$test_root/publication-source" "$test_root/published"; then
  fail "immutable publication was overwritten"
fi
assert_equal "$(tr -d '\n' <"$test_root/published")" first
pass

if provenance_run_logged "$test_root/failure.log" "$test_root/failure.argv" \
    "$test_root/failure.command" sh -c 'printf "complete-log\\n"; exit 17'; then
  fail "failed build command returned success"
else
  status=$?
fi
assert_equal "$status" 17
assert_contains "$test_root/failure.log" complete-log
[[ -s $test_root/failure.argv && -s $test_root/failure.command ]] ||
  fail "failed build provenance is incomplete"
pass

race_monitor="$test_root/race-monitor"
mkdir "$race_monitor"
: >"$race_monitor/host-observations.tsv"
: >"$race_monitor/host-violations.tsv"
: >"$race_monitor/start-calls"
: >"$race_monitor/provisional.tsv"
printf 'prebuild\n' >"$race_monitor/phase"
provenance_capture_processes() { printf '%s\n' ' 200    1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'; }
provenance_gradle_daemon_state() { printf 'busy\n'; }
provenance_process_start() {
  printf x >>"$race_monitor/start-calls"
  if [[ $(wc -c <"$race_monitor/start-calls") -eq 1 ]]; then
    printf 'Fri Sep 4 11:00:00 2026\n'
  else
    printf 'Fri Sep 4 11:00:01 2026\n'
  fi
}
sleep() { : >"$race_monitor/stop"; }
provenance_monitor_host "$race_monitor" 100 "$race_monitor/stop" 0 '' '' \
  "$race_monitor/phase" 65536 1 "$race_monitor/provisional.tsv"
assert_contains "$race_monitor/host-violations.tsv" $'violation\tprocess_identity_race\t200'
pass

monitor_dir="$test_root/monitor"
mkdir "$monitor_dir"
: >"$monitor_dir/host-observations.tsv"
: >"$monitor_dir/host-violations.tsv"
printf 'prebuild\n' >"$monitor_dir/phase"
provenance_capture_processes() { return 1; }
if provenance_monitor_host "$monitor_dir" "$$" "$monitor_dir/stop" 0 '' '' \
    "$monitor_dir/phase" 65536 1 "$monitor_dir/provisional.tsv"; then
  fail "process observation race/failure was accepted"
fi
assert_contains "$monitor_dir/host-violations.tsv" process_snapshot_failed
pass

workload_monitor="$test_root/workload-monitor"
mkdir "$workload_monitor"
: >"$workload_monitor/host-observations.tsv"
: >"$workload_monitor/host-violations.tsv"
: >"$workload_monitor/provisional.tsv"
: >"$workload_monitor/jcmd-calls"
printf 'workload\n' >"$workload_monitor/phase"
provenance_capture_processes() { printf '%s\n' ' 200    1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'; }
provenance_process_start() { printf 'Fri Sep 4 11:00:00 2026\n'; }
provenance_gradle_daemon_state() { printf x >>"$workload_monitor/jcmd-calls"; printf 'idle\n'; }
sleep() { : >"$workload_monitor/stop"; }
provenance_monitor_host "$workload_monitor" 100 "$workload_monitor/stop" 0 '' '' \
  "$workload_monitor/phase" 65536 1 "$workload_monitor/provisional.tsv"
[[ -s $workload_monitor/jcmd-calls ]] || fail "daemon inspection was skipped during workload phase"
assert_contains "$workload_monitor/host-classifications.tsv" 'state=idle'
if grep -F 'state=not_inspected' "$workload_monitor/host-classifications.tsv" >/dev/null; then
  fail "workload daemon received blanket acceptance without inspection"
fi
assert_contains "$workload_monitor/host-observations.tsv" $'workload\t'
awk -F '\t' 'NF == 6 && $4 ~ /^[0-9]+$/ {valid=1} END {exit !valid}' \
  "$workload_monitor/host-observations.tsv" ||
  fail "host observation did not retain phase and inspection cost"
pass

busy_workload_monitor="$test_root/busy-workload-monitor"
mkdir "$busy_workload_monitor"
: >"$busy_workload_monitor/host-observations.tsv"
: >"$busy_workload_monitor/host-violations.tsv"
: >"$busy_workload_monitor/provisional.tsv"
printf 'workload\n' >"$busy_workload_monitor/phase"
provenance_gradle_daemon_state() { printf 'busy\n'; }
sleep() { : >"$busy_workload_monitor/stop"; }
provenance_monitor_host "$busy_workload_monitor" 100 "$busy_workload_monitor/stop" 0 '' '' \
  "$busy_workload_monitor/phase" 65536 1 "$busy_workload_monitor/provisional.tsv"
assert_contains "$busy_workload_monitor/host-violations.tsv" $'violation\tbusy_gradle_daemon\t200'
pass

budget_monitor="$test_root/budget-monitor"
mkdir "$budget_monitor"
: >"$budget_monitor/host-observations.tsv"
: >"$budget_monitor/host-violations.tsv"
: >"$budget_monitor/provisional.tsv"
printf 'workload\n' >"$budget_monitor/phase"
provenance_capture_processes() { printf '%s\n' ' 1 0 Fri Sep  4 00:00:00 2026 launchd'; }
if provenance_monitor_host "$budget_monitor" 100 "$budget_monitor/stop" 0 '' '' \
    "$budget_monitor/phase" 512 1 "$budget_monitor/provisional.tsv"; then
  fail "host evidence byte budget was not enforced"
fi
assert_contains "$budget_monitor/host-violations.tsv" host_evidence_budget_exhausted
budget_bytes=$(provenance_evidence_bytes \
  "$budget_monitor/host-observations.tsv" "$budget_monitor/host-processes.tsv" \
  "$budget_monitor/host-classifications.tsv" "$budget_monitor/host-violations.tsv" \
  "$budget_monitor/provisional.tsv")
((budget_bytes <= 512)) || fail "retained host evidence exceeded configured byte budget"
pass

source "$script_dir/tps-provenance.sh"
timeout_started=$SECONDS
provenance_run_bounded 2 1024 "$test_root/bounded-success.log" \
  sh -c 'printf "bounded-success\n"'
assert_contains "$test_root/bounded-success.log" bounded-success
if provenance_run_bounded 1 1024 "$test_root/bounded-command.log" /bin/sleep 5; then
  fail "bounded daemon inspection did not time out"
else
  timeout_status=$?
fi
assert_equal "$timeout_status" 124
((SECONDS - timeout_started < 4)) || fail "bounded daemon inspection exceeded deadline"
pass

bounded_pid="$test_root/bounded-child.pid"
if provenance_run_bounded 1 1024 "$test_root/bounded-closed-output.log" \
    sh -c 'printf "%s\n" "$$" >"$1"; exec /bin/sleep 5' sh "$bounded_pid"; then
  fail "child that kept running after closing output escaped the timeout"
else
  timeout_status=$?
fi
assert_equal "$timeout_status" 124
bounded_child=$(cat "$bounded_pid")
if kill -0 "$bounded_child" 2>/dev/null; then
  fail "timed-out inspection child was not reaped"
fi
if provenance_run_bounded 2 32 "$test_root/bounded-overflow.log" \
    sh -c 'awk "BEGIN { for (i=0; i<100; i++) printf \"x\" }"'; then
  fail "bounded collector accepted oversized output"
else
  overflow_status=$?
fi
assert_equal "$overflow_status" 125
[[ ! -s $test_root/bounded-overflow.log ]] || fail "oversized raw output was retained"
pass

collector_bin="$test_root/collector-bin"
mkdir "$collector_bin"
cat >"$collector_bin/ps" <<'EOF'
#!/usr/bin/env bash
if [[ ${FAKE_COLLECTOR_MODE:-} == slow ]]; then exec /bin/sleep 5; fi
awk 'BEGIN { for (i=0; i<100; i++) printf "0123456789" }'
EOF
cat >"$collector_bin/jcmd" <<'EOF'
#!/usr/bin/env bash
case ${FAKE_COLLECTOR_MODE:-selected} in
  slow) exec /bin/sleep 5 ;;
  large) awk 'BEGIN { for (i=0; i<100; i++) print "unrelated.secret=low-entropy" }' ;;
  selected)
    printf 'unrelated.secret=do-not-retain\n'
    printf 'gradle.user.home=/owned/home\n'
    ;;
esac
EOF
chmod +x "$collector_bin/ps" "$collector_bin/jcmd"
if PATH="$collector_bin:$PATH" FAKE_COLLECTOR_MODE=large \
    provenance_capture_processes 2 64 >"$test_root/raw-ps-overflow"; then
  fail "oversized raw ps snapshot was accepted"
fi
[[ ! -s $test_root/raw-ps-overflow ]] || fail "oversized raw ps bytes were retained"
collector_started=$SECONDS
if PATH="$collector_bin:$PATH" FAKE_COLLECTOR_MODE=slow \
    provenance_capture_processes 1 64 >"$test_root/raw-ps-timeout"; then
  fail "raw ps snapshot escaped its time bound"
fi
((SECONDS - collector_started < 4)) || fail "raw ps timeout exceeded its bound"
if PATH="$collector_bin:$PATH" FAKE_COLLECTOR_MODE=large \
    provenance_gradle_daemon_home 200 2 64 >"$test_root/raw-jcmd-overflow"; then
  fail "oversized raw jcmd output was accepted"
fi
[[ ! -s $test_root/raw-jcmd-overflow ]] || fail "oversized raw jcmd bytes were retained"
PATH="$collector_bin:$PATH" FAKE_COLLECTOR_MODE=selected \
  provenance_gradle_daemon_home 200 2 1024 >"$test_root/selected-daemon-home"
assert_equal "$(cat "$test_root/selected-daemon-home")" /owned/home
if grep -F 'do-not-retain' "$test_root/selected-daemon-home" >/dev/null; then
  fail "unrelated jcmd system property was retained"
fi
pass

timeout_monitor="$test_root/timeout-monitor"
mkdir "$timeout_monitor"
: >"$timeout_monitor/host-observations.tsv"
: >"$timeout_monitor/host-violations.tsv"
: >"$timeout_monitor/provisional.tsv"
printf 'workload\n' >"$timeout_monitor/phase"
provenance_capture_processes() { printf '%s\n' ' 200 1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'; }
provenance_process_start() { printf 'Fri Sep 4 11:00:00 2026\n'; }
provenance_gradle_daemon_state() { /bin/sleep 2; printf 'idle\n'; }
if provenance_monitor_host "$timeout_monitor" 100 "$timeout_monitor/stop" 0 '' '' \
    "$timeout_monitor/phase" 65536 2 "$timeout_monitor/provisional.tsv" '' 1 \
    1024 1024 1; then
  :
fi
assert_contains "$timeout_monitor/host-violations.tsv" host_observation_timeout
pass

fixture="$test_root/full-run-fixture"
mkdir -p "$fixture/tools" "$fixture/fake-bin" "$fixture/build/classes" \
  "$fixture/fake-gradle-home/lib"
cp "$script_dir/tps-test.sh" "$script_dir/tps-provenance.sh" "$fixture/tools/"
printf 'build/\n' >"$fixture/.gitignore"
printf 'original-source\n' >"$fixture/mutable-source.txt"
printf 'fake-gradle-runtime\n' >"$fixture/fake-gradle-home/lib/gradle.jar"
cat >"$fixture/fake-bin/ps" <<'EOF'
#!/usr/bin/env bash
if [[ ${1:-} == -p ]]; then
  [[ ${FAKE_PS_SELF_FAIL:-false} != true ]] || exit 1
  printf 'Fri Sep  4 12:00:00 2026\n'
else
  if [[ -n ${FAKE_PS_SCAN_GATE:-} && ! -e $FAKE_PS_SCAN_GATE/release ]]; then
    if mkdir "$FAKE_PS_SCAN_GATE/claimed" 2>/dev/null; then
      : >"$FAKE_PS_SCAN_GATE/started"
      while [[ ! -e $FAKE_PS_SCAN_GATE/release ]]; do /bin/sleep 0.01; done
    fi
  fi
  [[ ${FAKE_PS_SCAN_FAIL:-false} != true ]] || exit 1
  printf '1 0 Fri Sep  4 00:00:00 2026 launchd\n'
fi
EOF
cat >"$fixture/fake-bin/sed" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/sed "$@"
status=$?
if [[ $status -eq 0 && -n ${FAKE_RELEASE_OWNER_RACE:-} &&
    ${1:-} == -n && ${2:-} == 6p && ${3:-} == "$FAKE_RELEASE_OWNER_RACE/owner" &&
    ! -e ${FAKE_RELEASE_OWNER_RACE}.race-complete ]]; then
  /usr/bin/awk '
    /^terminal_commitment_sha256=/ {
      print "terminal_commitment_sha256=0000000000000000000000000000000000000000000000000000000000000000"
      next
    }
    { print }
  ' "$FAKE_RELEASE_OWNER_RACE/owner" >"$FAKE_RELEASE_OWNER_RACE/owner.replacement"
  /bin/mv -- "$FAKE_RELEASE_OWNER_RACE/owner.replacement" \
    "$FAKE_RELEASE_OWNER_RACE/owner"
  : >"${FAKE_RELEASE_OWNER_RACE}.race-complete"
fi
exit "$status"
EOF
cat >"$fixture/fake-gradle" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'fake Gradle complete log\n'
if [[ ${FAKE_BUILD_FAIL:-false} == true ]]; then
  printf 'fake Gradle declared failure\n' >&2
  exit 17
fi
descriptor=
for argument in "$@"; do
  case $argument in
    -PriverTpsClasspathOutput=*) descriptor=${argument#*=} ;;
  esac
done
[[ -n $descriptor ]]
[[ -f $(dirname -- "$descriptor")/owned-gradle-build.active ]] || {
  printf 'owned build marker missing during exact build subprocess\n' >&2
  exit 19
}
printf 'fresh-class-bytes\n' >"$FAKE_RIVER_ROOT/build/classes/Main.class"
{
  printf 'schema=river-tps-runtime-v1\n'
  printf 'gradle.version=fake-1\n'
  printf 'gradle.home=%s\n' "$FAKE_RIVER_ROOT/fake-gradle-home"
  printf 'gradle.process.pid=%s\n' "$$"
  printf 'java.home=/fake/java\n'
  printf 'java.version=fake-25\n'
  printf 'classpath=%s\n' "$FAKE_RIVER_ROOT/build/classes"
} >"$descriptor"
EOF
cat >"$fixture/fake-java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == -XshowSettings:properties ]]; then
  printf '    java.home = /fake/java\n' >&2
  printf 'fake java version 25\n' >&2
  exit 0
fi
if [[ ${1:-} == -version ]]; then
  printf 'fake java version 25\n' >&2
  exit 0
fi
main=
for argument in "$@"; do
  case $argument in
    io.riverdb.bench.tpcc.TpccServerMain|io.riverdb.bench.tpcc.TpccAcceptanceMain) main=$argument ;;
  esac
done
if [[ $main == io.riverdb.bench.tpcc.TpccServerMain ]]; then
  ready= stop= metrics=
  for argument in "$@"; do
    case $argument in
      --ready-file=*) ready=${argument#*=} ;;
      --stop-file=*) stop=${argument#*=} ;;
      --metrics-file=*) metrics=${argument#*=} ;;
    esac
  done
  printf '54321\n' >"$ready"
  while [[ ! -e $stop ]]; do /bin/sleep 0.02; done
  {
    printf 'server_deadlock_diagnostics_enabled=false\n'
    printf 'server_deadlock_diagnostics_budget_bytes=0\n'
    printf 'server_deadlock_diagnostics_valid=true\n'
    printf 'server_deadlock_diagnostics_status=OK\n'
    printf 'server_performance_capture_enabled=true\n'
    printf 'server_performance_capture_status=OK\n'
    printf 'server_performance_capture_valid=true\n'
    printf 'server_active_transactions_at_capture=0\n'
    printf 'server_active_locks_at_capture=0\n'
    printf 'server_waiting_locks_at_capture=0\n'
    printf 'server_capture_lock_waits_deadlocked=0\n'
  } >"$metrics"
  exit 0
fi
[[ $main == io.riverdb.bench.tpcc.TpccAcceptanceMain ]]
artifact=
for argument in "$@"; do
  case $argument in --artifact=*) artifact=${argument#*=} ;; esac
done
[[ ! -e $(dirname -- "$artifact")/owned-gradle-build.active ]] || {
  printf 'owned build marker leaked into workload\n' >&2
  exit 20
}
if [[ -n ${FAKE_ARTIFACT_COLLISION_PATH:-} ]]; then
  printf 'external-artifact-sentinel\n' >"$FAKE_ARTIFACT_COLLISION_PATH"
fi
if [[ -n ${FAKE_PERSIST_COLLISION_DIR:-} ]]; then
  printf 'external-build-log-sentinel\n' >"$FAKE_PERSIST_COLLISION_DIR/build.log"
fi
if [[ -n ${FAKE_CHECKPOINT_COLLISION_DIR:-} ]]; then
  printf 'external-checkpoint-sentinel\n' >"$FAKE_CHECKPOINT_COLLISION_DIR/checkpoints"
fi
if [[ -n ${FAKE_METADATA_COLLISION_PATH:-} ]]; then
  printf 'external-metadata-sentinel\n' >"$FAKE_METADATA_COLLISION_PATH"
fi
if [[ -n ${FAKE_TERMINAL_COLLISION_PATH:-} ]]; then
  printf 'external-terminal-sentinel\n' >"$FAKE_TERMINAL_COLLISION_PATH"
fi
if [[ ${FAKE_LEDGER_FAILURE:-false} == true ]]; then
  ledger="$(dirname -- "$artifact")/provenance-checkpoints.tsv"
  rm -f -- "$ledger"
  mkdir "$ledger"
fi
if [[ -n ${FAKE_CORRUPT_LEASE_OWNER:-} ]]; then
  printf 'unexpected=field\n' >>"$FAKE_CORRUPT_LEASE_OWNER/owner"
fi
if [[ -n ${FAKE_MUTATE_LEASE_COMMITMENT:-} ]]; then
  awk '
    /^terminal_commitment_sha256=/ {
      print "terminal_commitment_sha256=0000000000000000000000000000000000000000000000000000000000000000"
      next
    }
    { print }
  ' "$FAKE_MUTATE_LEASE_COMMITMENT/owner" >"$FAKE_MUTATE_LEASE_COMMITMENT/owner.mutated"
  mv -- "$FAKE_MUTATE_LEASE_COMMITMENT/owner.mutated" \
    "$FAKE_MUTATE_LEASE_COMMITMENT/owner"
fi
if [[ -n ${FAKE_CLIENT_STARTED:-} ]]; then : >"$FAKE_CLIENT_STARTED"; fi
if [[ -n ${FAKE_CLIENT_DELAY:-} ]]; then /bin/sleep "$FAKE_CLIENT_DELAY"; fi
if [[ -n ${FAKE_MUTATE_SOURCE:-} ]]; then
  printf 'mutated-source\n' >"$FAKE_MUTATE_SOURCE"
fi
if [[ -n ${FAKE_MUTATE_CLASSPATH:-} ]]; then
  printf 'mutated-runtime\n' >"$FAKE_MUTATE_CLASSPATH"
fi
{
  printf 'run.id=fake-run\n'
  printf 'database.digest.sha256=fake-digest\n'
} >"$artifact"
for phase in load preflight warmup measured drain checkpoint; do
  printf 'phase_start=%s\n' "$phase"
  printf 'phase_complete=%s\n' "$phase"
done
printf 'whole_transaction_retries=0\n'
printf 'transaction_attempts=1\n'
printf 'completed_transactions=1\n'
printf 'in_flight_at_cutoff=0\n'
printf 'transaction=new-order committed=1 retry_exhausted=0 failed=0\n'
EOF
chmod +x "$fixture/fake-bin/ps" "$fixture/fake-bin/sed" \
  "$fixture/fake-gradle" "$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" "$fixture/tools/tps-provenance.sh"
git -C "$fixture" init -q
git -C "$fixture" config user.name test
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" add .
git -C "$fixture" commit -qm fixture

set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" --output-dir="$test_root/missing-attestation-output" \
  >"$test_root/missing-attestation.stdout" 2>"$test_root/missing-attestation.stderr"
missing_attestation_status=$?
set -e
assert_equal "$missing_attestation_status" 2
assert_contains "$test_root/missing-attestation.stderr" \
  'RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true is required'
pass

lease_identity_output="$test_root/lease-identity-output"
lease_identity_tmp="$test_root/lease-identity-tmp"
mkdir "$lease_identity_tmp"
set +e
PATH="$fixture/fake-bin:$PATH" TMPDIR="$lease_identity_tmp" \
  FAKE_RIVER_ROOT="$fixture" FAKE_PS_SELF_FAIL=true \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/lease-identity-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$lease_identity_output" \
  >"$test_root/lease-identity.stdout" 2>"$test_root/lease-identity.stderr"
lease_identity_status=$?
set -e
assert_equal "$lease_identity_status" 2
assert_contains "$lease_identity_output/run-metadata.properties" \
  'run.result=provisional'
assert_contains "$lease_identity_output/run-metadata.properties" \
  'run.provisional_status=HOST_LEASE_ACQUISITION_FAILED'
assert_contains "$lease_identity_output/run-metadata.properties" \
  'provenance.host_exclusion_valid=false'
assert_contains "$lease_identity_output/host-violations.tsv" \
  'reason=owner_identity_unavailable'
assert_contains "$lease_identity_output/evidence-invalid.status" \
  'status=HOST_LEASE_ACQUISITION_FAILED'
[[ -z $(find "$lease_identity_tmp" -mindepth 1 -maxdepth 1 \
    -type d -name 'river-tps-test.*' -print -quit) && \
  ! -e $test_root/lease-identity-lease ]] ||
  fail "lease identity failure did not clean persisted temp state"
pass

slow_monitor_output="$test_root/slow-monitor-output"
slow_monitor_gate="$test_root/slow-monitor-gate"
mkdir "$slow_monitor_gate"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_PS_SCAN_GATE="$slow_monitor_gate" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/slow-monitor-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$slow_monitor_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/slow-monitor.stdout" 2>"$test_root/slow-monitor.stderr" &
slow_monitor_pid=$!
for _ in {1..200}; do
  [[ -e $slow_monitor_gate/started ]] && break
  /bin/sleep 0.01
done
[[ -e $slow_monitor_gate/started ]] || fail "slow initial host observation did not start"
/bin/sleep 5.5
kill -0 "$slow_monitor_pid" 2>/dev/null ||
  fail "slow initial host observation hit the former five-second readiness race"
: >"$slow_monitor_gate/release"
wait "$slow_monitor_pid" || fail "slow initial host observation was rejected"
assert_contains "$slow_monitor_output/run-metadata.properties" 'run.result=provisional'
assert_contains "$slow_monitor_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=success'
assert_contains "$slow_monitor_output/run-metadata.properties" \
  'provenance.host_exclusion_valid=true'
pass

monitor_start_output="$test_root/monitor-start-output"
monitor_start_tmp="$test_root/monitor-start-tmp"
mkdir "$monitor_start_tmp"
set +e
PATH="$fixture/fake-bin:$PATH" TMPDIR="$monitor_start_tmp" \
  FAKE_RIVER_ROOT="$fixture" \
  FAKE_PS_SCAN_FAIL=true \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/monitor-start-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$monitor_start_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/monitor-start.stdout" 2>"$test_root/monitor-start.stderr"
monitor_start_status=$?
set -e
assert_equal "$monitor_start_status" 2
assert_contains "$monitor_start_output/run-metadata.properties" \
  'run.result=provisional'
assert_contains "$monitor_start_output/run-metadata.properties" \
  'run.provisional_status=HOST_MONITOR_START_FAILED'
assert_contains "$monitor_start_output/run-metadata.properties" \
  'provenance.host_exclusion_valid=false'
assert_contains "$monitor_start_output/host-violations.tsv" \
  $'violation\thost_monitor_start_failed'
assert_contains "$monitor_start_output/host-violations.tsv" \
  'reason=initial_observation_failed'
assert_contains "$monitor_start_output/evidence-invalid.status" \
  'status=HOST_MONITOR_START_FAILED'
assert_contains "$monitor_start_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=evidence_invalid'
[[ ! -e $monitor_start_output/tpcc-acceptance.properties ]] ||
  fail "monitor startup failure published an acceptance artifact"
[[ -z $(find "$monitor_start_tmp" -mindepth 1 -maxdepth 1 \
    -type d -name 'river-tps-test.*' -print -quit) && \
  ! -e $test_root/monitor-start-lease ]] ||
  fail "monitor startup failure did not clean the owned temp tree and lease"
pass

printf 'stale-class-bytes\n' >"$fixture/build/classes/Main.class"
full_output="$test_root/full-output"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/full-lease" \
  RIVER_TPS_GRADLE_USER_HOME="$test_root/full-gradle-home" \
  RIVER_TPS_PROJECT_CACHE_DIR="$test_root/full-project-cache" \
  "$fixture/tools/tps-test.sh" --output-dir="$full_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null
assert_contains "$fixture/build/classes/Main.class" fresh-class-bytes
assert_contains "$full_output/run-metadata.properties" 'run.result=provisional'
assert_contains "$full_output/run-metadata.properties" 'run.status=TERMINAL_RECEIPT_REQUIRED'
assert_contains "$full_output/run-metadata.properties.terminal-receipt" 'terminal.result=success'
assert_contains "$full_output/run-metadata.properties" 'build.exit_status=0'
gradle_manifest_hash=$(sed -n 's/^build.gradle_runtime_manifest_sha256=//p' \
  "$full_output/run-metadata.properties")
assert_equal "$(provenance_sha256_file "$full_output/gradle-runtime-manifest.tsv")" \
  "$gradle_manifest_hash"
provisional_hash=$(sed -n 's/^host.provisional_daemons_sha256=//p' \
  "$full_output/run-metadata.properties.terminal-receipt")
assert_equal "$(provenance_sha256_file "$full_output/host-provisional-daemons.tsv")" \
  "$provisional_hash"
assert_contains "$full_output/run-metadata.properties" 'provenance.source_stable=true'
assert_contains "$full_output/run-metadata.properties" 'provenance.classpath_stable=true'
assert_contains "$full_output/run-metadata.properties" 'provenance.host_exclusion_valid=true'
assert_contains "$full_output/run-metadata.properties" \
  'provenance.host_provisional_daemons_sha256='
assert_contains "$full_output/run-metadata.properties" 'provenance.publication_valid=true'
assert_contains "$full_output/run-metadata.properties" \
  'configuration.operator_no_uncoordinated_work_attestation=true'
provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
  "$full_output/tpcc-acceptance.properties" \
  "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success ||
  fail "shared validator rejected a complete terminal success receipt"
cp "$full_output/run-metadata.properties.terminal-receipt" "$test_root/terminal.saved"
printf 'unexpected=duplicate\n' >>"$full_output/run-metadata.properties.terminal-receipt"
if provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
    "$full_output/tpcc-acceptance.properties" \
    "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success; then
  fail "noncanonical terminal receipt was accepted"
fi
mv -- "$test_root/terminal.saved" "$full_output/run-metadata.properties.terminal-receipt"
cp "$full_output/run-metadata.properties" "$test_root/metadata.saved"
replace_property "$full_output/run-metadata.properties" evidence.run_id "$(printf '%064d' 0)"
if provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
    "$full_output/tpcc-acceptance.properties" \
    "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success; then
  fail "metadata mutation after terminal publication was accepted"
fi
mv -- "$test_root/metadata.saved" "$full_output/run-metadata.properties"
mv -- "$full_output/run-metadata.properties.terminal-receipt" "$test_root/terminal.saved"
if provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
    "$full_output/tpcc-acceptance.properties" \
    "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success; then
  fail "missing terminal receipt was accepted"
fi
mv -- "$test_root/terminal.saved" "$full_output/run-metadata.properties.terminal-receipt"
full_temp_dir=$(retained_temp_dir "$full_output")
[[ ! -e $full_temp_dir ]] || fail "successful persisted run retained owned temporary database tree"

provenance_write_classpath_manifest "$full_output/runtime-classpath.properties" \
  "$test_root/full-replayed-classpath.tsv"
cmp -s "$test_root/full-replayed-classpath.tsv" "$full_output/classpath-manifest.tsv" ||
  fail "retained classpath manifest cannot be replayed"
while IFS=$'\t' read -r stage _ source_hash status_hash classpath_hash descriptor_hash; do
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/source-manifest.$stage.tsv")" "$source_hash"
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/git-status.$stage.txt")" "$status_hash"
  if [[ $classpath_hash != unavailable ]]; then
    assert_equal "$(provenance_sha256_file "$full_output/checkpoints/classpath-manifest.$stage.tsv")" "$classpath_hash"
    assert_equal "$(provenance_sha256_file "$full_output/runtime-classpath.properties")" "$descriptor_hash"
  fi
done <"$full_output/provenance-checkpoints.tsv"
metadata_status_hash=$(sed -n 's/^git.status_sha256=//p' "$full_output/run-metadata.properties")
assert_equal "$(provenance_sha256_file "$full_output/checkpoints/git-status.metadata.txt")" "$metadata_status_hash"
publication_hash_before=$(provenance_sha256_file "$full_output/run-metadata.properties")
if PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
    RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
    RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
    "$fixture/tools/tps-test.sh" --output-dir="$full_output" >/dev/null 2>&1; then
  fail "full-run evidence directory was overwritten"
fi
assert_equal "$(provenance_sha256_file "$full_output/run-metadata.properties")" "$publication_hash_before"
pass

explicit_output="$test_root/explicit-output"
explicit_metadata="$test_root/explicit-metadata.properties"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/explicit-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$explicit_output" \
  --metadata="$explicit_metadata" --warmup-seconds=1 --measured-seconds=1 \
  --terminals=1 >/dev/null
[[ -f $explicit_metadata.terminal-receipt ]] ||
  fail "explicit metadata path did not receive an adjacent terminal receipt"
provenance_validate_terminal_receipt "$explicit_metadata" \
  "$explicit_output/tpcc-acceptance.properties" "$explicit_metadata.terminal-receipt" \
  "$explicit_output" success || fail "explicit metadata terminal receipt was invalid"
pass

source_mutation_output="$test_root/source-mutation-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_MUTATE_SOURCE="$fixture/mutable-source.txt" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/source-mutation-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$source_mutation_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
source_mutation_status=$?
set -e
assert_equal "$source_mutation_status" 1
assert_contains "$source_mutation_output/run-metadata.properties" 'provenance.source_stable=false'
cmp -s "$source_mutation_output/checkpoints/source-manifest.start.tsv" \
  "$source_mutation_output/checkpoints/source-manifest.metadata.tsv" &&
  fail "invalid final source bytes were not retained"
source_final_hash=$(sed -n 's/^git.workspace_finish_sha256=//p' \
  "$source_mutation_output/run-metadata.properties")
assert_equal "$(provenance_sha256_file "$source_mutation_output/checkpoints/source-manifest.metadata.tsv")" \
  "$source_final_hash"
source_mutation_temp=$(retained_temp_dir "$source_mutation_output")
[[ ! -e $source_mutation_temp ]] ||
  fail "invalid but successfully persisted source run retained temporary tree"
printf 'original-source\n' >"$fixture/mutable-source.txt"
pass

classpath_mutation_output="$test_root/classpath-mutation-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_MUTATE_CLASSPATH="$fixture/build/classes/Main.class" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/classpath-mutation-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$classpath_mutation_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
classpath_mutation_status=$?
set -e
assert_equal "$classpath_mutation_status" 1
assert_contains "$classpath_mutation_output/run-metadata.properties" 'provenance.classpath_stable=false'
cmp -s "$classpath_mutation_output/checkpoints/classpath-manifest.start.tsv" \
  "$classpath_mutation_output/checkpoints/classpath-manifest.metadata.tsv" &&
  fail "invalid final classpath bytes were not retained"
classpath_mutation_temp=$(retained_temp_dir "$classpath_mutation_output")
[[ ! -e $classpath_mutation_temp ]] ||
  fail "invalid but successfully persisted classpath run retained temporary tree"
pass

failed_output="$test_root/failed-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" FAKE_BUILD_FAIL=true \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/failed-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$failed_output" >/dev/null 2>&1
failed_status=$?
set -e
assert_equal "$failed_status" 17
assert_contains "$failed_output/build.log" 'fake Gradle declared failure'
assert_contains "$failed_output/run-metadata.properties" 'run.provisional_result=build_failed'
assert_contains "$failed_output/run-metadata.properties" 'build.exit_status=17'
failed_temp_dir=$(retained_temp_dir "$failed_output")
[[ ! -e $failed_temp_dir ]] || fail "failed persisted run retained owned temporary database tree"
pass

interrupted_output="$test_root/interrupted-output"
client_started="$test_root/client-started"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CLIENT_STARTED="$client_started" FAKE_CLIENT_DELAY=30 \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/interrupted-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$interrupted_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1 &
interrupted_pid=$!
for _ in {1..100}; do
  [[ -e $client_started ]] && break
  /bin/sleep 0.05
done
[[ -e $client_started ]] || fail "interruption fixture did not reach the client"
kill -TERM "$interrupted_pid"
set +e
wait "$interrupted_pid"
interrupted_status=$?
set -e
assert_equal "$interrupted_status" 143
assert_contains "$interrupted_output/run-metadata.properties" 'run.provisional_result=interrupted'
assert_contains "$interrupted_output/run-metadata.properties" 'run.provisional_status=INTERRUPTED'
provenance_validate_terminal_receipt "$interrupted_output/run-metadata.properties" \
  "$interrupted_output/tpcc-acceptance.properties" \
  "$interrupted_output/run-metadata.properties.terminal-receipt" \
  "$interrupted_output" evidence_invalid ||
  fail "interruption did not publish a valid terminal failure receipt"
[[ -f $interrupted_output/build.log && -f $interrupted_output/provenance-checkpoints.tsv ]] ||
  fail "interrupted evidence was not preserved"
interrupted_temp_dir=$(retained_temp_dir "$interrupted_output")
[[ ! -e $interrupted_temp_dir ]] || fail "interrupted persisted run retained owned temporary database tree"
pass

terminal_collision_output="$test_root/terminal-collision-output"
terminal_collision_path="$terminal_collision_output/run-metadata.properties.terminal-receipt"
mkdir "$terminal_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_TERMINAL_COLLISION_PATH="$terminal_collision_path" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/terminal-collision-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$terminal_collision_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/terminal-collision.stdout" 2>"$test_root/terminal-collision.stderr"
terminal_collision_status=$?
set -e
assert_equal "$terminal_collision_status" 1
assert_contains "$terminal_collision_path" external-terminal-sentinel
assert_contains "$terminal_collision_output/evidence-invalid.status" \
  'status=TERMINAL_RECEIPT_PUBLICATION_FAILED'
assert_contains "$test_root/terminal-collision.stderr" \
  'reason=terminal_receipt_publication_failed'
if provenance_validate_terminal_receipt \
    "$terminal_collision_output/run-metadata.properties" \
    "$terminal_collision_output/tpcc-acceptance.properties" \
    "$terminal_collision_path" "$terminal_collision_output" success; then
  fail "terminal publication collision was accepted as successful evidence"
fi
pass

artifact_collision_output="$test_root/artifact-collision-output"
mkdir "$artifact_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_ARTIFACT_COLLISION_PATH="$artifact_collision_output/tpcc-acceptance.properties" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/artifact-collision-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$artifact_collision_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/artifact-collision.stdout" 2>"$test_root/artifact-collision.stderr"
artifact_collision_status=$?
set -e
assert_equal "$artifact_collision_status" 1
assert_contains "$artifact_collision_output/tpcc-acceptance.properties" \
  external-artifact-sentinel
assert_contains "$artifact_collision_output/run-metadata.properties" \
  'run.provisional_result=evidence_invalid'
assert_contains "$artifact_collision_output/run-metadata.properties" \
  'provenance.publication_valid=false'
assert_contains "$artifact_collision_output/run-metadata.properties" \
  'artifact.published=false'
pass

persist_collision_output="$test_root/persist-collision-output"
mkdir "$persist_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_PERSIST_COLLISION_DIR="$persist_collision_output" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/persist-collision-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$persist_collision_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
persist_collision_status=$?
set -e
assert_equal "$persist_collision_status" 1
assert_contains "$persist_collision_output/build.log" external-build-log-sentinel
assert_contains "$persist_collision_output/run-metadata.properties" \
  'run.provisional_status=EVIDENCE_PUBLICATION_FAILED'
assert_contains "$persist_collision_output/run-metadata.properties" \
  'provenance.publication_valid=false'
pass

checkpoint_collision_output="$test_root/checkpoint-collision-output"
mkdir "$checkpoint_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CHECKPOINT_COLLISION_DIR="$checkpoint_collision_output" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/checkpoint-collision-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$checkpoint_collision_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
checkpoint_collision_status=$?
set -e
assert_equal "$checkpoint_collision_status" 1
assert_contains "$checkpoint_collision_output/checkpoints" external-checkpoint-sentinel
assert_contains "$checkpoint_collision_output/run-metadata.properties" \
  'run.provisional_result=completed'
assert_contains "$checkpoint_collision_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=evidence_invalid'
pass

ledger_failure_output="$test_root/ledger-failure-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" FAKE_LEDGER_FAILURE=true \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/ledger-failure-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$ledger_failure_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
ledger_failure_status=$?
set -e
assert_equal "$ledger_failure_status" 1
assert_contains "$ledger_failure_output/run-metadata.properties" \
  'run.provisional_result=evidence_invalid'
assert_contains "$ledger_failure_output/run-metadata.properties" \
  'provenance.publication_valid=false'
pass

metadata_collision_output="$test_root/metadata-collision-output"
mkdir "$metadata_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_METADATA_COLLISION_PATH="$metadata_collision_output/run-metadata.properties" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/metadata-collision-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$metadata_collision_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/metadata-collision.stdout" 2>"$test_root/metadata-collision.stderr"
metadata_collision_status=$?
set -e
assert_equal "$metadata_collision_status" 1
assert_contains "$metadata_collision_output/run-metadata.properties" \
  external-metadata-sentinel
assert_contains "$test_root/metadata-collision.stderr" \
  'evidence_status=evidence_invalid reason=publication_failed'
metadata_collision_temp=$(retained_temp_dir "$metadata_collision_output")
assert_contains "$metadata_collision_temp/evidence-invalid.status" \
  'status=EVIDENCE_PUBLICATION_FAILED'
assert_contains "$metadata_collision_output/evidence-invalid.status" \
  'result=evidence_invalid'
pass

lease_release_output="$test_root/lease-release-output"
lease_release_dir="$test_root/lease-release-lease"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CORRUPT_LEASE_OWNER="$lease_release_dir" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$lease_release_dir" \
  "$fixture/tools/tps-test.sh" --output-dir="$lease_release_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/lease-release.stdout" 2>"$test_root/lease-release.stderr"
lease_release_status=$?
set -e
assert_equal "$lease_release_status" 1
assert_contains "$lease_release_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=evidence_invalid'
assert_contains "$lease_release_output/run-metadata.properties.terminal-receipt" \
  'terminal.status=LEASE_RELEASE_FAILED'
assert_contains "$lease_release_output/run-metadata.properties.terminal-receipt" \
  'lease.release_outcome=failed'
assert_contains "$test_root/lease-release.stderr" \
  'evidence_status=evidence_invalid reason=lease_release_failed'
lease_release_temp=$(retained_temp_dir "$lease_release_output")
[[ ! -d $lease_release_temp && -d $lease_release_dir ]] ||
  fail "lease-release failure did not preserve the fail-closed lease after evidence persistence"
rm -rf -- "$lease_release_dir"
pass

commitment_release_output="$test_root/commitment-release-output"
commitment_release_dir="$test_root/commitment-release-lease"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_MUTATE_LEASE_COMMITMENT="$commitment_release_dir" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$commitment_release_dir" \
  "$fixture/tools/tps-test.sh" --output-dir="$commitment_release_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/commitment-release.stdout" 2>"$test_root/commitment-release.stderr"
commitment_release_status=$?
set -e
assert_equal "$commitment_release_status" 1
[[ -d $commitment_release_dir && -f $commitment_release_dir/owner ]] ||
  fail "commitment-only release mismatch deleted the fail-closed lease"
assert_contains "$commitment_release_dir/owner" \
  'terminal_commitment_sha256=0000000000000000000000000000000000000000000000000000000000000000'
metadata_commitment=$(provenance_property_once terminal.commitment_sha256 \
  "$commitment_release_output/run-metadata.properties")
[[ $metadata_commitment != \
    0000000000000000000000000000000000000000000000000000000000000000 ]] ||
  fail "fixture did not isolate a commitment-only owner mutation"
assert_contains "$commitment_release_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=evidence_invalid'
assert_contains "$commitment_release_output/run-metadata.properties.terminal-receipt" \
  'terminal.status=LEASE_RELEASE_FAILED'
assert_contains "$commitment_release_output/run-metadata.properties.terminal-receipt" \
  'lease.release_outcome=failed'
provenance_validate_terminal_receipt \
  "$commitment_release_output/run-metadata.properties" \
  "$commitment_release_output/tpcc-acceptance.properties" \
  "$commitment_release_output/run-metadata.properties.terminal-receipt" \
  "$commitment_release_output" evidence_invalid ||
  fail "commitment-only mismatch did not produce a bound failure receipt"
rm -rf -- "$commitment_release_dir"
pass

release_race_output="$test_root/release-race-output"
release_race_dir="$test_root/release-race-lease"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_RELEASE_OWNER_RACE="$release_race_dir" \
  RIVER_TPS_OPERATOR_NO_UNCOORDINATED_WORK_ATTESTATION=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$release_race_dir" \
  "$fixture/tools/tps-test.sh" --output-dir="$release_race_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 \
  >"$test_root/release-race.stdout" 2>"$test_root/release-race.stderr"
release_race_status=$?
set -e
assert_equal "$release_race_status" 1
[[ -d $release_race_dir && -f $release_race_dir/owner &&
    -f ${release_race_dir}.race-complete ]] ||
  fail "post-parse owner replacement was not preserved with the lease"
assert_contains "$release_race_dir/owner" \
  'terminal_commitment_sha256=0000000000000000000000000000000000000000000000000000000000000000'
provenance_read_lease_owner "$release_race_dir/owner" >/dev/null ||
  fail "post-parse replacement was not a canonical owner record"
assert_contains "$release_race_output/run-metadata.properties.terminal-receipt" \
  'terminal.result=evidence_invalid'
assert_contains "$release_race_output/run-metadata.properties.terminal-receipt" \
  'terminal.status=LEASE_RELEASE_FAILED'
assert_contains "$release_race_output/run-metadata.properties.terminal-receipt" \
  'lease.release_outcome=failed'
provenance_validate_terminal_receipt \
  "$release_race_output/run-metadata.properties" \
  "$release_race_output/tpcc-acceptance.properties" \
  "$release_race_output/run-metadata.properties.terminal-receipt" \
  "$release_race_output" evidence_invalid ||
  fail "post-parse replacement did not produce a bound failure receipt"
rm -rf -- "$release_race_dir"
rm -- "${release_race_dir}.race-complete"
pass

grep -F 'writeRiverTpsRuntimeClasspath' "$script_dir/../river-bench/build.gradle.kts" >/dev/null ||
  fail "Gradle-owned classpath provider is missing"
if grep -F 'RIVER_TPS_SKIP_BUILD' "$script_dir/tps-test.sh" >/dev/null; then
  fail "stale-class build bypass remains"
fi
assert_contains "$script_dir/tps-test.sh" "trap 'run_result=interrupted"
assert_contains "$script_dir/tps-test.sh" 'build_status=${PROVENANCE_LOGGED_COMMAND_STATUS:-125}'
assert_contains "$script_dir/tps-test.sh" 'build_wrapper_status=$?'
pass

echo "PASS: $tests provenance boundary tests"
