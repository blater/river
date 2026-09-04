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
  local staged="$destination.staged.$$"
  [[ -f $source && ! -e $destination ]] || return 1
  cp -p -- "$source" "$staged" || return 1
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

provenance_acquire_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local stale_dir pid expected actual token
  token="$$:$(provenance_process_start "$$")"
  if ! mkdir "$lease_dir" 2>/dev/null; then
    pid=$(sed -n 's/^pid=//p' "$owner_file" 2>/dev/null | head -1)
    expected=$(sed -n 's/^start=//p' "$owner_file" 2>/dev/null | head -1)
    actual=
    [[ $pid =~ ^[0-9]+$ ]] && actual=$(provenance_process_start "$pid" || true)
    if [[ -n $actual && $actual == "$expected" ]]; then
      return 1
    fi
    stale_dir="$lease_dir.stale.$$.${SECONDS}"
    mv -- "$lease_dir" "$stale_dir" 2>/dev/null || return 1
    mkdir "$lease_dir" 2>/dev/null || return 1
    rm -rf -- "$stale_dir"
  fi
  {
    printf 'schema=river-tps-host-lease-v1\n'
    printf 'pid=%s\n' "$$"
    printf 'start=%s\n' "${token#*:}"
    printf 'token_sha256=%s\n' "$(provenance_sha256_text "$token")"
  } >"$owner_file"
  PROVENANCE_LEASE_TOKEN_SHA256=$(provenance_sha256_text "$token")
  export PROVENANCE_LEASE_TOKEN_SHA256
}

provenance_release_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local recorded
  recorded=$(sed -n 's/^token_sha256=//p' "$owner_file" 2>/dev/null | head -1)
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
  command -v jcmd >/dev/null 2>&1 || return 1
  jcmd "$pid" Thread.print -l >"$output" 2>/dev/null || return 1
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
  command -v jcmd >/dev/null 2>&1 || return 1
  jcmd "$pid" VM.system_properties >"$output" 2>/dev/null || return 1
  sed -n 's/^gradle\.user\.home=//p' "$output" | head -1
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
  local sequence=0 raw_snapshot snapshot raw_classification classification now line label pid state daemon_home
  local snapshot_start live_start_before live_start_after
  while [[ ! -e $stop_file ]]; do
    sequence=$((sequence + 1))
    now=$(date +%s)
    raw_snapshot="$evidence_dir/host-processes-raw.$(printf '%06d' "$sequence").txt"
    snapshot="$evidence_dir/host-processes.$(printf '%06d' "$sequence").tsv"
    raw_classification="$evidence_dir/host-classification-raw.$(printf '%06d' "$sequence").tsv"
    classification="$evidence_dir/host-classification.$(printf '%06d' "$sequence").tsv"
    if ! provenance_capture_processes >"$raw_snapshot"; then
      printf 'process_snapshot_failed\t%s\n' "$now" >>"$evidence_dir/host-violations.tsv"
      return 1
    fi
    if ! provenance_normalize_snapshot "$raw_snapshot" "$snapshot"; then
      printf 'process_normalization_failed\t%s\n' "$now" >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$raw_snapshot"
      return 1
    fi
    rm -f -- "$raw_snapshot"
    provenance_classify_snapshot "$snapshot" "$self_pid" >"$raw_classification" || {
      printf 'process_classification_failed\t%s\n' "$now" >>"$evidence_dir/host-violations.tsv"
      return 1
    }
    : >"$classification"
    while IFS=$'\t' read -r label pid; do
      if [[ $label != allowed_idle_gradle_daemon ]]; then
        printf '%s\t%s\n' "$label" "$pid" >>"$classification"
        continue
      fi
      snapshot_start=$(awk -F '\t' -v pid="$pid" '$1 == pid {print $3}' "$snapshot")
      live_start_before=$(provenance_process_start "$pid" || true)
      state=$(provenance_gradle_daemon_state "$pid" \
        "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt") || state=unknown
      live_start_after=$(provenance_process_start "$pid" || true)
      if [[ -z $snapshot_start || $live_start_before != "$snapshot_start" ||
          $live_start_after != "$snapshot_start" ]]; then
        printf 'violation\tprocess_identity_race\t%s\n' "$pid" >>"$classification"
        rm -f -- "$evidence_dir/gradle-daemon-thread.$sequence.$pid.txt"
        continue
      fi
      if [[ $state == busy && -e $owned_build_marker && -n $owned_gradle_home ]]; then
        daemon_home=$(provenance_gradle_daemon_home "$pid" \
          "$evidence_dir/gradle-daemon-properties.$sequence.$pid.txt") || daemon_home=unknown
        if [[ $daemon_home == "$owned_gradle_home" ]]; then
          printf 'allowed_owned_gradle_daemon\t%s\tstate=busy\thome_match=true\n' "$pid" >>"$classification"
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
    {
      printf '%s\t%s\t%s\t' "$sequence" "$now" \
        "$(provenance_sha256_file "$snapshot")"
      printf '%s\n' "$(provenance_sha256_file "$classification")"
    } >>"$evidence_dir/host-observations.tsv"
    awk -F '\t' '$1 == "violation" {print}' "$classification" >>"$evidence_dir/host-violations.tsv"
    awk -v sequence="$sequence" '{print sequence "\t" $0}' "$snapshot" \
      >>"$evidence_dir/host-processes.tsv"
    awk -v sequence="$sequence" '{print sequence "\t" $0}' "$classification" \
      >>"$evidence_dir/host-classifications.tsv"
    sleep "$interval"
  done
}
