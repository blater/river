#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  tools/terminal-cast.sh record CAST [COMMAND ...]
  tools/terminal-cast.sh play CAST [ASCIINEMA_OPTIONS ...]
  tools/terminal-cast.sh gif CAST [GIF]
  tools/terminal-cast.sh video CAST [MP4]

Examples:
  tools/terminal-cast.sh record demo.cast
  tools/terminal-cast.sh record demo.cast ./gradlew test
  tools/terminal-cast.sh play demo.cast --speed 1.5
  tools/terminal-cast.sh gif demo.cast demo.gif
  tools/terminal-cast.sh video demo.cast demo.mp4

Recording keeps the .cast file as the source of truth. GIF rendering requires
agg; MP4 rendering requires agg and ffmpeg. MP4 uses H.264 4:4:4 at CRF 14 to
keep terminal text sharp; set VIDEO_CRF to a higher value for a smaller file.
The MP4 path uses a finite GIF intermediate, so direct window capture is better
when preserving arbitrary truecolor output matters.
EOF
}

die() {
  printf 'error: %s\n' "$1" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_cast() {
  [[ -f "$1" ]] || die "cast file not found: $1"
}

default_output() {
  local cast=$1
  local extension=$2
  printf '%s%s\n' "${cast%.cast}" "$extension"
}

[[ $# -gt 0 ]] || {
  usage
  exit 0
}

action=$1
shift

case "$action" in
  record)
    require_command asciinema
    [[ $# -gt 0 ]] || die 'record needs a .cast output path'
    cast=$1
    shift
    if [[ $# -gt 0 ]]; then
      asciinema rec --command "$*" "$cast"
    else
      asciinema rec "$cast"
    fi
    ;;

  play)
    require_command asciinema
    [[ $# -gt 0 ]] || die 'play needs a .cast input path'
    cast=$1
    shift
    require_cast "$cast"
    asciinema play "$@" "$cast"
    ;;

  gif)
    require_command agg
    [[ $# -gt 0 ]] || die 'gif needs a .cast input path'
    cast=$1
    shift
    require_cast "$cast"
    gif=${1:-$(default_output "$cast" .gif)}
    agg --no-loop "$cast" "$gif"
    ;;

  video)
    require_command agg
    require_command ffmpeg
    [[ $# -gt 0 ]] || die 'video needs a .cast input path'
    cast=$1
    shift
    require_cast "$cast"
    mp4=${1:-$(default_output "$cast" .mp4)}
    crf=${VIDEO_CRF:-14}
    temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/terminal-cast.XXXXXXXX")
    trap 'rm -rf "$temporary_dir"' EXIT
    gif="$temporary_dir/render.gif"
    agg --no-loop "$cast" "$gif"
    ffmpeg -hide_banner -loglevel error -y \
      -i "$gif" \
      -c:v libx264 -preset slow -crf "$crf" -pix_fmt yuv444p \
      -color_primaries bt709 -color_trc iec61966-2-1 -colorspace bt709 \
      -movflags +faststart -r 30 "$mp4"
    printf 'wrote %s\n' "$mp4"
    ;;

  -h|--help|help)
    usage
    ;;

  *)
    usage >&2
    die "unknown action: $action"
    ;;
esac
