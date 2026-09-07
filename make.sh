#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runtime_descriptor="$script_dir/river-bench/build/tps-runtime-classpath.properties"
cd -- "$script_dir"
source "$script_dir/tools/tps-provenance.sh"

case $# in
  0)
    ;;
  1)
    [[ $1 == clean ]] || {
      echo "usage: ./make.sh [clean]" >&2
      exit 2
    }
    ;;
  *)
    echo "usage: ./make.sh [clean]" >&2
    exit 2
    ;;
esac

build_stop_seconds=${RIVER_TPS_BUILD_STOP_TIMEOUT_SECONDS:-10}
[[ $build_stop_seconds =~ ^[0-9]+$ && $build_stop_seconds -gt 0 ]] || {
  echo "invalid build stop timeout" >&2
  exit 2
}

build_id=$(provenance_random_hex)
build_lease_dir=$(provenance_canonical_lease_dir)
build_lease_run_id=$(provenance_random_hex)
build_lease_nonce=$(provenance_random_hex)
# The previous descriptor remains usable until this invocation passes host
# admission; successful admission invalidates it before source capture.
build_stage=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-build.XXXXXX")
build_record="$script_dir/river-bench/build/tps-build/$build_id"
build_phase=source_capture
build_complete=false
build_lease_acquired=false
write_build_status() {
  local status=$1 release_outcome=$2 status_dir=$build_stage
  [[ -d $status_dir ]] || status_dir=$build_record
  {
    printf 'schema=river-tps-build-status-v1\n'
    printf 'build.phase=%s\n' "$build_phase"
    printf 'build.exit_status=%s\n' "$status"
    printf 'build.release_outcome=%s\n' "$release_outcome"
    printf 'build.lease.evidence_run_id=%s\n' "${PROVENANCE_LEASE_RUN_ID:-unavailable}"
    printf 'build.lease.pid=%s\n' "${PROVENANCE_LEASE_OWNER_PID:-unavailable}"
    printf 'build.lease.start=%s\n' "${PROVENANCE_LEASE_OWNER_START:-unavailable}"
    printf 'build.lease.owner_identity_sha256=%s\n' \
      "${PROVENANCE_LEASE_OWNER_IDENTITY_SHA256:-unavailable}"
    printf 'build.lease.nonce=%s\n' "${PROVENANCE_LEASE_NONCE:-unavailable}"
    printf 'build.lease.terminal_commitment_sha256=%s\n' \
      "${PROVENANCE_TERMINAL_COMMITMENT_SHA256:-unavailable}"
  } >"$status_dir/build.status"
}
finish_build() {
  local status=$? pid=${PROVENANCE_LOGGED_PID:-} attempt=0
  local release_outcome=not_acquired
  trap - EXIT INT TERM
  set +e
  if [[ -n $pid ]]; then
    # The runner owns this group; a shared Gradle daemon is not killed here.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    while kill -0 -- "-$pid" 2>/dev/null && ((attempt < build_stop_seconds * 10)); do
      sleep 0.1
      attempt=$((attempt + 1))
    done
    if kill -0 -- "-$pid" 2>/dev/null; then kill -KILL -- "-$pid" 2>/dev/null; fi
    wait "$pid" 2>/dev/null
  fi
  if [[ $build_complete == true && $status -eq 0 ]]; then
    if ! rm -rf -- "$build_stage"; then
      build_phase=temporary_cleanup
      status=1
    fi
    if provenance_release_lease "$build_lease_dir"; then
      release_outcome=released
      build_lease_acquired=false
      if [[ $status -eq 0 ]] && ! provenance_complete_build_record "$build_record" "$build_id" \
          "$build_lease_run_id" "$PROVENANCE_LEASE_OWNER_PID" \
          "$PROVENANCE_LEASE_OWNER_START" "$PROVENANCE_LEASE_OWNER_IDENTITY_SHA256" \
          "$build_lease_nonce" "$PROVENANCE_TERMINAL_COMMITMENT_SHA256"; then
        status=1
        echo "build_evidence=$build_record status=completion_publication_failed" >&2
      fi
    else
      release_outcome=release_failed
      status=1
      echo "build_evidence=$build_record status=lease_release_failed" >&2
    fi
  else
    if [[ $build_lease_acquired == true ]]; then
      if provenance_release_lease "$build_lease_dir"; then
        release_outcome=released
      else
        release_outcome=release_failed
        status=1
      fi
      build_lease_acquired=false
    fi
    echo "build_evidence=$build_stage phase=$build_phase exit_status=$status" >&2
  fi
  if ((status != 0)); then write_build_status "$status" "$release_outcome"; fi
  exit "$status"
}
trap finish_build EXIT
trap 'build_phase=interrupted; exit 130' INT
trap 'build_phase=interrupted; exit 143' TERM

if ! provenance_acquire_lease "$build_lease_dir" "$build_lease_run_id" "$build_lease_nonce"; then
  build_phase=host_ownership
  echo "build_evidence=unavailable status=$PROVENANCE_LEASE_ACQUIRE_STATUS" >&2
  exit 1
fi
build_lease_acquired=true
provenance_validate_current_lease "$build_lease_dir" "$build_lease_run_id" \
  "$build_lease_nonce" || {
  build_phase=host_ownership
  exit 1
}
provenance_inventory_boundary "$build_stage" "$$" build-pre "$script_dir/gradlew" "" \
  16777216 5 false || {
  build_phase=host_preflight
  exit 1
}
provenance_validate_current_lease "$build_lease_dir" "$build_lease_run_id" \
  "$build_lease_nonce" || {
  build_phase=host_ownership
  exit 1
}
rm -f -- "$runtime_descriptor"
provenance_write_source_manifest "$script_dir" "$build_stage/source.before.tsv"
provenance_write_git_status "$script_dir" "$build_stage/git-status.before.txt"
gradle_args=("--no-daemon" ":river-bench:writeRiverTpsRuntimeClasspath"
  "-PriverTpsClasspathOutput=$runtime_descriptor" "-PriverTpsBuildId=$build_id")
if (( $# == 1 )); then
  gradle_args=(clean "${gradle_args[@]}")
fi

build_status=0
build_phase=build
provenance_run_logged "$build_stage/build.log" "$build_stage/build.argv" \
  "$build_stage/build.command" "$script_dir/gradlew" "${gradle_args[@]}" || build_status=$?
cat -- "$build_stage/build.log"
if ((build_status != 0)); then
  exit "$build_status"
fi
build_phase=publication
provenance_write_source_manifest "$script_dir" "$build_stage/source.after.tsv"
provenance_write_git_status "$script_dir" "$build_stage/git-status.after.txt"
cp -- "$runtime_descriptor" "$build_stage/runtime.properties"
provenance_write_classpath_manifest "$runtime_descriptor" "$build_stage/classpath.tsv"
gradle_home=$(provenance_property_once gradle.user.home "$runtime_descriptor")
provenance_validate_current_lease "$build_lease_dir" "$build_lease_run_id" \
  "$build_lease_nonce" || {
  build_phase=host_ownership
  exit 1
}
provenance_inventory_boundary "$build_stage" "$$" build-post "$script_dir/gradlew" "$gradle_home" \
  16777216 5 true || {
  build_phase=host_postflight
  exit 1
}
provenance_validate_current_lease "$build_lease_dir" "$build_lease_run_id" \
  "$build_lease_nonce" || {
  build_phase=host_ownership
  exit 1
}
mkdir -p -- "$(dirname -- "$build_record")"
mkdir -- "$build_record"
for build_file in "$build_stage"/*; do
  provenance_publish_file "$build_file" "$build_record/$(basename -- "$build_file")"
done
if ! provenance_seal_build_record "$build_record" "$build_id"; then
  echo "build_evidence=$build_record status=unsupported_or_changed_inputs" >&2
  exit 1
fi
build_complete=true
echo "build_evidence=$build_record status=payload_sealed"
