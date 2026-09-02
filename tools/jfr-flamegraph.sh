#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/jfr-flamegraph.sh --jfr=PATH [options]

Print a readable flamegraph-equivalent report from JFR execution samples.
The folded stacks are printed deepest-to-shallowest in flamegraph order, with
the sample count as the weight. This does not alter the JFR recording.

Options:
  --jfr=PATH       JFR recording to inspect (required)
  --top=N          Number of folded stacks to show (default: 25)
  --folded=PATH    Also write folded stacks to PATH
  -h, --help       Show this help
EOF
}

jfr_path=
top=25
folded_path=

while (($# > 0)); do
  case $1 in
    --jfr=*) jfr_path=${1#*=} ;;
    --top=*) top=${1#*=} ;;
    --folded=*) folded_path=${1#*=} ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -z ${jfr_path} ]]; then
  echo "error: --jfr=PATH is required" >&2
  usage >&2
  exit 2
fi
if [[ ! -f ${jfr_path} ]]; then
  echo "error: JFR recording does not exist: $jfr_path" >&2
  exit 1
fi
if [[ ! ${top} =~ ^[1-9][0-9]*$ ]]; then
  echo "error: --top must be a positive integer" >&2
  exit 2
fi

jfr_bin=${RIVER_JFR:-jfr}
if ! command -v "$jfr_bin" >/dev/null 2>&1; then
  echo "error: JFR tool not found: $jfr_bin" >&2
  exit 1
fi

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-jfr-flamegraph.XXXXXX")
cleanup() {
  rm -rf -- "$temporary_dir"
}
trap cleanup EXIT

raw_stacks="$temporary_dir/raw-stacks.txt"
folded_stacks="$temporary_dir/folded-stacks.txt"

# jfr prints the sampled leaf first. Reverse each stack so the output follows
# folded-stack convention: root;caller;leaf count.
"$jfr_bin" print --events jdk.ExecutionSample --stack-depth 64 "$jfr_path" \
  | awk '
  function emit(    i, folded) {
    if (frame_count == 0) return
    folded = frames[frame_count]
    for (i = frame_count - 1; i >= 1; i--) folded = folded ";" frames[i]
    print folded
    frame_count = 0
  }
  /^jdk\.ExecutionSample[[:space:]]*\{/ {
    frame_count = 0
    in_stack = 0
    next
  }
  /stackTrace = \[/ {
    frame_count = 0
    in_stack = 1
    next
  }
  in_stack {
    frame = $0
    sub(/^[[:space:]]+/, "", frame)
    sub(/,[[:space:]]*$/, "", frame)
    if (frame == "]") {
      emit()
      in_stack = 0
      next
    }
    if (frame == "..." || frame == "") next
    sub(/[[:space:]]+line:[[:space:]]+[0-9]+$/, "", frame)
    frames[++frame_count] = frame
    next
  }
  END { if (in_stack) emit() }
' >"$raw_stacks"

sort "$raw_stacks" | uniq -c | sort -rn >"$folded_stacks"
sample_count=$(wc -l <"$raw_stacks" | tr -d ' ')
unique_stacks=$(wc -l <"$folded_stacks" | tr -d ' ')

if [[ -n ${folded_path} ]]; then
  folded_parent=$(dirname -- "$folded_path")
  mkdir -p -- "$folded_parent"
  cp -- "$folded_stacks" "$folded_path"
fi

echo "jfr=$(cd -- "$(dirname -- "$jfr_path")" && pwd)/$(basename -- "$jfr_path")"
echo "report=folded_execution_samples"
echo "execution_samples=$sample_count"
echo "unique_stacks=$unique_stacks"
if ((sample_count < 100)); then
  echo "sampling_warning=too_few_execution_samples_for_a_stable_hotspot_ranking"
fi
if [[ -n ${folded_path} ]]; then
  echo "folded_stacks=$folded_path"
fi
echo
echo "=== top leaf methods ==="
if ((sample_count == 0)); then
  echo "(no jdk.ExecutionSample events)"
else
  awk '
    {
      weight = $1
      stack = $0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+/, "", stack)
      count = split(stack, frames, ";")
      totals[frames[count]] += weight
    }
    END {
      for (method in totals) print totals[method], method
    }
  ' "$folded_stacks" | sort -rn | sed -n "1,${top}p"
fi
echo
echo "=== top folded stacks (sample count first) ==="
if ((sample_count == 0)); then
  echo "(no jdk.ExecutionSample events)"
else
  sed -n "1,${top}p" "$folded_stacks"
fi
