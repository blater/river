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
# A failed or interrupted attempt cannot leave the previous descriptor usable.
rm -f -- "$runtime_descriptor"
build_stage=$(mktemp -d "${TMPDIR:-/tmp}/river-tps-build.XXXXXX")
build_record="$script_dir/river-bench/build/tps-build/$build_id"
build_phase=source_capture
build_complete=false
finish_build() {
  local status=$? pid=${PROVENANCE_LOGGED_PID:-} attempt=0
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
    rm -rf -- "$build_stage" || status=1
  else
    {
      printf 'build.phase=%s\n' "$build_phase"
      printf 'build.exit_status=%s\n' "$status"
    } >"$build_stage/build.status"
    echo "build_evidence=$build_stage phase=$build_phase exit_status=$status" >&2
  fi
  exit "$status"
}
trap finish_build EXIT
trap 'build_phase=interrupted; exit 130' INT
trap 'build_phase=interrupted; exit 143' TERM

provenance_write_source_manifest "$script_dir" "$build_stage/source.before.tsv"
provenance_write_git_status "$script_dir" "$build_stage/git-status.before.txt"
gradle_args=(":river-bench:writeRiverTpsRuntimeClasspath"
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
echo "build_evidence=$build_record status=completed"
