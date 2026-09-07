#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/tps-provenance.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-provenance-test.XXXXXX")
test_make_pid=
race_a_pid=
race_b_pid=
finish_test() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n $test_make_pid ]]; then
    kill -TERM "$test_make_pid" 2>/dev/null
    wait "$test_make_pid" 2>/dev/null
  fi
  for race_pid in "$race_a_pid" "$race_b_pid"; do
    [[ -n $race_pid ]] || continue
    kill -TERM "$race_pid" 2>/dev/null
    wait "$race_pid" 2>/dev/null
  done
  if ((status == 0)); then
    rm -rf -- "$test_root" || status=1
  else
    echo "failed_fixture=$test_root" >&2
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

classpath_root="$test_root/classpath"
mkdir -p "$classpath_root/classes/a" "$classpath_root/resources"
printf 'class-a\n' >"$classpath_root/classes/a/A.class"
printf 'resource\n' >"$classpath_root/resources/example.txt"
printf 'jar\n' >"$classpath_root/dependency.jar"
descriptor="$test_root/runtime.properties"
{
  printf 'schema=river-tps-runtime-v3\n'
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
  800    1 Fri Sep  4 12:00:07 2026 /usr/bin/rg GradleWrapperMain tools
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
if grep -F $'\t800' "$test_root/classification.tsv" >/dev/null; then
  fail "source inspection command was classified as Gradle activity"
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

inventory="$test_root/inventory"
mkdir "$inventory"
fake_gradle_status="$test_root/fake-gradle-status"
cat >"$fake_gradle_status" <<'EOF'
#!/usr/bin/env bash
cat <<'STATUS'
    PID STATUS   INFO
  200 IDLE     9.7.0

Only Daemons for the current Gradle version are displayed. For more on this, please refer to https://docs.gradle.org/9.7.0/userguide/gradle_daemon.html#sec:status in the Gradle documentation.
STATUS
EOF
chmod +x "$fake_gradle_status"
provenance_capture_processes() {
  printf '%s\n' ' 200    1 Fri Sep  4 11:00:00 2026 /usr/bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'
}
provenance_process_start() { printf 'Fri Sep 4 11:00:00 2026\n'; }
provenance_inventory_boundary "$inventory" 100 pre-source "$fake_gradle_status" \
  /owned/home 65536 2 false || fail "idle Gradle status inventory failed"
assert_contains "$inventory/host-classifications.tsv" $'allowed_idle_gradle_daemon\t200'
if provenance_gradle_status "$test_root/missing-status" /owned/home 1 1024; then
  fail "missing Gradle status query was accepted"
fi
pass

cold_gradle_status="$test_root/cold-gradle-status"
cat >"$cold_gradle_status" <<'EOF'
#!/usr/bin/env bash
cat <<'STATUS'
    PID STATUS   INFO
No Gradle daemons are running.

Only Daemons for the current Gradle version are displayed. For more on this, please refer to https://docs.gradle.org/9.7.0/userguide/gradle_daemon.html#sec:status in the Gradle documentation.
STATUS
EOF
chmod +x "$cold_gradle_status"
provenance_capture_processes() {
  printf '%s\n' ' 1    0 Mon Sep  7 00:00:00 2026 launchd'
}
cold_inventory="$test_root/cold-inventory"
mkdir "$cold_inventory"
if ! provenance_inventory_boundary "$cold_inventory" 100 pre-source \
    "$cold_gradle_status" /owned/home 65536 2 false; then
  fail "cold no-daemon status was rejected"
fi
assert_contains "$cold_inventory/host-classifications.tsv" $'clean\t-'

stopped_gradle_status="$test_root/stopped-gradle-status"
cat >"$stopped_gradle_status" <<'EOF'
#!/usr/bin/env bash
cat <<'STATUS'
    PID STATUS   INFO
  200 STOPPED  (by user or OS)

Only Daemons for the current Gradle version are displayed. For more on this, please refer to https://docs.gradle.org/9.7.0/userguide/gradle_daemon.html#sec:status in the Gradle documentation.
STATUS
EOF
chmod +x "$stopped_gradle_status"
provenance_capture_processes() {
  printf '%s\n' \
    ' 200    1 Mon Sep  7 00:00:00 2026 /usr/bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.7'
}
provenance_process_start() { printf 'Mon Sep 7 00:00:00 2026\n'; }
stopped_inventory="$test_root/stopped-inventory"
mkdir "$stopped_inventory"
if provenance_inventory_boundary "$stopped_inventory" 100 pre-source \
    "$stopped_gradle_status" /owned/home 65536 2 false; then
  fail "observed daemon with stopped history was accepted"
fi
assert_contains "$stopped_inventory/host-violations.tsv" \
  uninspectable_gradle_daemon

empty_process_inventory="$test_root/empty-process-inventory"
mkdir "$empty_process_inventory"
provenance_capture_processes() { :; }
if provenance_inventory_boundary "$empty_process_inventory" 100 pre-source \
    "$fake_gradle_status" /owned/home 65536 2 false; then
  fail "empty process capture was accepted"
fi
assert_contains "$empty_process_inventory/host-violations.tsv" \
  process_inventory_unavailable

malformed_process_inventory="$test_root/malformed-process-inventory"
mkdir "$malformed_process_inventory"
provenance_capture_processes() { printf '%s\n' 'malformed process row'; }
if provenance_inventory_boundary "$malformed_process_inventory" 100 pre-source \
    "$fake_gradle_status" /owned/home 65536 2 false; then
  fail "malformed process capture was accepted"
fi
assert_contains "$malformed_process_inventory/host-violations.tsv" \
  process_inventory_unavailable
provenance_capture_processes() {
  printf '%s\n' \
    ' 200    1 Fri Sep  4 11:00:00 2026 /usr/bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'
}
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

replacement_race_lease="$test_root/replacement-race-lease"
replacement_race_gate="$test_root/replacement-race-gate"
replacement_race_results="$test_root/replacement-race-results"
replacement_race_a_run=$(printf '%064d' 31)
replacement_race_b_run=$(printf '%064d' 32)
mkdir "$replacement_race_gate"
: >"$replacement_race_results"
bash -c '
  set -euo pipefail
  source "$1"
  lease=$2
  gate=$3
  run_id=$4
  nonce=$5
  provenance_process_start() { printf "A-start\n"; }
  rm() {
    local owner_unlink=false argument
    for argument in "$@"; do
      [[ $argument == ./owner || $argument == "$lease/owner" ]] && owner_unlink=true
    done
    if [[ $owner_unlink == true && ! -e $gate/a-unlink-entered ]]; then
      : >"$gate/a-unlink-entered"
      while [[ ! -e $gate/a-unlink-release ]]; do /bin/sleep 0.01; done
    fi
    command rm "$@"
  }
  printf "%s\n" "${BASHPID:-$$}" >"$gate/a-pid"
  provenance_acquire_lease "$lease" "$run_id" "$nonce"
  if provenance_release_lease "$lease"; then
    printf "a-success\n" >>"$6"
  else
    printf "a-failed\n" >>"$6"
  fi
' bash "$script_dir/tps-provenance.sh" "$replacement_race_lease" \
  "$replacement_race_gate" "$replacement_race_a_run" "$lease_nonce" \
  "$replacement_race_results" &
race_a_pid=$!
for _ in {1..200}; do
  [[ -s $replacement_race_gate/a-pid && -e $replacement_race_gate/a-unlink-entered ]] && break
  /bin/sleep 0.01
done
[[ -e $replacement_race_gate/a-unlink-entered ]] || fail "candidate A did not pause before owner unlink"
a_pid=$(cat "$replacement_race_gate/a-pid")
bash -c '
  set -euo pipefail
  source "$1"
  lease=$2
  gate=$3
  run_id=$4
  nonce=$5
  old_pid=$6
  provenance_process_start() {
    if [[ $1 == "$old_pid" ]]; then return 1; fi
    printf "B-start\n"
  }
  kill() {
    if [[ ${1:-} == -0 && ${2:-} == "$old_pid" ]]; then return 1; fi
    command kill "$@"
  }
  provenance_acquire_lease "$lease" "$run_id" "$nonce"
  printf "%s\n" "${BASHPID:-$$}" >"$gate/b-pid"
  : >"$gate/b-acquired"
  while [[ ! -e $gate/b-release ]]; do /bin/sleep 0.01; done
  provenance_release_lease "$lease"
' bash "$script_dir/tps-provenance.sh" "$replacement_race_lease" \
  "$replacement_race_gate" "$replacement_race_b_run" "$lease_nonce" \
  "$a_pid" &
race_b_pid=$!
for _ in {1..200}; do
  [[ -e $replacement_race_gate/b-acquired ]] && break
  /bin/sleep 0.01
done
[[ -e $replacement_race_gate/b-acquired ]] || fail "proper contender B did not publish replacement"
: >"$replacement_race_gate/a-unlink-release"
for _ in {1..200}; do
  grep -F a-failed "$replacement_race_results" >/dev/null && break
  /bin/sleep 0.01
done
grep -F a-failed "$replacement_race_results" >/dev/null ||
  fail "candidate A did not fail after replacement"
[[ -f $replacement_race_lease/owner && -d $replacement_race_lease ]] ||
  fail "candidate A removed replacement lease directory"
assert_contains "$replacement_race_lease/owner" "evidence_run_id=$replacement_race_b_run"
: >"$replacement_race_gate/b-release"
wait "$race_a_pid"
wait "$race_b_pid"
race_a_pid=
race_b_pid=
pass

non_owner_lease="$test_root/non-owner-lease"
non_owner_run=$(printf '%064d' 41)
non_owner_nonce=$(printf '%064d' 42)
provenance_acquire_lease "$non_owner_lease" "$non_owner_run" "$non_owner_nonce" ||
  fail "non-owner release fixture could not acquire"
saved_owner_identity=$PROVENANCE_LEASE_OWNER_IDENTITY_SHA256
PROVENANCE_LEASE_OWNER_IDENTITY_SHA256=$(printf '%064d' 0)
if provenance_release_lease "$non_owner_lease"; then
  fail "non-owner release was accepted"
fi
[[ -f $non_owner_lease/owner && -d $non_owner_lease ]] ||
  fail "non-owner release modified the lease"
PROVENANCE_LEASE_OWNER_IDENTITY_SHA256=$saved_owner_identity
provenance_release_lease "$non_owner_lease" || fail "owned release after refusal failed"
pass

inherited_live_owner_lease="$test_root/inherited-live-owner-lease"
inherited_live_owner_run=$(printf '%064d' 51)
inherited_live_owner_nonce=$(printf '%064d' 52)
provenance_acquire_lease "$inherited_live_owner_lease" \
  "$inherited_live_owner_run" "$inherited_live_owner_nonce" ||
  fail "inherited live-owner fixture could not acquire"
if bash -c '
  set -euo pipefail
  source "$1"
  unset PROVENANCE_LEASE_RUN_ID PROVENANCE_LEASE_NONCE \
    PROVENANCE_LEASE_OWNER_PID PROVENANCE_LEASE_OWNER_START \
    PROVENANCE_LEASE_OWNER_IDENTITY_SHA256 PROVENANCE_TERMINAL_COMMITMENT_SHA256
  provenance_release_lease "$2"
' bash "$script_dir/tps-provenance.sh" "$inherited_live_owner_lease"; then
  fail "inherited live-owner release without binding was accepted"
fi
[[ -f $inherited_live_owner_lease/owner && -d $inherited_live_owner_lease ]] ||
  fail "inherited live-owner release modified the lease"
provenance_release_lease "$inherited_live_owner_lease" ||
  fail "owned release after inherited refusal failed"
pass

incomplete_inventory="$test_root/incomplete-inventory.tsv"
printf '1\tbuild-pre\n' >"$incomplete_inventory"
if provenance_validate_inventory_sequence "$incomplete_inventory" build; then
  fail "incomplete ordered inventory evidence was accepted"
fi
extra_inventory="$test_root/extra-inventory.tsv"
printf '1\tbuild-pre\n2\tbuild-post\n3\tbuild-post\n' >"$extra_inventory"
if provenance_validate_inventory_sequence "$extra_inventory" build; then
  fail "extra ordered inventory evidence was accepted"
fi
incomplete_checkpoints="$test_root/incomplete-checkpoints.tsv"
printf 'startup\t1\t%s\t%s\t%s\t%s\n' \
  "$(printf '%064d' 1)" "$(printf '%064d' 2)" \
  "$(printf '%064d' 3)" "$(printf '%064d' 4)" >"$incomplete_checkpoints"
if provenance_validate_checkpoint_sequence "$incomplete_checkpoints" true; then
  fail "incomplete ordered checkpoint evidence was accepted"
fi
incomplete_ledgers="$test_root/incomplete-host-ledgers"
mkdir "$incomplete_ledgers"
printf '1\tbuild-pre\n2\tbuild-post\n' >"$incomplete_ledgers/host-observations.tsv"
{
  printf '1\t4242\t1\tFri Sep 4 11:00:00 2026\tgradle_daemon\n'
  printf '2\t4242\t1\tFri Sep 4 11:00:00 2026\tnone\n'
} >"$incomplete_ledgers/host-processes.tsv"
{
  printf '1\tbuild-pre\tallowed_idle_gradle_daemon\t9999\n'
  printf '2\tbuild-post\tclean\t-\n'
} >"$incomplete_ledgers/host-classifications.tsv"
if provenance_validate_host_ledgers "$incomplete_ledgers"; then
  fail "host ledger with an absent PID reference was accepted"
fi
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

race_inventory="$test_root/race-inventory"
mkdir "$race_inventory"
provenance_capture_processes() {
  printf '%s\n' ' 200 1 Fri Sep 4 11:00:00 2026 /usr/bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'
}
provenance_process_start() {
  if [[ -e $race_inventory/changed ]]; then
    printf 'Fri Sep 4 11:00:01 2026\n'
  else
    : >"$race_inventory/changed"
    printf 'Fri Sep 4 11:00:00 2026\n'
  fi
}
provenance_gradle_status() { printf '200\tidle\n'; }
if provenance_inventory_boundary "$race_inventory" 100 pre-source \
    "$test_root/fake-gradle-status" /owned/home 512 2 false; then
  fail "process identity race was accepted"
fi
assert_contains "$race_inventory/host-violations.tsv" process_identity_race
pass

budget_inventory="$test_root/budget-inventory"
mkdir "$budget_inventory"
provenance_capture_processes() { printf '%s\n' ' 1 0 Fri Sep 4 00:00:00 2026 launchd'; }
if provenance_inventory_boundary "$budget_inventory" 100 pre-source \
    "$test_root/fake-gradle-status" /owned/home 32 2 false; then
  fail "host evidence byte budget was not enforced"
fi
budget_bytes=$(provenance_evidence_bytes \
  "$budget_inventory/host-observations.tsv" "$budget_inventory/host-processes.tsv" \
  "$budget_inventory/host-classifications.tsv" "$budget_inventory/host-violations.tsv")
((budget_bytes <= 32)) || fail "retained host evidence exceeded configured byte budget"
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
cat >"$collector_bin/gradle" <<'EOF'
#!/usr/bin/env bash
case ${FAKE_COLLECTOR_MODE:-selected} in
  slow) exec /bin/sleep 5 ;;
  large) awk 'BEGIN { for (i=0; i<100; i++) print "200 IDLE 9.7 extra-output" }' ;;
  selected)
    printf '   PID STATUS   INFO\n'
    printf '  200 IDLE     9.7.0\n\n'
    printf '%s\n' 'Only Daemons for the current Gradle version are displayed. For more on this, please refer to https://docs.gradle.org/9.7.0/userguide/gradle_daemon.html#sec:status in the Gradle documentation.'
    ;;
esac
EOF
chmod +x "$collector_bin/ps" "$collector_bin/gradle"
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
if FAKE_COLLECTOR_MODE=large provenance_gradle_status \
    "$collector_bin/gradle" /owned/home 2 64 >"$test_root/raw-gradle-status-overflow"; then
  fail "oversized Gradle status output was accepted"
fi
[[ ! -s $test_root/raw-gradle-status-overflow ]] || fail "oversized Gradle status bytes were retained"
FAKE_COLLECTOR_MODE=selected provenance_gradle_status \
  "$collector_bin/gradle" /owned/home 2 1024 >"$test_root/selected-gradle-status"
assert_contains "$test_root/selected-gradle-status" $'200\tidle'
pass

fixture="$test_root/full-run-fixture"
fixture_lease_dir="$test_root/owned-host-lease"
mkdir -p "$fixture/tools" "$fixture/fake-bin" "$fixture/river-bench/build/classes" \
  "$fixture/river-bench/build"
cp "$script_dir/tps-test.sh" "$script_dir/tps-provenance.sh" "$fixture/tools/"
printf 'PROVENANCE_CANONICAL_LEASE_DIR=%q\n' "$fixture_lease_dir" \
  >>"$fixture/tools/tps-provenance.sh"
printf 'build/\n' >"$fixture/.gitignore"
printf 'original-source\n' >"$fixture/mutable-source.txt"
cp "$script_dir/../make.sh" "$fixture/make.sh"
cat >"$fixture/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")" && pwd)
if [[ ${1:-} == --status ]]; then
  printf '   PID STATUS   INFO\n'
  printf '  200 IDLE     9.7.0\n\n'
  printf '%s\n' 'Only Daemons for the current Gradle version are displayed. For more on this, please refer to https://docs.gradle.org/9.7.0/userguide/gradle_daemon.html#sec:status in the Gradle documentation.'
  exit 0
fi
[[ ${FAKE_BUILD_FAIL:-false} != true ]] || exit 17
descriptor= id=
for argument in "$@"; do
  case $argument in
    -PriverTpsClasspathOutput=*) descriptor=${argument#*=} ;;
    -PriverTpsBuildId=*) id=${argument#*=} ;;
  esac
done
if [[ -n ${FAKE_BUILD_GATE:-} ]]; then
  printf '%s\n' "$$" >"$FAKE_BUILD_GATE/pid"
  : >"$FAKE_BUILD_GATE/started"
  while [[ ! -e $FAKE_BUILD_GATE/release ]]; do /bin/sleep 0.02; done
fi
mkdir -p "$root/river-bench/build/classes" "$root/river-bench/build/resources"
printf 'resource\n' >"$root/river-bench/build/resources/resource.txt"
printf 'compiled-by-fixture\n' >"$root/river-bench/build/classes/Main.class"
{
  printf 'schema=river-tps-runtime-v3\nbuild.id=%s\n' "$id"
  printf 'build.inputs=%s\nbuild.cache_trust=gradle_declared_inputs\n' "${FAKE_BUILD_INPUTS:-workspace_declared}"
  printf 'gradle.version=fixture\ngradle.home=/fake/gradle\ngradle.user.home=/fake/gradle-user-home\ngradle.process.pid=%s\n' "$$"
  printf 'java.home=/fake/java\njava.version=fake-25\n'
  printf 'compiler.fixture.home=/fake/java\ncompiler.fixture.version=fake-25\n'
  printf 'compiler.fixture.executable=%s\n' "$root/fake-java"
  printf 'compiler.fixture.launcher_sha256=%s\n' "$(shasum -a 256 "$root/fake-java" | awk '{print $1}')"
  printf 'compiler.fixture.selected_options_sha256=%064d\n' 0
  printf 'classpath=%s\n' "$root/river-bench/build/classes" "$root/river-bench/build/resources"
} >"$descriptor"
[[ -z ${FAKE_BUILD_MUTATE_SOURCE:-} ]] || printf 'build-mutation\n' >>"$FAKE_BUILD_MUTATE_SOURCE"
echo 'BUILD SUCCESSFUL (fixture)'
EOF
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
if [[ -n ${FAKE_METADATA_MUTATION_TARGET:-} && -f ${FAKE_METADATA_MUTATION_TRIGGER:-} &&
    ! -e ${FAKE_METADATA_MUTATION_DONE:-} ]]; then
  printf 'late-class-mutation\n' >"$FAKE_METADATA_MUTATION_TARGET"
  : >"$FAKE_METADATA_MUTATION_DONE"
fi
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
[[ -z ${FAKE_WORKLOAD_STARTED:-} ]] || : >"$FAKE_WORKLOAD_STARTED"
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
if [[ -n ${FAKE_CHECKPOINT_COLLISION_DIR:-} ]]; then
  printf 'external-checkpoint-sentinel\n' >"$FAKE_CHECKPOINT_COLLISION_DIR/checkpoints"
fi
if [[ -n ${FAKE_METADATA_COLLISION_PATH:-} ]]; then
  printf 'external-metadata-sentinel\n' >"$FAKE_METADATA_COLLISION_PATH"
fi
if [[ -n ${FAKE_TERMINAL_COLLISION_PATH:-} ]]; then
  printf 'external-terminal-sentinel\n' >"$FAKE_TERMINAL_COLLISION_PATH"
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
if [[ -n ${FAKE_CLIENT_PID_FILE:-} ]]; then printf '%s\n' "$$" >"$FAKE_CLIENT_PID_FILE"; fi
if [[ ${FAKE_CLIENT_IGNORE_TERM:-false} == true ]]; then
  trap '' TERM
  while :; do :; done
fi
if [[ -n ${FAKE_CLIENT_DELAY:-} ]]; then /bin/sleep "$FAKE_CLIENT_DELAY"; fi
if [[ -n ${FAKE_MUTATE_SOURCE:-} ]]; then
  printf 'mutated-source\n' >"$FAKE_MUTATE_SOURCE"
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
chmod +x "$fixture/make.sh" "$fixture/gradlew" "$fixture/fake-bin/ps" "$fixture/fake-bin/sed" \
  "$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" "$fixture/tools/tps-provenance.sh"
git -C "$fixture" init -q
git -C "$fixture" config user.name test
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" add .
git -C "$fixture" commit -qm fixture
PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
  "$fixture/make.sh" >"$test_root/make-success.log"


full_output="$test_root/full-output"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_JAVA="$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" --output-dir="$full_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null
assert_contains "$full_output/run-metadata.properties" 'run.result=provisional'
assert_contains "$full_output/run-metadata.properties" 'run.status=TERMINAL_RECEIPT_REQUIRED'
assert_contains "$full_output/run-metadata.properties.terminal-receipt" 'terminal.result=success'
assert_contains "$full_output/run-metadata.properties" 'provenance.source_stable=true'
assert_contains "$full_output/run-metadata.properties" 'provenance.host_exclusion_valid=true'
assert_contains "$full_output/run-metadata.properties" 'host.guarantee=qualified'
assert_contains "$full_output/run-metadata.properties" 'provenance.publication_valid=true'
provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
  "$full_output/tpcc-acceptance.properties" \
  "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success ||
  fail "shared validator rejected a complete terminal success receipt"
provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
  "$full_output/tpcc-acceptance.properties" \
  "$full_output/run-metadata.properties.terminal-receipt" "$full_output" success promotion ||
  fail "qualified host ownership was not accepted as promotion evidence"
assert_contains "$full_output/run-metadata.properties.terminal-receipt" 'host.release_outcome=released'
cp "$full_output/run-metadata.properties" "$test_root/binding-metadata.saved"
cp "$full_output/run-metadata.properties.terminal-receipt" "$test_root/binding-terminal.saved"
replace_property "$full_output/run-metadata.properties" provenance.classpath_sha256 "$(printf '%064d' 0)"
provenance_write_terminal_receipt "$full_output/run-metadata.properties.terminal-receipt" \
  success OK "$(provenance_property_once evidence.run_id "$full_output/run-metadata.properties")" \
  "$(provenance_property_once artifact.run_id "$full_output/run-metadata.properties")" \
  "$(provenance_sha256_file "$full_output/run-metadata.properties")" \
  "$(provenance_property_once publisher.pid "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once publisher.start "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once publisher.identity_sha256 "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once terminal.nonce "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once terminal.commitment_sha256 "$test_root/binding-terminal.saved")" \
  "$full_output" \
  "$(provenance_property_once host.release_outcome "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once lease.evidence_run_id "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once lease.owner_pid "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once lease.owner_start "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once lease.owner_identity_sha256 "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once lease.terminal_commitment_sha256 "$test_root/binding-terminal.saved")" \
  "$(provenance_property_once host.guarantee "$test_root/binding-terminal.saved")"
if provenance_validate_terminal_receipt "$full_output/run-metadata.properties" \
    "$full_output/tpcc-acceptance.properties" "$full_output/run-metadata.properties.terminal-receipt" \
    "$full_output" success; then
  fail "receipt accepted a false classpath claim with otherwise coherent hashes"
fi
mv "$test_root/binding-metadata.saved" "$full_output/run-metadata.properties"
mv "$test_root/binding-terminal.saved" "$full_output/run-metadata.properties.terminal-receipt"
pass

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
while IFS=$'\t' read -r stage _ source_hash status_hash classpath_hash descriptor_hash; do
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/source-manifest.$stage.tsv")" "$source_hash"
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/git-status.$stage.txt")" "$status_hash"
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/classpath.$stage.tsv")" "$classpath_hash"
  assert_equal "$(provenance_sha256_file "$full_output/checkpoints/runtime.$stage.properties")" "$descriptor_hash"
done <"$full_output/provenance-checkpoints.tsv"
metadata_status_hash=$(sed -n 's/^git.status_sha256=//p' "$full_output/run-metadata.properties")
assert_equal "$(provenance_sha256_file "$full_output/checkpoints/git-status.metadata.txt")" "$metadata_status_hash"
publication_hash_before=$(provenance_sha256_file "$full_output/run-metadata.properties")
if PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
    RIVER_JAVA="$fixture/fake-java" \
    "$fixture/tools/tps-test.sh" --output-dir="$full_output" >/dev/null 2>&1; then
  fail "full-run evidence directory was overwritten"
fi
assert_equal "$(provenance_sha256_file "$full_output/run-metadata.properties")" "$publication_hash_before"
pass

explicit_output="$test_root/explicit-output"
explicit_metadata="$test_root/explicit-metadata.properties"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_JAVA="$fixture/fake-java" \
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
  RIVER_JAVA="$fixture/fake-java" \
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
printf 'original-source\n' >"$fixture/mutable-source.txt"
pass

interrupted_output="$test_root/interrupted-output"
client_started="$test_root/client-started"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CLIENT_STARTED="$client_started" FAKE_CLIENT_DELAY=30 \
  RIVER_JAVA="$fixture/fake-java" \
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
[[ -f $interrupted_output/provenance-checkpoints.tsv ]] ||
  fail "interrupted evidence was not preserved"
pass

term_ignoring_output="$test_root/term-ignoring-output"
term_ignoring_started="$test_root/term-ignoring-started"
term_ignoring_pid_file="$test_root/term-ignoring-pid"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CLIENT_STARTED="$term_ignoring_started" \
  FAKE_CLIENT_PID_FILE="$term_ignoring_pid_file" FAKE_CLIENT_IGNORE_TERM=true \
  RIVER_JAVA="$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" --output-dir="$term_ignoring_output" \
  --server-stop-timeout-seconds=1 --warmup-seconds=1 --measured-seconds=1 \
  --terminals=1 >/dev/null 2>&1 &
term_ignoring_tps_pid=$!
for _ in {1..100}; do
  [[ -e $term_ignoring_started && -s $term_ignoring_pid_file ]] && break
  /bin/sleep 0.05
done
[[ -e $term_ignoring_started && -s $term_ignoring_pid_file ]] ||
  fail "TERM-ignoring cleanup fixture did not reach the client"
term_ignoring_client_pid=$(cat "$term_ignoring_pid_file")
kill -TERM "$term_ignoring_tps_pid"
set +e
wait "$term_ignoring_tps_pid"
term_ignoring_status=$?
set -e
assert_equal "$term_ignoring_status" 143
assert_contains "$term_ignoring_output/run-metadata.properties" \
  'run.provisional_status=OWNED_PROCESS_LEAK'
if kill -0 "$term_ignoring_client_pid" 2>/dev/null; then
  fail "TERM-ignoring owned client survived bounded KILL cleanup"
fi
pass

runner_timeout_output="$test_root/runner-timeout-output"
runner_timeout_pid_file="$test_root/runner-timeout-pid"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CLIENT_PID_FILE="$runner_timeout_pid_file" FAKE_CLIENT_IGNORE_TERM=true \
  RIVER_JAVA="$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" --output-dir="$runner_timeout_output" \
  --runner-timeout-seconds=1 --server-stop-timeout-seconds=1 \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null 2>&1
runner_timeout_status=$?
set -e
assert_equal "$runner_timeout_status" 124
assert_contains "$runner_timeout_output/run-metadata.properties" \
  'run.provisional_status=OWNED_PROCESS_LEAK'
runner_timeout_client_pid=$(cat "$runner_timeout_pid_file")
if kill -0 "$runner_timeout_client_pid" 2>/dev/null; then
  fail "runner-timeout TERM-ignoring client survived bounded KILL cleanup"
fi
pass

terminal_collision_output="$test_root/terminal-collision-output"
terminal_collision_path="$terminal_collision_output/run-metadata.properties.terminal-receipt"
mkdir "$terminal_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_TERMINAL_COLLISION_PATH="$terminal_collision_path" \
  RIVER_JAVA="$fixture/fake-java" \
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
  RIVER_JAVA="$fixture/fake-java" \
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

checkpoint_collision_output="$test_root/checkpoint-collision-output"
mkdir "$checkpoint_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CHECKPOINT_COLLISION_DIR="$checkpoint_collision_output" \
  RIVER_JAVA="$fixture/fake-java" \
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

metadata_collision_output="$test_root/metadata-collision-output"
mkdir "$metadata_collision_output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_METADATA_COLLISION_PATH="$metadata_collision_output/run-metadata.properties" \
  RIVER_JAVA="$fixture/fake-java" \
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
assert_contains "$metadata_collision_output/evidence-invalid.status" \
  'result=evidence_invalid'
pass

[[ -x "$script_dir/../make.sh" ]] || fail "standalone build script is missing"
expect_prelaunch_failure() {
  local label=$1 marker="$test_root/$1.workload-started"
  if PATH="$fixture/fake-bin:$PATH" RIVER_JAVA="$fixture/fake-java" \
      FAKE_WORKLOAD_STARTED="$marker" "$fixture/tools/tps-test.sh" \
      --output-dir="$test_root/$label" --warmup-seconds=1 --measured-seconds=1 \
      --terminals=1 >"$test_root/$label.log" 2>&1; then
    fail "$label admitted invalid prebuilt evidence"
  fi
  [[ ! -e $marker ]] || fail "$label started a workload before validation"
  assert_contains "$test_root/$label/run-metadata.properties.terminal-receipt" \
    'terminal.result=evidence_invalid'
  pass
}

class_file="$fixture/river-bench/build/classes/Main.class"
cp "$class_file" "$test_root/compiled.saved"
printf 'stale-or-mutated-class\n' >"$class_file"
expect_prelaunch_failure stale-class
cp "$test_root/compiled.saved" "$class_file"
rm "$class_file"
expect_prelaunch_failure missing-class
cp "$test_root/compiled.saved" "$class_file"

runtime="$fixture/river-bench/build/tps-runtime-classpath.properties"
cp "$runtime" "$test_root/runtime.saved"
awk '/^classpath=/ { entries[++count]=$0; next } { print }
  END { for (i=count; i>0; i--) print entries[i] }' "$test_root/runtime.saved" >"$runtime"
expect_prelaunch_failure reordered-classpath
cp "$test_root/runtime.saved" "$runtime"
record="$fixture/river-bench/build/tps-build/$(provenance_property_once build.id "$runtime")"
mv "$record/completion.properties" "$record/completion.saved"
expect_prelaunch_failure incomplete-build
mv "$record/completion.saved" "$record/completion.properties"

cp -R "$record" "$test_root/missing-compiler-record"
rm "$test_root/missing-compiler-record/completion.properties"
sed '/^compiler\./d' "$record/runtime.properties" >"$test_root/missing-compiler-record/runtime.properties"
if provenance_seal_build_record "$test_root/missing-compiler-record" \
    "$(provenance_property_once build.id "$runtime")"; then
  fail "missing compiler identity was sealed"
fi
pass

invalid_cache_record="$test_root/invalid-cache-trust-record"
cp -R "$record" "$invalid_cache_record"
replace_property "$invalid_cache_record/runtime.properties" \
  build.cache_trust unsupported
runtime_hash=$(provenance_sha256_file "$invalid_cache_record/runtime.properties")
awk -F '\t' -v replacement="$runtime_hash" 'BEGIN { OFS="\t" }
  $2 == "runtime.properties" { $1 = replacement }
  { print }
' "$invalid_cache_record/files.tsv" >"$invalid_cache_record/files.tsv.replaced"
mv -- "$invalid_cache_record/files.tsv.replaced" "$invalid_cache_record/files.tsv"
files_hash=$(provenance_sha256_file "$invalid_cache_record/files.tsv")
replace_property "$invalid_cache_record/completion.properties" files.sha256 "$files_hash"
if provenance_validate_build_record "$invalid_cache_record" \
    "$(provenance_property_once build.id "$runtime")"; then
  fail "unsupported runtime cache trust was accepted"
fi
pass

empty_host_record="$test_root/empty-host-observations-record"
cp -R "$record" "$empty_host_record"
: >"$empty_host_record/host-observations.tsv"
empty_host_hash=$(provenance_sha256_file "$empty_host_record/host-observations.tsv")
replace_property "$empty_host_record/completion.properties" \
  build.host-observations_sha256 "$empty_host_hash"
awk -F '\t' -v replacement="$empty_host_hash" 'BEGIN { OFS="\t" }
  $2 == "host-observations.tsv" { $1 = replacement }
  { print }
' "$empty_host_record/files.tsv" >"$empty_host_record/files.tsv.replaced"
mv -- "$empty_host_record/files.tsv.replaced" "$empty_host_record/files.tsv"
empty_host_files_hash=$(provenance_sha256_file "$empty_host_record/files.tsv")
replace_property "$empty_host_record/completion.properties" files.sha256 "$empty_host_files_hash"
if provenance_validate_build_record "$empty_host_record" \
    "$(provenance_property_once build.id "$runtime")"; then
  fail "empty host observations were accepted after coherent rehashing"
fi
pass

late_output="$test_root/late-runtime-mutation"
if PATH="$fixture/fake-bin:$PATH" RIVER_JAVA="$fixture/fake-java" \
    FAKE_METADATA_MUTATION_TRIGGER="$late_output/run-metadata.properties" \
    FAKE_METADATA_MUTATION_TARGET="$class_file" FAKE_METADATA_MUTATION_DONE="$test_root/late.done" \
    "$fixture/tools/tps-test.sh" --output-dir="$late_output" --warmup-seconds=1 \
    --measured-seconds=1 --terminals=1 >"$test_root/late.log" 2>&1; then
  fail "late runtime-only mutation was accepted"
fi
assert_contains "$late_output/run-metadata.properties.terminal-receipt" 'terminal.status=PROVENANCE_CHANGED'
provenance_validate_terminal_receipt "$late_output/run-metadata.properties" \
  "$late_output/tpcc-acceptance.properties" "$late_output/run-metadata.properties.terminal-receipt" \
  "$late_output" evidence_invalid || fail "late drift did not retain a valid failure receipt"
cp "$test_root/compiled.saved" "$class_file"
pass

if PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" FAKE_BUILD_FAIL=true \
    "$fixture/make.sh" >"$test_root/failed-make.log" 2>&1; then
  fail "failed make succeeded"
fi
assert_contains "$test_root/failed-make.log" 'exit_status=17'
expect_prelaunch_failure failed-make
PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
  "$fixture/make.sh" >"$test_root/remake.log"

if PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" FAKE_BUILD_INPUTS=unsupported \
    "$fixture/make.sh" >"$test_root/unsupported-make.log" 2>&1; then
  fail "unsupported build inputs were sealed"
fi
expect_prelaunch_failure unsupported-make
PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
  "$fixture/make.sh" >"$test_root/remake.log"

if PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
    FAKE_BUILD_MUTATE_SOURCE="$fixture/mutable-source.txt" \
    "$fixture/make.sh" >"$test_root/mutating-make.log" 2>&1; then
  fail "source mutation during make was sealed"
fi
expect_prelaunch_failure mutating-make
printf 'original-source\n' >"$fixture/mutable-source.txt"
PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
  "$fixture/make.sh" >"$test_root/remake.log"

build_gate="$test_root/build-gate"
mkdir "$build_gate"
PATH="$fixture/fake-bin:$PATH" TMPDIR="$test_root" \
  RIVER_TPS_BUILD_STOP_TIMEOUT_SECONDS=2 FAKE_BUILD_GATE="$build_gate" \
  "$fixture/make.sh" >"$test_root/interrupted-make.log" 2>&1 &
test_make_pid=$!
for ((attempt=0; attempt<500; attempt++)); do
  [[ -f $build_gate/started ]] && break
  /bin/sleep 0.02
done
[[ -f $build_gate/started ]] || fail "make interruption fixture did not start"
build_pid=$(cat "$build_gate/pid")
kill -TERM "$test_make_pid"
make_status=0
wait "$test_make_pid" || make_status=$?
test_make_pid=
assert_equal 143 "$make_status"
if kill -0 "$build_pid" 2>/dev/null; then fail "interrupted make retained its owned Gradle process"; fi
assert_contains "$test_root/interrupted-make.log" 'phase=interrupted exit_status=143'
expect_prelaunch_failure interrupted-make

if grep -Eq 'writeRiverTpsRuntimeClasspath|provenance_run_logged_marked|build launcher' \
    "$script_dir/tps-test.sh"; then
  fail "build invocation remains in tps-test.sh"
fi
assert_contains "$script_dir/tps-test.sh" "trap 'run_result=interrupted"
pass

echo "PASS: $tests provenance boundary tests"
