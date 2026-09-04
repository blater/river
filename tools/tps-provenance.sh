#!/usr/bin/env bash

# Shared evidence primitives for tools/tps-test.sh. This file intentionally
# owns byte observation and host exclusion; workload semantics remain in Java.

provenance_sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

provenance_sha256_text() {
  printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
}

provenance_publish_file() {
  local source=$1
  local destination=$2
  local parent staged
  [[ -f $source && ! -e $destination ]] || return 1
  parent=$(dirname -- "$destination")
  [[ -d $parent ]] || return 1
  staged=$(mktemp "$parent/.river-provenance.XXXXXX") || return 1
  if ! cp -p -- "$source" "$staged"; then
    rm -f -- "$staged"
    return 1
  fi
  if ! ln "$staged" "$destination" 2>/dev/null; then
    rm -f -- "$staged"
    return 1
  fi
  rm -f -- "$staged"
}

provenance_run_logged() {
  local log=$1
  local argv_file=$2
  local command_file=$3
  shift 3
  printf '%s\0' "$@" >"$argv_file" || return 125
  printf '%q ' "$@" >"$command_file" || return 125
  printf '\n' >>"$command_file" || return 125
  "$@" >"$log" 2>&1
}

provenance_run_logged_marked() {
  local log=$1
  local argv_file=$2
  local command_file=$3
  local marker=$4
  local phase_file=$5
  shift 5
  PROVENANCE_LOGGED_COMMAND_STATUS=125
  PROVENANCE_LOGGED_WRAPPER_VALID=false
  printf '%s\0' "$@" >"$argv_file" || return 125
  printf '%q ' "$@" >"$command_file" || return 125
  printf '\n' >>"$command_file" || return 125
  : >"$marker" || return 125
  local status=0
  "$@" >"$log" 2>&1 || status=$?
  PROVENANCE_LOGGED_COMMAND_STATUS=$status
  local cleanup_valid=true
  printf 'workload\n' >"$phase_file" || cleanup_valid=false
  rm -f -- "$marker" || cleanup_valid=false
  [[ $cleanup_valid == true ]] || return 125
  PROVENANCE_LOGGED_WRAPPER_VALID=true
  return "$status"
}

provenance_stat_identity() {
  if stat -f '%d:%i:%z:%m' "$1" >/dev/null 2>&1; then
    stat -f '%d:%i:%z:%m' "$1"
  else
    stat -c '%d:%i:%s:%Y' "$1"
  fi
}

provenance_observe_file() {
  local path=$1
  local before first second after
  before=$(provenance_stat_identity "$path") || return 1
  first=$(provenance_sha256_file "$path") || return 1
  second=$(provenance_sha256_file "$path") || return 1
  after=$(provenance_stat_identity "$path") || return 1
  [[ $before == "$after" && $first == "$second" ]] || return 1
  printf '%s\n' "$first"
}

provenance_write_source_manifest() {
  local root=$1
  local destination=$2
  local staged="$destination.staged.$$"
  {
    git -C "$root" ls-files -s | sed 's/^/index\t/' || return 1
    (
      cd -- "$root" || exit 1
      git ls-files -z --cached --others --exclude-standard |
        perl -MDigest::SHA -e '
          use strict;
          use warnings;
          local $/ = "\0";
          my @paths = <STDIN>;
          @paths = sort @paths;
          for my $path (@paths) {
            chop $path if substr($path, -1) eq "\0";
            die "unsafe source path" if $path =~ /[\t\r\n]/;
            if (-l $path) {
              my $target = readlink($path);
              die "cannot read source symlink" unless defined $target;
              my $hash = Digest::SHA::sha256_hex($target);
              print "working\tsymlink\t$hash\t$path\n";
              next;
            }
            if (!-e $path) {
              print "working\tmissing\tmissing\t$path\n";
              next;
            }
            die "unsupported source entry" unless -f $path;
            my @before = stat($path);
            open my $file, "<:raw", $path or die "cannot read source file";
            my $digest = Digest::SHA->new(256);
            $digest->addfile($file);
            close $file or die "cannot close source file";
            my @after = stat($path);
            for my $index (0, 1, 7, 9, 10) {
              die "source changed while hashing"
                if $before[$index] != $after[$index];
            }
            print "working\tfile\t", $digest->hexdigest, "\t$path\n";
          }
        '
    ) || return 1
  } >"$staged" || {
    rm -f -- "$staged"
    return 1
  }
  mv -- "$staged" "$destination"
}

provenance_write_git_status() {
  git -C "$1" status --porcelain=v1 --untracked-files=all >"$2"
}

provenance_write_classpath_manifest() {
  local descriptor=$1
  local destination=$2
  local staged="$destination.staged.$$"
  [[ -f $descriptor ]] || return 1
  perl -MDigest::SHA -MFile::Find -e '
    use strict;
    use warnings;
    my ($descriptor, $output) = @ARGV;
    open my $input, "<:raw", $descriptor or die "cannot read descriptor";
    my @entries;
    while (my $line = <$input>) {
      $line =~ s/\n\z//;
      push @entries, substr($line, 10) if index($line, "classpath=") == 0;
    }
    close $input or die "cannot close descriptor";
    die "empty classpath" unless @entries;
    open my $manifest, ">:raw", $output or die "cannot write manifest";
    my $file_count = 0;
    my $index = 0;
    for my $entry (@entries) {
      ++$index;
      die "unsafe classpath entry" if $entry !~ m{^/} || $entry =~ /[:\t\r\n]/;
      die "classpath symlink" if -l $entry;
      if (-f $entry) {
        my $hash = hash_file($entry);
        printf $manifest "entry\t%06d\tfile\t%s\t%s\n", $index, $hash, $entry;
        ++$file_count;
        next;
      }
      die "missing classpath entry" unless -d $entry;
      printf $manifest "entry\t%06d\tdirectory\t-\t%s\n", $index, $entry;
      my (@files, @directories);
      find({
        no_chdir => 1,
        follow => 0,
        wanted => sub {
          die "classpath symlink" if -l $_;
          push @directories, [$_, identity($_)] if -d _;
          push @files, $_ if -f _;
        }
      }, $entry);
      for my $file (sort @files) {
        my $relative = substr($file, length($entry) + 1);
        die "unsafe classpath file" if $relative =~ /[\t\r\n]/;
        my $hash = hash_file($file);
        printf $manifest "file\t%06d\t%s\t%s\n", $index, $hash, $relative;
        ++$file_count;
      }
      for my $directory (@directories) {
        die "classpath directory changed while hashing"
          if $directory->[1] ne identity($directory->[0]);
      }
    }
    close $manifest or die "cannot close manifest";
    die "classpath has no files" unless $file_count;

    sub identity {
      my ($path) = @_;
      my @stat = stat($path);
      die "cannot stat classpath path" unless @stat;
      return join(":", @stat[0, 1, 7, 9, 10]);
    }

    sub hash_file {
      my ($path) = @_;
      my $before = identity($path);
      my @hashes;
      for (1..2) {
        open my $file, "<:raw", $path or die "cannot read classpath file";
        my $digest = Digest::SHA->new(256);
        $digest->addfile($file);
        close $file or die "cannot close classpath file";
        push @hashes, $digest->hexdigest;
      }
      die "classpath file changed while hashing"
        if $before ne identity($path) || $hashes[0] ne $hashes[1];
      return $hashes[0];
    }
  ' "$descriptor" "$staged" || {
    rm -f -- "$staged"
    return 1
  }
  mv -- "$staged" "$destination"
}

provenance_classpath_value() {
  local descriptor=$1
  local entries=()
  local line
  while IFS= read -r line; do
    [[ $line == classpath=* ]] && entries+=( "${line#classpath=}" )
  done <"$descriptor"
  ((${#entries[@]} > 0)) || return 1
  local IFS=:
  printf '%s\n' "${entries[*]}"
}

provenance_process_start() {
  ps -p "$1" -o lstart= 2>/dev/null |
    awk 'NF >= 5 {print $1 " " $2 " " $3 " " $4 " " $5}'
}

provenance_read_lease_owner() {
  local owner_file=$1
  local owner_lines owner_line_1 owner_line_2 owner_line_3 owner_line_4
  local schema pid started recorded_token
  owner_lines=$(awk 'END {print NR}' "$owner_file" 2>/dev/null || true)
  owner_line_1=$(sed -n '1p' "$owner_file" 2>/dev/null)
  owner_line_2=$(sed -n '2p' "$owner_file" 2>/dev/null)
  owner_line_3=$(sed -n '3p' "$owner_file" 2>/dev/null)
  owner_line_4=$(sed -n '4p' "$owner_file" 2>/dev/null)
  schema=${owner_line_1#schema=}
  pid=${owner_line_2#pid=}
  started=${owner_line_3#start=}
  recorded_token=${owner_line_4#token_sha256=}
  [[ $owner_lines == 4 && $schema == river-tps-host-lease-v1 &&
      $owner_line_1 == schema=* && $owner_line_2 == pid=* &&
      $owner_line_3 == start=* && $owner_line_4 == token_sha256=* &&
      $pid =~ ^[0-9]+$ && -n $started && $started != *$'\t'* &&
      $started != *$'\r'* && $recorded_token =~ ^[0-9a-f]{64}$ &&
      $recorded_token == "$(provenance_sha256_text "$pid:$started")" ]] || return 1
  printf '%s\t%s\t%s\n' "$pid" "$started" "$recorded_token"
}

provenance_acquire_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local owner_staged="$lease_dir/owner.staged"
  local stale_dir pid expected recorded_token actual token token_hash owner_pid owner_record
  PROVENANCE_LEASE_ACQUIRE_STATUS=owner_identity_unavailable
  owner_pid=${BASHPID:-$$}
  expected=$(provenance_process_start "$owner_pid")
  [[ -n $expected ]] || return 1
  token="$owner_pid:$expected"
  token_hash=$(provenance_sha256_text "$token") || {
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_token_failed
    return 1
  }
  if ! mkdir "$lease_dir" 2>/dev/null; then
    owner_record=$(provenance_read_lease_owner "$owner_file") || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=existing_owner_invalid
      return 1
    }
    IFS=$'\t' read -r pid expected recorded_token <<<"$owner_record"
    actual=
    actual=$(provenance_process_start "$pid" || true)
    if [[ -n $actual && $actual == "$expected" ]]; then
      PROVENANCE_LEASE_ACQUIRE_STATUS=lease_held
      return 1
    fi
    if [[ -z $actual ]] && kill -0 "$pid" 2>/dev/null; then
      PROVENANCE_LEASE_ACQUIRE_STATUS=existing_owner_identity_unavailable
      return 1
    fi
    stale_dir="$lease_dir.stale.$owner_pid.${SECONDS}"
    mv -- "$lease_dir" "$stale_dir" 2>/dev/null || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_reclaim_failed
      return 1
    }
    mkdir "$lease_dir" 2>/dev/null || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_reclaim_raced
      return 1
    }
    rm -rf -- "$stale_dir"
  fi
  if ! {
    printf 'schema=river-tps-host-lease-v1\n'
    printf 'pid=%s\n' "$owner_pid"
    printf 'start=%s\n' "${token#*:}"
    printf 'token_sha256=%s\n' "$token_hash"
  } >"$owner_staged"; then
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_write_failed
    return 1
  fi
  mv -- "$owner_staged" "$owner_file" 2>/dev/null || {
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_publish_failed
    return 1
  }
  PROVENANCE_LEASE_TOKEN_SHA256=$token_hash
  PROVENANCE_LEASE_ACQUIRE_STATUS=acquired
  export PROVENANCE_LEASE_TOKEN_SHA256
}

provenance_release_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local owner_record pid started recorded
  owner_record=$(provenance_read_lease_owner "$owner_file") || return 1
  IFS=$'\t' read -r pid started recorded <<<"$owner_record"
  [[ -n ${PROVENANCE_LEASE_TOKEN_SHA256:-} && $recorded == "$PROVENANCE_LEASE_TOKEN_SHA256" ]] || return 1
  rm -f -- "$owner_file"
  rmdir "$lease_dir"
}

provenance_capture_processes() {
  ps -axo pid=,ppid=,lstart=,command=
}

provenance_normalize_snapshot() {
  local raw_snapshot=$1
  local normalized_snapshot=$2
  local staged="$normalized_snapshot.staged.$$"
  local pid ppid started token command
  awk '
    {
      pid=$1; ppid=$2
      started=$3 " " $4 " " $5 " " $6 " " $7
      command=$0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+/, "", command)
      sub(/^[A-Z][a-z][a-z][[:space:]]+[A-Z][a-z][a-z][[:space:]]+[ 0-9][0-9][[:space:]]+[0-9:]+[[:space:]]+[0-9]{4}[[:space:]]+/, "", command)
      token="none"
      if (command ~ /org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon/) token="gradle_daemon"
      else if (command ~ /GradleWrapperMain|GradleWorkerMain|gradle[^ ]* (build|test|check|classes|compile|verify|clean|assemble)/) token="gradle_activity"
      else if (command ~ /TpccAcceptanceMain|TpccServerMain|tools\/tps-test\.sh|tools\/tps-p4\.sh|tools\/tps-interleave\.sh/) token="river_workload"
      else if (command ~ /river-harness\/benchmark|[\/]benchmark run (river|mariadb|postgres)/) token="database_harness"
      else if (command ~ /async-profiler|jfr (recording|start)|tools\/trace-update\.sh|tools\/jfr-flamegraph\.sh/) token="profile"
      else if (command ~ /org\.junit|JUnitStarter|[\/]river[^ ]*\/build\//) token="river_build_or_test"
      else if (command ~ /pgbench|mysqlslap|sysbench.*(mysql|pgsql)|tpcc.*(run|benchmark)/) token="database_workload"
      print pid "\t" ppid "\t" started "\t" token "\t" command
    }
  ' "$raw_snapshot" | while IFS=$'\t' read -r pid ppid started token command; do
    printf '%s\t%s\t%s\t%s\n' "$pid" "$ppid" "$started" "$token"
  done >"$staged" || {
    rm -f -- "$staged"
    return 1
  }
  mv -- "$staged" "$normalized_snapshot"
}

provenance_gradle_daemon_state() {
  local pid=$1
  local output=$2
  local timeout_seconds=${3:-2}
  command -v jcmd >/dev/null 2>&1 || return 1
  provenance_run_bounded "$timeout_seconds" "$output" \
    jcmd "$pid" Thread.print -l || return 1
  if grep -E 'org\.gradle\.launcher\.exec\.(ExecuteBuild|ChainingBuildActionRunner)|org\.gradle\.internal\.buildtree' \
      "$output" >/dev/null; then
    printf 'busy\n'
  else
    printf 'idle\n'
  fi
}

provenance_gradle_daemon_home() {
  local pid=$1
  local output=$2
  local timeout_seconds=${3:-2}
  command -v jcmd >/dev/null 2>&1 || return 1
  provenance_run_bounded "$timeout_seconds" "$output" \
    jcmd "$pid" VM.system_properties || return 1
  sed -n 's/^gradle\.user\.home=//p' "$output" | head -1
}

provenance_run_bounded() {
  local timeout_seconds=$1
  local output=$2
  shift 2
  local child_pid started=$SECONDS status
  "$@" >"$output" 2>/dev/null &
  child_pid=$!
  while kill -0 "$child_pid" 2>/dev/null; do
    if ((SECONDS - started >= timeout_seconds)); then
      kill "$child_pid" 2>/dev/null || true
      wait "$child_pid" 2>/dev/null || true
      return 124
    fi
    sleep 0.05
  done
  if wait "$child_pid"; then
    status=0
  else
    status=$?
  fi
  return "$status"
}

provenance_evidence_bytes() {
  local total=0 file bytes
  for file in "$@"; do
    [[ -f $file ]] || continue
    bytes=$(wc -c <"$file") || return 1
    total=$((total + bytes))
  done
  printf '%s\n' "$total"
}

provenance_append_with_budget() {
  local maximum_bytes=$1
  local destination=$2
  local addition=$3
  shift 3
  local current addition_bytes
  current=$(provenance_evidence_bytes "$@") || return 1
  addition_bytes=$(wc -c <"$addition") || return 1
  ((current + addition_bytes <= maximum_bytes)) || return 2
  cat "$addition" >>"$destination"
}

provenance_validate_gradle_daemons() {
  local descriptor=$1
  local provisional=$2
  local expected pid started current
  expected=$(sed -n 's/^gradle\.process\.pid=//p' "$descriptor" | head -1)
  [[ $expected =~ ^[0-9]+$ ]] || return 1
  [[ -f $provisional ]] || return 0
  while IFS=$'\t' read -r pid started; do
    [[ -n $pid ]] || continue
    [[ $pid == "$expected" && -n $started ]] || return 1
    current=$(provenance_process_start "$pid" || true)
    [[ $current == "$started" ]] || return 1
  done < <(LC_ALL=C sort -u "$provisional")
}

provenance_classify_snapshot() {
  local snapshot=$1
  local self_pid=$2
  awk -F '\t' -v self="$self_pid" '
    {
      pid=$1; parent=$2; ppid[pid]=parent; token[pid]=$4
      order[++count]=pid
    }
    function owned(pid, cursor, steps) {
      cursor=pid
      for (steps=0; steps<=count; steps++) {
        if (cursor==self) return 1
        if (!(cursor in ppid) || ppid[cursor]==cursor) return 0
        cursor=ppid[cursor]
      }
      return 0
    }
    function ancestor_of_self(pid, cursor, steps) {
      cursor=self
      for (steps=0; steps<=count; steps++) {
        if (cursor==pid) return 1
        if (!(cursor in ppid) || ppid[cursor]==cursor) return 0
        cursor=ppid[cursor]
      }
      return 0
    }
    END {
      for (i=1; i<=count; i++) {
        pid=order[i]; kind=token[pid]
        if (owned(pid) || ancestor_of_self(pid)) continue
        if (kind=="gradle_daemon") {
          print "allowed_idle_gradle_daemon\t" pid
          continue
        }
        if (kind!="none") print "violation\t" kind "\t" pid
      }
    }
  ' "$snapshot"
}

provenance_monitor_host() {
  local evidence_dir=$1
  local self_pid=$2
  local stop_file=$3
  local interval=${4:-1}
  local owned_gradle_home=${5:-}
  local owned_build_marker=${6:-}
  local phase_file=${7:-}
  local maximum_bytes=${8:-16777216}
  local inspection_timeout_seconds=${9:-2}
  local provisional_daemons=${10:-$evidence_dir/host-provisional-daemons.tsv}
  local retained_maximum=$((maximum_bytes - 256))
  local sequence=0 raw_snapshot snapshot raw_classification classification now label pid state daemon_home
  local snapshot_start live_start_before live_start_after phase observation observation_finished provisional_addition
  local retained_files=( "$evidence_dir/host-observations.tsv"
    "$evidence_dir/host-processes.tsv" "$evidence_dir/host-classifications.tsv"
    "$evidence_dir/host-violations.tsv" "$provisional_daemons" )
  ((retained_maximum > 0)) || return 1
  if [[ -s $evidence_dir/host-observations.tsv ]]; then
    sequence=$(tail -1 "$evidence_dir/host-observations.tsv" | cut -f1)
    [[ $sequence =~ ^[0-9]+$ ]] || return 1
  fi
  while [[ ! -e $stop_file ]]; do
    sequence=$((sequence + 1))
    now=$(date +%s)
    phase=unknown
    [[ -f $phase_file ]] && phase=$(tr -d '\r\n' <"$phase_file")
    case $phase in prebuild|build|workload|publication) ;; *) phase=unknown ;; esac
    raw_snapshot="$evidence_dir/host-processes-raw.$(printf '%06d' "$sequence").txt"
    snapshot="$evidence_dir/host-processes.$(printf '%06d' "$sequence").tsv"
    raw_classification="$evidence_dir/host-classification-raw.$(printf '%06d' "$sequence").tsv"
    classification="$evidence_dir/host-classification.$(printf '%06d' "$sequence").tsv"
    observation="$evidence_dir/host-observation.$(printf '%06d' "$sequence").tsv"
    provisional_addition="$evidence_dir/host-provisional.$(printf '%06d' "$sequence").tsv"
    : >"$provisional_addition"
    if ! provenance_capture_processes >"$raw_snapshot"; then
      printf 'violation\tprocess_snapshot_failed\t%s\tphase=%s\n' "$now" "$phase" \
        >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$raw_snapshot" "$provisional_addition"
      return 1
    fi
    if ! provenance_normalize_snapshot "$raw_snapshot" "$snapshot"; then
      printf 'violation\tprocess_normalization_failed\t%s\tphase=%s\n' "$now" "$phase" \
        >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$raw_snapshot" "$provisional_addition"
      return 1
    fi
    rm -f -- "$raw_snapshot"
    provenance_classify_snapshot "$snapshot" "$self_pid" >"$raw_classification" || {
      printf 'violation\tprocess_classification_failed\t%s\tphase=%s\n' "$now" "$phase" \
        >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$snapshot" "$raw_classification" "$provisional_addition"
      return 1
    }
    : >"$classification"
    while IFS=$'\t' read -r label pid; do
      if [[ $label != allowed_idle_gradle_daemon ]]; then
        printf '%s\t%s\n' "$label" "$pid" >>"$classification"
        continue
      fi
      snapshot_start=$(awk -F '\t' -v pid="$pid" '$1 == pid {print $3}' "$snapshot")
      if [[ $phase == workload || $phase == publication ]]; then
        printf 'allowed_cooperative_gradle_daemon\t%s\tstate=not_inspected\tphase=%s\n' \
          "$pid" "$phase" >>"$classification"
        continue
      fi
      live_start_before=$(provenance_process_start "$pid" || true)
      state=$(provenance_gradle_daemon_state "$pid" \
        "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt" \
        "$inspection_timeout_seconds") || state=unknown
      live_start_after=$(provenance_process_start "$pid" || true)
      if [[ -z $snapshot_start || $live_start_before != "$snapshot_start" ||
          $live_start_after != "$snapshot_start" ]]; then
        printf 'violation\tprocess_identity_race\t%s\n' "$pid" >>"$classification"
        rm -f -- "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt"
        continue
      fi
      if [[ $state == busy && -e $owned_build_marker && -n $owned_gradle_home ]]; then
        daemon_home=$(provenance_gradle_daemon_home "$pid" \
          "$evidence_dir/gradle-daemon-properties.$sequence.$pid.txt" \
          "$inspection_timeout_seconds") || daemon_home=unknown
        if [[ $daemon_home == "$owned_gradle_home" ]]; then
          printf '%s\t%s\n' "$pid" "$snapshot_start" >>"$provisional_addition" || return 1
          printf 'provisional_owned_gradle_daemon\t%s\tstate=busy\thome_match=true\n' "$pid" >>"$classification"
          rm -f -- "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt" \
            "$evidence_dir/gradle-daemon-properties.$sequence.$pid.txt"
          continue
        fi
      fi
      case $state in
        idle) printf 'allowed_idle_gradle_daemon\t%s\tstate=idle\n' "$pid" >>"$classification" ;;
        busy) printf 'violation\tbusy_gradle_daemon\t%s\thome_match=false\n' "$pid" >>"$classification" ;;
        *) printf 'violation\tuninspectable_gradle_daemon\t%s\n' "$pid" >>"$classification" ;;
      esac
      rm -f -- "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt" \
        "$evidence_dir/gradle-daemon-properties.$sequence.$pid.txt"
    done <"$raw_classification"
    rm -f -- "$raw_classification"
    observation_finished=$(date +%s)
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$sequence" "$now" "$phase" \
      "$((observation_finished - now))" "$(provenance_sha256_file "$snapshot")" \
      "$(provenance_sha256_file "$classification")" >"$observation"
    local additions=( "$observation" "$snapshot" "$classification" "$provisional_addition" )
    local destinations=( "$evidence_dir/host-observations.tsv"
      "$evidence_dir/host-processes.tsv" "$evidence_dir/host-classifications.tsv"
      "$provisional_daemons" )
    local addition destination prefixed index append_status
    for ((index = 0; index < ${#additions[@]}; index++)); do
      addition=${additions[$index]}
      destination=${destinations[$index]}
      prefixed="$addition.prefixed"
      if [[ $addition == "$observation" || $addition == "$provisional_addition" ]]; then
        cp -- "$addition" "$prefixed"
      else
        awk -v sequence="$sequence" '{print sequence "\t" $0}' "$addition" >"$prefixed"
      fi
      append_status=0
      provenance_append_with_budget "$retained_maximum" "$destination" "$prefixed" \
        "${retained_files[@]}" || append_status=$?
      if ((append_status != 0)); then
        printf 'violation\thost_evidence_budget_exhausted\t%s\tphase=%s\tmaximum_bytes=%s\n' \
          "$now" "$phase" "$maximum_bytes" >>"$evidence_dir/host-violations.tsv"
        rm -f -- "$raw_snapshot" "$snapshot" "$raw_classification" "$classification" \
          "$observation" "$provisional_addition" "$prefixed"
        return 1
      fi
      rm -f -- "$prefixed"
    done
    awk -F '\t' '$1 == "violation" {print}' "$classification" \
      >"$classification.violations"
    if [[ -s $classification.violations ]]; then
      append_status=0
      provenance_append_with_budget "$retained_maximum" "$evidence_dir/host-violations.tsv" \
        "$classification.violations" "${retained_files[@]}" || append_status=$?
      if ((append_status != 0)); then
        printf 'violation\thost_evidence_budget_exhausted\t%s\tphase=%s\tmaximum_bytes=%s\n' \
          "$now" "$phase" "$maximum_bytes" >>"$evidence_dir/host-violations.tsv"
        rm -f -- "$snapshot" "$classification" "$observation" "$provisional_addition" \
          "$classification.violations"
        return 1
      fi
    fi
    rm -f -- "$snapshot" "$classification" "$observation" "$provisional_addition" \
      "$classification.violations"
    sleep "$interval"
  done
}
