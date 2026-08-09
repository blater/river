#!/bin/sh
set -eu

river_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
river_snapshot_root=$(mktemp -d "${TMPDIR:-/tmp}/river-archives.XXXXXX")

if [ -n "${RIVER_GRADLE_HOME:-}" ]; then
  GRADLE_USER_HOME=$RIVER_GRADLE_HOME
elif [ -z "${GRADLE_USER_HOME:-}" ]; then
  GRADLE_USER_HOME="$river_root/.river-gradle"
fi
export GRADLE_USER_HOME

cleanup() {
  rm -rf -- "$river_snapshot_root"
}
trap cleanup EXIT HUP INT TERM

snapshot_archives() {
  snapshot_name=$1
  snapshot_directory="$river_snapshot_root/$snapshot_name"
  archive_list="$river_snapshot_root/$snapshot_name.paths"
  mkdir -p "$snapshot_directory"
  : > "$archive_list"

  find "$river_root" -type f -path "$river_root/river-*/build/libs/*.jar" \
    | LC_ALL=C sort \
    | while IFS= read -r archive_path; do
        relative_path=${archive_path#"$river_root/"}
        mkdir -p "$snapshot_directory/$(dirname -- "$relative_path")"
        cp "$archive_path" "$snapshot_directory/$relative_path"
        printf '%s\n' "$relative_path" >> "$archive_list"
      done

  if [ ! -s "$archive_list" ]; then
    echo "no River archives were assembled" >&2
    exit 1
  fi

  expected_list="$river_root/build/reports/expected-archives.paths"
  expected_count_path="$river_root/build/reports/expected-archives.count"
  if [ ! -s "$expected_list" ] || [ ! -s "$expected_count_path" ]; then
    echo "expected archive manifest was not generated" >&2
    exit 1
  fi
  expected_count=$(tr -d '[:space:]' < "$expected_count_path")
  actual_count=$(wc -l < "$archive_list" | tr -d '[:space:]')
  if [ "$actual_count" != "$expected_count" ]; then
    echo "assembled $actual_count archives; expected $expected_count" >&2
    exit 1
  fi
  if ! cmp -s "$expected_list" "$archive_list"; then
    echo "assembled archive paths differ from the declared module archive set" >&2
    diff -u "$expected_list" "$archive_list" >&2 || true
    exit 1
  fi
}

"$river_root/gradlew" --no-daemon --no-build-cache --rerun-tasks \
  clean assembleRiverArchives
snapshot_archives first
"$river_root/gradlew" --no-daemon --no-build-cache --rerun-tasks \
  clean assembleRiverArchives
snapshot_archives second

if ! cmp -s "$river_snapshot_root/first.paths" "$river_snapshot_root/second.paths"; then
  echo "archive sets differ between clean builds" >&2
  diff -u "$river_snapshot_root/first.paths" "$river_snapshot_root/second.paths" >&2 || true
  exit 1
fi

report_directory="$river_root/build/reports"
report_path="$report_directory/reproducible-archives.tsv"
mkdir -p "$report_directory"
printf 'archive\tbytes\tposix_cksum\tcomparison\n' > "$report_path"

while IFS= read -r relative_path; do
  first_path="$river_snapshot_root/first/$relative_path"
  second_path="$river_snapshot_root/second/$relative_path"
  if ! cmp -s "$first_path" "$second_path"; then
    echo "$relative_path differs between clean builds" >&2
    exit 1
  fi
  set -- $(cksum "$second_path")
  printf '%s\t%s\t%s\tidentical\n' "$relative_path" "$2" "$1" >> "$report_path"
done < "$river_snapshot_root/first.paths"

echo "reproducible archive comparison passed: $report_path"
