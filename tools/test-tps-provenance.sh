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
provenance_capture_processes() { printf '%s\n' ' 200    1 Fri Sep  4 11:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.0'; }
provenance_gradle_daemon_state() { printf 'busy\n'; }
provenance_gradle_daemon_home() { printf '/owned/home\n'; }
provenance_process_start() { printf 'Fri Sep 4 11:00:00 2026\n'; }
sleep() { : >"$own_monitor/stop"; }
provenance_monitor_host "$own_monitor" 100 "$own_monitor/stop" 0 \
  /owned/home "$own_monitor/owned-build"
[[ ! -s $own_monitor/host-violations.tsv ]] || fail "owned busy Gradle daemon was rejected"
assert_contains "$own_monitor/host-classification.000001.tsv" $'allowed_owned_gradle_daemon\t200'
pass

foreign_monitor="$test_root/foreign-monitor"
mkdir "$foreign_monitor"
: >"$foreign_monitor/host-observations.tsv"
: >"$foreign_monitor/host-violations.tsv"
: >"$foreign_monitor/owned-build"
provenance_gradle_daemon_home() { printf '/foreign/home\n'; }
sleep() { : >"$foreign_monitor/stop"; }
provenance_monitor_host "$foreign_monitor" 100 "$foreign_monitor/stop" 0 \
  /owned/home "$foreign_monitor/owned-build"
assert_contains "$foreign_monitor/host-violations.tsv" $'violation\tbusy_gradle_daemon\t200'
pass

provenance_process_start() {
  ps -p "$1" -o lstart= 2>/dev/null |
    awk 'NF >= 5 {print $1 " " $2 " " $3 " " $4 " " $5}'
}

lease="$test_root/lease"
provenance_process_start() {
  [[ $1 == "$$" ]] && printf 'Fri Sep  4 12:00:00 2026\n'
}
mkdir "$lease"
{
  printf 'pid=%s\n' "$$"
  printf 'start=definitely-not-this-process\n'
} >"$lease/owner"
provenance_acquire_lease "$lease" || fail "stale PID-reuse lease was not reclaimed"
provenance_release_lease "$lease" || fail "owned lease was not released"
mkdir "$lease"
{
  printf 'pid=%s\n' "$$"
  printf 'start=%s\n' "$(provenance_process_start "$$")"
} >"$lease/owner"
if provenance_acquire_lease "$lease"; then
  fail "live lease owner was displaced"
fi
rm -rf -- "$lease"
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
provenance_monitor_host "$race_monitor" 100 "$race_monitor/stop" 0
assert_contains "$race_monitor/host-violations.tsv" $'violation\tprocess_identity_race\t200'
pass

monitor_dir="$test_root/monitor"
mkdir "$monitor_dir"
: >"$monitor_dir/host-observations.tsv"
: >"$monitor_dir/host-violations.tsv"
provenance_capture_processes() { return 1; }
if provenance_monitor_host "$monitor_dir" "$$" "$monitor_dir/stop" 0; then
  fail "process observation race/failure was accepted"
fi
assert_contains "$monitor_dir/host-violations.tsv" process_snapshot_failed
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
  printf 'Fri Sep  4 12:00:00 2026\n'
else
  printf '1 0 Fri Sep  4 00:00:00 2026 launchd\n'
fi
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
printf 'fresh-class-bytes\n' >"$FAKE_RIVER_ROOT/build/classes/Main.class"
{
  printf 'schema=river-tps-runtime-v1\n'
  printf 'gradle.version=fake-1\n'
  printf 'gradle.home=%s\n' "$FAKE_RIVER_ROOT/fake-gradle-home"
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
chmod +x "$fixture/fake-bin/ps" "$fixture/fake-gradle" "$fixture/fake-java" \
  "$fixture/tools/tps-test.sh" "$fixture/tools/tps-provenance.sh"
git -C "$fixture" init -q
git -C "$fixture" config user.name test
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" add .
git -C "$fixture" commit -qm fixture

printf 'stale-class-bytes\n' >"$fixture/build/classes/Main.class"
full_output="$test_root/full-output"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/full-lease" \
  RIVER_TPS_GRADLE_USER_HOME="$test_root/full-gradle-home" \
  RIVER_TPS_PROJECT_CACHE_DIR="$test_root/full-project-cache" \
  "$fixture/tools/tps-test.sh" --output-dir="$full_output" \
  --warmup-seconds=1 --measured-seconds=1 --terminals=1 >/dev/null
assert_contains "$fixture/build/classes/Main.class" fresh-class-bytes
assert_contains "$full_output/run-metadata.properties" 'run.result=completed'
assert_contains "$full_output/run-metadata.properties" 'build.exit_status=0'
gradle_manifest_hash=$(sed -n 's/^build.gradle_runtime_manifest_sha256=//p' \
  "$full_output/run-metadata.properties")
assert_equal "$(provenance_sha256_file "$full_output/gradle-runtime-manifest.tsv")" \
  "$gradle_manifest_hash"
assert_contains "$full_output/run-metadata.properties" 'provenance.source_stable=true'
assert_contains "$full_output/run-metadata.properties" 'provenance.classpath_stable=true'
assert_contains "$full_output/run-metadata.properties" 'provenance.host_exclusion_valid=true'

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
    RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
    "$fixture/tools/tps-test.sh" --output-dir="$full_output" >/dev/null 2>&1; then
  fail "full-run evidence directory was overwritten"
fi
assert_equal "$(provenance_sha256_file "$full_output/run-metadata.properties")" "$publication_hash_before"
pass

source_mutation_output="$test_root/source-mutation-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_MUTATE_SOURCE="$fixture/mutable-source.txt" \
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
printf 'original-source\n' >"$fixture/mutable-source.txt"
pass

classpath_mutation_output="$test_root/classpath-mutation-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_MUTATE_CLASSPATH="$fixture/build/classes/Main.class" \
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
pass

failed_output="$test_root/failed-output"
set +e
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" FAKE_BUILD_FAIL=true \
  RIVER_GRADLE="$fixture/fake-gradle" RIVER_JAVA="$fixture/fake-java" \
  RIVER_TPS_HOST_LEASE_DIR="$test_root/failed-lease" \
  "$fixture/tools/tps-test.sh" --output-dir="$failed_output" >/dev/null 2>&1
failed_status=$?
set -e
assert_equal "$failed_status" 17
assert_contains "$failed_output/build.log" 'fake Gradle declared failure'
assert_contains "$failed_output/run-metadata.properties" 'run.result=build_failed'
assert_contains "$failed_output/run-metadata.properties" 'build.exit_status=17'
pass

interrupted_output="$test_root/interrupted-output"
client_started="$test_root/client-started"
PATH="$fixture/fake-bin:$PATH" FAKE_RIVER_ROOT="$fixture" \
  FAKE_CLIENT_STARTED="$client_started" FAKE_CLIENT_DELAY=30 \
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
assert_contains "$interrupted_output/run-metadata.properties" 'run.result=interrupted'
assert_contains "$interrupted_output/run-metadata.properties" 'run.status=INTERRUPTED'
[[ -f $interrupted_output/build.log && -f $interrupted_output/provenance-checkpoints.tsv ]] ||
  fail "interrupted evidence was not preserved"
pass

grep -F 'writeRiverTpsRuntimeClasspath' "$script_dir/../river-bench/build.gradle.kts" >/dev/null ||
  fail "Gradle-owned classpath provider is missing"
if grep -F 'RIVER_TPS_SKIP_BUILD' "$script_dir/tps-test.sh" >/dev/null; then
  fail "stale-class build bypass remains"
fi
assert_contains "$script_dir/tps-test.sh" "trap 'run_result=interrupted"
assert_contains "$script_dir/tps-test.sh" 'build_status=$?'
pass

echo "PASS: $tests provenance boundary tests"
