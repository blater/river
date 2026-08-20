#!/usr/bin/env bash

usage() {
cat <<EOF
Usage:

  ./designdebt.sh
  ./designdebt.sh --limit 20 --explain
  ./designdebt.sh --limit 0
  ./designdebt.sh --include-bench

river-bench is excluded by default. Thresholds are pinned in
river-design-debt.xml, and the scoring implementation is in codehealth.py.
EOF
}

if [ "$1" = "-h" ]; then
  usage
  exit 0
fi

which -s pmd
if [ $? -ne 0 ]; then
  echo "error, pmd is either not installed or not on the path. Cannot continue."
  exit 1
fi

set -euo pipefail
tool_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$tool_dir/codehealth.py" "$@"
