#!/bin/sh
set -eu

river_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
river_snapshot_root=$(mktemp -d "${TMPDIR:-/tmp}/river-archives.XXXXXX")
river_gradle_home=${RIVER_GRADLE_HOME:-"$river_root/.river-gradle"}

export GRADLE_USER_HOME="$river_gradle_home"

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
}

"$river_root/gradlew" --no-daemon clean assembleRiverArchives
snapshot_archives first
"$river_root/gradlew" --no-daemon clean assembleRiverArchives
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
