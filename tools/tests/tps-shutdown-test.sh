#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
script_path="$script_dir/tps-test.sh"
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-shutdown-test.XXXXXX")
trap 'rm -rf -- "$test_dir"' EXIT

awk '
  /^# BEGIN owned-process shutdown helpers$/ { in_helpers=1; next }
  /^# END owned-process shutdown helpers$/ { exit }
  in_helpers { print }
' "$script_path" >"$test_dir/shutdown-helpers.sh"
awk '
  /^# BEGIN cleanup function$/ { in_cleanup=1; next }
  /^# END cleanup function$/ { exit }
  in_cleanup { print }
' "$script_path" >"$test_dir/cleanup-function.sh"

source "$test_dir/shutdown-helpers.sh"

fail() {
  echo "not ok: $*" >&2
  exit 1
}

reset_fake_processes() {
  server_stop_timeout_seconds=$1
  SECONDS=500
  shutdown_deadline_initialized=false
  shutdown_deadline_seconds=0
  shutdown_failure_reported=false
  owned_process_cleanup_valid=true
  runner_term_sent=false
  runner_kill_sent=false
  server_kill_sent=false
  server_exit_status=0
  reaped_child_status=0
  fake_server_exit_status=0
  runner_pid=101
  server_pid=202
  runner_live=true
  server_live=true
  runner_zombie=false
  runner_job_state=Running
  server_job_state=Running
  jobs_failure=false
  jobs_unknown_state=false
  runner_kill_reaps=true
  server_kill_reaps=true
  runner_term_sleeps=0
  server_stop_sleeps=0
  runner_term_exit_after=0
  server_stop_exit_after=0
  virtual_sleep_count=0
  virtual_wait_count=0
  runner_kill_count=0
  server_kill_count=0
  live_wait_attempt=false
  temp_dir="$test_dir/run"
  server_stop="$test_dir/server.stop"
  rm -f -- "$server_stop"
  mkdir -p "$temp_dir/database"
}

kill() {
  local signal pid
  if [[ $1 == -* ]]; then signal=$1; pid=$2; else signal=-TERM; pid=$1; fi
  case $signal in
    -0)
      case $pid in
        101)
          if [[ $runner_live == true ]]; then return 0; else return 1; fi
          ;;
        202)
          if [[ $server_live == true ]]; then return 0; else return 1; fi
          ;;
        *) return 1 ;;
      esac
      ;;
    -KILL)
      case $pid in
        101)
          ((runner_kill_count += 1))
          if [[ $runner_kill_reaps == true ]]; then runner_live=false; runner_zombie=false; fi
          ;;
        202)
          ((server_kill_count += 1))
          if [[ $server_kill_reaps == true ]]; then server_live=false; server_zombie=false; fi
          ;;
      esac
      ;;
    *)
      case $pid in
        101) runner_term_sent=true ;;
      esac
      ;;
  esac
  return 0
}

jobs() {
  [[ $1 == -l ]] || return 2
  [[ $jobs_failure != true ]] || return 1
  if [[ $runner_live == true ]]; then
    if [[ $jobs_unknown_state == true ]]; then
      printf '[1]+ 101 Unknown fake-runner\n'
    else
      printf '[1]+ 101 %s fake-runner\n' "$runner_job_state"
    fi
  elif [[ $runner_zombie == true ]]; then
    printf '[1]+ 101 Done fake-runner\n'
  fi
  [[ $server_live != true ]] || printf '[2]+ 202 %s fake-server\n' "$server_job_state"
}

wait() {
  local pid=$1
  ((virtual_wait_count += 1))
  case $pid in
    101) [[ $runner_live != true ]] || live_wait_attempt=true ;;
    202) [[ $server_live != true ]] || live_wait_attempt=true ;;
  esac
  [[ $live_wait_attempt != true ]] || return 1
  if [[ $pid == 202 ]]; then return "$fake_server_exit_status"; fi
  return 0
}

sleep() {
  ((virtual_sleep_count += 1))
  SECONDS=$((SECONDS + 1))
  if [[ $runner_term_sent == true ]]; then
    ((runner_term_sleeps += 1))
    if ((runner_term_exit_after > 0 && runner_term_sleeps >= runner_term_exit_after)); then
      runner_live=false
    fi
  fi
  if [[ -e $server_stop ]]; then
    ((server_stop_sleeps += 1))
    if ((server_stop_exit_after > 0 && server_stop_sleeps >= server_stop_exit_after)); then
      server_live=false
    fi
  fi
}

reset_fake_processes 4
runner_term_exit_after=1
server_stop_exit_after=99
shutdown_started=$SECONDS
stop_runner || fail "runner should stop through the fake TERM path"
stop_server || fail "server should be killed and reaped within the shared deadline"
((shutdown_deadline_seconds == shutdown_started + 3)) || fail "shutdown deadline changed between processes"
((SECONDS - shutdown_started <= 4)) || fail "runner and server exceeded their shared budget"
((virtual_sleep_count <= 3)) || fail "shutdown used separate per-process budgets"
[[ -z $runner_pid && -z $server_pid ]] || fail "successfully stopped child PIDs were retained"
[[ $owned_process_cleanup_valid == true ]] || fail "successful cleanup was marked invalid"
[[ $live_wait_attempt == false ]] || fail "wait was called while a process was live"
[[ -e $server_stop ]] || fail "server was not given its graceful stop request"
record_server_shutdown_failure || fail "forced server termination was accepted"
[[ $run_status == SERVER_FORCED_TERMINATION && $run_exit_status == 1 ]] ||
  fail "forced server termination did not report failure"
reset_fake_processes 2
server_live=false
fake_server_exit_status=7
stop_server || fail "exited server was not reaped"
[[ -z $server_pid && $server_exit_status == 7 ]] || fail "server exit status was lost during reap"
record_server_shutdown_failure || fail "nonzero server exit was accepted"
[[ $run_status == SERVER_EXIT_FAILED && $run_exit_status == 1 ]] ||
  fail "nonzero server exit did not report failure"

reset_fake_processes 2
runner_kill_reaps=false
shutdown_started=$SECONDS
set +e
stop_runner 2>"$test_dir/unresponsive.txt"
stop_status=$?
set -e
((stop_status != 0)) || fail "still-live process after SIGKILL was reported as stopped"
[[ $owned_process_cleanup_valid == false ]] || fail "unresponsive child did not invalidate cleanup"
[[ $runner_pid == 101 ]] || fail "unreaped runner PID was discarded"
[[ $live_wait_attempt == false && $virtual_wait_count == 0 ]] || fail "wait was called for a live post-SIGKILL process"
[[ $(<"$test_dir/unresponsive.txt") == *"unresponsive_runner_pid=101"* ]] || fail "runner PID was not reported"
[[ $(<"$test_dir/unresponsive.txt") == *"retained_database=$temp_dir/database"* ]] || fail "database path was not reported"
[[ $(<"$test_dir/unresponsive.txt") == *"retained_evidence_dir=$temp_dir"* ]] || fail "evidence path was not reported"
[[ -d $temp_dir/database ]] || fail "test database directory disappeared"

reset_fake_processes 1
runner_zombie=true
runner_live=false
reap_child_if_exited "$runner_pid" || fail "zombie child should be reapable"
[[ $live_wait_attempt == false && $virtual_wait_count == 1 ]] || fail "terminal zombie was not safely reaped"

reset_fake_processes 1
runner_live=false
runner_zombie=true
server_live=false
shutdown_deadline_initialized=true
shutdown_deadline_seconds=$((SECONDS - 1))
stop_runner || fail "completed runner was not reaped after deadline expiry"
stop_server || fail "completed server was not reaped after deadline expiry"
[[ -z $runner_pid && -z $server_pid ]] || fail "completed child PID was retained"
[[ $runner_kill_count == 0 && $server_kill_count == 0 ]] || fail "completed child received a signal"
[[ $runner_term_sent == false ]] || fail "completed runner received TERM"
[[ ! -e $server_stop ]] || fail "completed server received a filesystem stop request"

reset_fake_processes 1
runner_job_state="Stopped (signal)"
child_is_reapable "$runner_pid" && fail "stopped child was treated as reaped"
reap_child_if_exited "$runner_pid" && fail "wait accepted a stopped child"
jobs_unknown_state=true
child_is_reapable "$runner_pid" && fail "unknown job state was treated as reaped"
reap_child_if_exited "$runner_pid" && fail "wait accepted an unknown job state"
jobs_unknown_state=false
jobs_failure=true
child_is_reapable "$runner_pid" && fail "failed jobs snapshot was treated as reaped"
reap_child_if_exited "$runner_pid" && fail "wait accepted a failed jobs snapshot"
[[ $virtual_wait_count == 0 ]] || fail "wait was called after an uncertain process snapshot"

reset_fake_processes 2
runner_kill_reaps=false
server_kill_reaps=true
set +e
shutdown_owned_processes 2>"$test_dir/cleanup-runner-hang.txt"
set -e
[[ $owned_process_cleanup_valid == false ]] || fail "cleanup lost the runner leak status"
[[ $runner_pid == 101 && -z $server_pid ]] || fail "cleanup discarded or failed to stop an owned PID"
[[ ! -e $server_stop ]] || fail "latched runner failure wrote the server stop file"
[[ $runner_kill_count == 1 && $server_kill_count == 1 ]] || fail "cleanup did not signal each owned process once"
[[ $live_wait_attempt == false ]] || fail "cleanup waited on the live runner"
deadline_after_failure=$shutdown_deadline_seconds
runner_kills_after_failure=$runner_kill_count
server_kills_after_failure=$server_kill_count
set +e
shutdown_owned_processes 2>"$test_dir/cleanup-runner-hang-repeat.txt"
set -e
[[ $shutdown_deadline_seconds == "$deadline_after_failure" ]] || fail "repeated cleanup reset the shared deadline"
[[ $runner_kill_count == "$runner_kills_after_failure" &&
    $server_kill_count == "$server_kills_after_failure" ]] || fail "repeated cleanup signaled a PID again"

reset_fake_processes 2
runner_pid=
server_kill_reaps=false
set +e
shutdown_owned_processes 2>"$test_dir/cleanup-server-hang.txt"
set -e
[[ $owned_process_cleanup_valid == false && $server_pid == 202 ]] || fail "server hang was not retained as cleanup failure"
[[ $live_wait_attempt == false && $virtual_wait_count == 0 ]] || fail "cleanup waited on the live server"
[[ $(<"$test_dir/cleanup-server-hang.txt") == *"unresponsive_server_pid=202"* ]] || fail "server PID was not reported"
[[ $(<"$test_dir/cleanup-server-hang.txt") == *"retained_evidence_dir=$temp_dir"* ]] || fail "server hang evidence path was not reported"

reset_fake_processes 2
runner_kill_reaps=false
server_kill_reaps=false
set +e
shutdown_owned_processes 2>"$test_dir/cleanup-both-hang.txt"
set -e
[[ $owned_process_cleanup_valid == false && $runner_pid == 101 && $server_pid == 202 ]] ||
  fail "cleanup did not retain both unresponsive owned PIDs"
[[ $(<"$test_dir/cleanup-both-hang.txt") == *"unresponsive_runner_pid=101"* &&
    $(<"$test_dir/cleanup-both-hang.txt") == *"unresponsive_server_pid=202"* ]] ||
  fail "cleanup did not report both unresponsive PIDs"
[[ ! -e $server_stop ]] || fail "cleanup wrote the server stop file after the runner was unresponsive"

cleanup_marker="$test_dir/cleanup-side-effect"
if (
  source "$test_dir/cleanup-function.sh"
  temp_dir="$test_dir/cleanup-run"
  runner_pid=303
  server_pid=
  owned_process_cleanup_valid=false
  shutdown_failure_reported=false
  run_status=NOT_STARTED
  run_result=interrupted
  run_phase=interrupted
  run_exit_status=130
  artifact="$temp_dir/staged-artifact"
  artifact_destination="$test_dir/artifact"
  output_dir="$test_dir/output"
  stdout_log="$temp_dir/stdout"
  stderr_log="$temp_dir/stderr"
  combined_log="$temp_dir/combined"
  server_log="$temp_dir/server"
  server_metrics="$temp_dir/metrics"
  metadata="$test_dir/metadata"
  keep_output=false
  persistence_valid=true
  artifact_published=false
  persist_file() { : >"$cleanup_marker"; return 1; }
  persist_if_present() { : >"$cleanup_marker"; return 1; }
  write_metadata() { : >"$cleanup_marker"; return 1; }
  rm() { : >"$cleanup_marker"; return 1; }
  cleanup
) 2>"$test_dir/cleanup-output"; then
  cleanup_status=0
else
  cleanup_status=$?
fi
[[ $cleanup_status == 1 ]] || fail "latched cleanup failure did not exit with failure"
[[ ! -e $cleanup_marker ]] || fail "cleanup copied, wrote metadata, or removed files after an owned-process failure"

echo "ok: TPS shutdown deadline and process-reaping tests"
