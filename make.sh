#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runtime_descriptor="$script_dir/river-bench/build/tps-runtime-classpath.properties"
cd -- "$script_dir"

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

gradle_args=(":river-bench:writeRiverTpsRuntimeClasspath"
  "-PriverTpsClasspathOutput=$runtime_descriptor")
if (( $# == 1 )); then
  gradle_args=(clean "${gradle_args[@]}")
fi

exec "$script_dir/gradlew" "${gradle_args[@]}"
