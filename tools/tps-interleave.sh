#!/usr/bin/env bash
set -euo pipefail

LC_ALL=C
export LC_ALL

usage() {
  cat <<'EOF'
Usage: tools/tps-interleave.sh --output-dir=PATH [options]

Run paired baseline/candidate River samples in strict A1,B1,A2,B2 order. Each
variant is expressed as repeated --a-option/--b-option values and is passed as
one argv element to tools/tps-test.sh; no shell command strings are evaluated.

Options:
  --output-dir=PATH             New empty evidence directory (required)
  --samples=N                   Pairs per variant (default: 5)
  --a-label=LABEL               Baseline label (default: A)
  --b-label=LABEL               Candidate label (default: B)
  --a-option=OPTION             Variant A tps-test option (repeatable)
  --b-option=OPTION             Variant B tps-test option (repeatable)
  --common-option=OPTION        Option passed to both variants (repeatable)
  --dry-run                     Print the interleaved argv without executing
  -h, --help                    Show this help

The helper records an immutable interleave-result.tsv and stops on the first
failed sample. The per-sample run-metadata.properties and acceptance artifact
remain the source of truth for configuration, provenance, and hashes.
EOF
}

fail() {
  echo "interleave=failed reason=$*" >&2
  exit 1
}

require_positive() {
  [[ $2 =~ ^[0-9]+$ && $2 -gt 0 ]] || fail "$1 must be a positive integer: $2"
}

safe_label() {
  [[ $1 =~ ^[A-Za-z0-9_.-]+$ ]] || fail "label contains unsafe characters: $1"
}

tool_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tps_test="$tool_dir/tps-test.sh"
[[ -x $tps_test ]] || fail "tps-test.sh is not executable"

output_dir=
sample_count=5
a_label=A
b_label=B
a_options=()
b_options=()
common_options=()
dry_run=false

while (($# > 0)); do
  case $1 in
    --output-dir=*) output_dir=${1#*=} ;;
    --samples=*) sample_count=${1#*=} ;;
    --a-label=*) a_label=${1#*=} ;;
    --b-label=*) b_label=${1#*=} ;;
    --a-option=*) a_options+=( "${1#*=}" ) ;;
    --b-option=*) b_options+=( "${1#*=}" ) ;;
    --common-option=*) common_options+=( "${1#*=}" ) ;;
    --dry-run) dry_run=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; fail "unknown option: $1" ;;
  esac
  shift
done

[[ -n $output_dir ]] || fail "--output-dir is required"
output_dir=$(case $output_dir in /*) printf '%s' "$output_dir";; *) printf '%s/%s' "$PWD" "$output_dir";; esac)
[[ ! -e $output_dir || -d $output_dir ]] || fail "output-dir is not a directory: $output_dir"
mkdir -p "$output_dir"
[[ -z $(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
  fail "output-dir must be empty: $output_dir"
require_positive samples "$sample_count"
safe_label "$a_label"
safe_label "$b_label"

for option in "${common_options[@]}" "${a_options[@]}" "${b_options[@]}"; do
  case $option in
    --output-dir=*|--artifact=*|--metadata=*|--sample-id=*)
      fail "per-run output/identity options are owned by this helper: $option" ;;
  esac
done

result_file="$output_dir/interleave-result.tsv"
printf 'order\tpair\tvariant\tlabel\tstatus\tsample_dir\n' >"$result_file"
order=0
for pair in $(seq 1 "$sample_count"); do
  for variant in A B; do
    ((order += 1))
    if [[ $variant == A ]]; then label=$a_label; variant_options=( "${a_options[@]}" );
    else label=$b_label; variant_options=( "${b_options[@]}" ); fi
    sample_id="$label-$(printf '%02d' "$pair")"
    sample_dir="$output_dir/$sample_id"
    args=( "$tps_test" "${common_options[@]}" "${variant_options[@]}"
      "--output-dir=$sample_dir" "--sample-id=$sample_id" )
    echo "interleave_order=$order pair=$pair variant=$variant sample_id=$sample_id"
    if [[ $dry_run == true ]]; then
      printf 'dry_run_argv='
      printf '%q ' "${args[@]}"
      printf '\n'
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$order" "$pair" "$variant" "$label" dry-run "$sample_dir" >>"$result_file"
      continue
    fi
    set +e
    "${args[@]}"
    sample_status=$?
    set -e
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$order" "$pair" "$variant" "$label" "$sample_status" "$sample_dir" >>"$result_file"
    if ((sample_status != 0)); then
      fail "sample $sample_id exited with $sample_status; evidence retained at $sample_dir"
    fi
  done
done
echo "interleave=completed samples=$((sample_count * 2)) result=$result_file"
