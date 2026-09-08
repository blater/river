#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd -- "$script_dir"
case $# in
  0) exec ./gradlew --no-daemon :river-bench:installTps ;;
  1) [[ $1 == clean ]] && exec ./gradlew --no-daemon clean :river-bench:installTps ;;
esac
echo "usage: ./make.sh [clean]" >&2
exit 2
