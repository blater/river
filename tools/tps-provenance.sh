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

provenance_canonical_hash() {
  local domain=$1
  shift
  {
    printf '%s\n' "$domain"
    printf '%s\n' "$@"
  } | shasum -a 256 | awk '{print $1}'
}

provenance_random_hex() {
  perl -e '
    use strict;
    use warnings;
    open my $random, "<:raw", "/dev/urandom" or exit 1;
    my $bytes = "";
    read($random, $bytes, 32) == 32 or exit 1;
    close $random or exit 1;
    print unpack("H*", $bytes), "\n";
  '
}

provenance_owner_identity_hash() {
  provenance_canonical_hash river-tps-owner-v2 "$1" "$2" "$3"
}

provenance_terminal_commitment_hash() {
  provenance_canonical_hash river-tps-terminal-commitment-v1 "$1" "$2" "$3"
}

provenance_capture_bounded() {
  local timeout_seconds=$1
  local maximum_bytes=$2
  shift 2
  [[ $timeout_seconds =~ ^[0-9]+$ && $timeout_seconds -gt 0 &&
      $maximum_bytes =~ ^[0-9]+$ && $maximum_bytes -gt 0 ]] || return 126
  perl -MIPC::Open3 -MIO::Select -MTime::HiRes=time,sleep -MSymbol=gensym \
    -e '
      use strict;
      use warnings;
      use POSIX qw(WNOHANG);
      my ($timeout, $maximum, @command) = @ARGV;
      my $error = gensym;
      my ($input, $output);
      my $pid = eval { open3($input, $output, $error, @command) };
      exit 126 unless defined $pid;
      close $input;
      my $selector = IO::Select->new($output, $error);
      my $started = time();
      my $stdout = "";
      my $total = 0;
      my $failure = 0;
      my $reaped = 0;
      my $status = 0;
      while (!$reaped || $selector->count) {
        my $remaining = $timeout - (time() - $started);
        if ($remaining <= 0) { $failure = 124; last; }
        my @ready = $selector->can_read($remaining < 0.05 ? $remaining : 0.05);
        for my $handle (@ready) {
          my $chunk = "";
          my $count = sysread($handle, $chunk, 8192);
          if (!defined $count) { $failure = 126; last; }
          if ($count == 0) { $selector->remove($handle); close $handle; next; }
          $total += $count;
          if ($total > $maximum) { $failure = 125; last; }
          $stdout .= $chunk if fileno($handle) == fileno($output);
        }
        last if $failure;
        if (!$reaped) {
          my $waited = waitpid($pid, WNOHANG);
          if ($waited == $pid) { $status = $?; $reaped = 1; }
          elsif ($waited == -1) { $failure = 126; last; }
        }
      }
      if ($failure) {
        if (!$reaped) {
          kill 15, $pid;
          for (1..10) {
            my $waited = waitpid($pid, WNOHANG);
            if ($waited == $pid || $waited == -1) { $reaped = 1; last; }
            sleep 0.01;
          }
          if (!$reaped) { kill 9, $pid; waitpid($pid, 0); }
        }
        exit $failure;
      }
      exit 126 if $status == -1;
      exit 128 + ($status & 127) if $status & 127;
      my $exit = $status >> 8;
      exit $exit if $exit;
      print $stdout;
    ' "$timeout_seconds" "$maximum_bytes" "$@"
}

provenance_process_start() {
  local pid=$1
  local timeout_seconds=${2:-2}
  local maximum_bytes=${3:-4096}
  local raw
  raw=$(provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    ps -p "$pid" -o lstart=) || return 1
  printf '%s\n' "$raw" |
    awk 'NF >= 5 {print $1 " " $2 " " $3 " " $4 " " $5}'
}

provenance_path_identity() {
  if stat -f '%d:%i:%HT:%l' "$1" >/dev/null 2>&1; then
    stat -f '%d:%i:%HT:%l' "$1"
  else
    stat -c '%d:%i:%F:%h' "$1"
  fi
}

provenance_read_lease_owner() {
  local owner_file=$1
  local lines line1 line2 line3 line4 line5 line6
  local run_id pid started identity commitment
  [[ -f $owner_file && ! -L $owner_file ]] || return 1
  lines=$(awk 'END {print NR}' "$owner_file" 2>/dev/null || true)
  line1=$(sed -n '1p' "$owner_file" 2>/dev/null)
  line2=$(sed -n '2p' "$owner_file" 2>/dev/null)
  line3=$(sed -n '3p' "$owner_file" 2>/dev/null)
  line4=$(sed -n '4p' "$owner_file" 2>/dev/null)
  line5=$(sed -n '5p' "$owner_file" 2>/dev/null)
  line6=$(sed -n '6p' "$owner_file" 2>/dev/null)
  run_id=${line2#evidence_run_id=}
  pid=${line3#pid=}
  started=${line4#start=}
  identity=${line5#owner_identity_sha256=}
  commitment=${line6#terminal_commitment_sha256=}
  [[ $lines == 6 && $line1 == schema=river-tps-host-lease-v2 &&
      $line2 == evidence_run_id=* && $line3 == pid=* && $line4 == start=* &&
      $line5 == owner_identity_sha256=* && $line6 == terminal_commitment_sha256=* &&
      $run_id =~ ^[0-9a-f]{64}$ && $pid =~ ^[0-9]+$ && -n $started &&
      $started != *$'\t'* && $started != *$'\r'* && $started != *$'\n'* &&
      $identity =~ ^[0-9a-f]{64}$ && $commitment =~ ^[0-9a-f]{64}$ &&
      $identity == "$(provenance_owner_identity_hash "$run_id" "$pid" "$started")" ]] || return 1
  printf '%s\t%s\t%s\t%s\t%s\n' "$run_id" "$pid" "$started" "$identity" "$commitment"
}

provenance_lease_exact_owner() {
  local lease_dir=$1
  local owner="$lease_dir/owner"
  [[ -d $lease_dir && ! -L $lease_dir && -f $owner && ! -L $owner ]] || return 1
  [[ -z $(find "$lease_dir" -mindepth 1 -maxdepth 1 ! -name owner -print -quit 2>/dev/null) ]] || return 1
  [[ $(find "$lease_dir" -mindepth 1 -maxdepth 1 -name owner -print | wc -l | tr -d ' ') == 1 ]] || return 1
  case $(provenance_path_identity "$owner") in *:1) ;; *) return 1 ;; esac
}

provenance_acquire_lease() {
  local lease_dir=$1
  local run_id=$2
  local nonce=$3
  local owner_file="$lease_dir/owner"
  local owner_staged owner_record old_run pid expected identity old_commitment actual commitment
  local owner_pid owner_start owner_identity directory_before owner_before owner_hash_before
  PROVENANCE_LEASE_ACQUIRE_STATUS=owner_identity_unavailable
  owner_pid=${BASHPID:-$$}
  owner_start=$(provenance_process_start "$owner_pid")
  [[ -n $owner_start ]] || return 1
  owner_identity=$(provenance_owner_identity_hash "$run_id" "$owner_pid" "$owner_start") || return 1
  commitment=$(provenance_terminal_commitment_hash "$run_id" "$owner_identity" "$nonce") || return 1
  if ! mkdir "$lease_dir" 2>/dev/null; then
    provenance_lease_exact_owner "$lease_dir" || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=existing_owner_invalid
      return 1
    }
    owner_record=$(provenance_read_lease_owner "$owner_file") || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=existing_owner_invalid
      return 1
    }
    IFS=$'\t' read -r old_run pid expected identity old_commitment <<<"$owner_record"
    actual=$(provenance_process_start "$pid" || true)
    if [[ -n $actual && $actual == "$expected" ]]; then
      PROVENANCE_LEASE_ACQUIRE_STATUS=lease_held
      return 1
    fi
    if [[ -z $actual ]] && kill -0 "$pid" 2>/dev/null; then
      PROVENANCE_LEASE_ACQUIRE_STATUS=existing_owner_identity_unavailable
      return 1
    fi
    directory_before=$(provenance_path_identity "$lease_dir") || return 1
    owner_before=$(provenance_path_identity "$owner_file") || return 1
    owner_hash_before=$(provenance_sha256_file "$owner_file") || return 1
    provenance_lease_exact_owner "$lease_dir" &&
      [[ $(provenance_path_identity "$lease_dir") == "$directory_before" &&
        $(provenance_path_identity "$owner_file") == "$owner_before" &&
        $(provenance_sha256_file "$owner_file") == "$owner_hash_before" ]] || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_reclaim_raced
      return 1
    }
    rm -- "$owner_file" 2>/dev/null || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_owner_unlink_failed
      return 1
    }
    rmdir "$lease_dir" 2>/dev/null || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_directory_remove_failed
      return 1
    }
    mkdir "$lease_dir" 2>/dev/null || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_reclaim_raced
      return 1
    }
  fi
  owner_staged=$(mktemp "$(dirname -- "$lease_dir")/.river-tps-owner.XXXXXX") || {
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_write_failed
    return 1
  }
  if ! {
    printf 'schema=river-tps-host-lease-v2\n'
    printf 'evidence_run_id=%s\n' "$run_id"
    printf 'pid=%s\n' "$owner_pid"
    printf 'start=%s\n' "$owner_start"
    printf 'owner_identity_sha256=%s\n' "$owner_identity"
    printf 'terminal_commitment_sha256=%s\n' "$commitment"
  } >"$owner_staged"; then
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_write_failed
    return 1
  fi
  if ! ln "$owner_staged" "$owner_file" 2>/dev/null; then
    rm -f -- "$owner_staged"
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_publish_failed
    return 1
  fi
  rm -f -- "$owner_staged" || {
    PROVENANCE_LEASE_ACQUIRE_STATUS=owner_stage_cleanup_failed
    return 1
  }
  PROVENANCE_LEASE_OWNER_IDENTITY_SHA256=$owner_identity
  PROVENANCE_LEASE_OWNER_PID=$owner_pid
  PROVENANCE_LEASE_OWNER_START=$owner_start
  PROVENANCE_TERMINAL_COMMITMENT_SHA256=$commitment
  PROVENANCE_LEASE_ACQUIRE_STATUS=acquired
  export PROVENANCE_LEASE_OWNER_IDENTITY_SHA256 PROVENANCE_LEASE_OWNER_PID \
    PROVENANCE_LEASE_OWNER_START PROVENANCE_TERMINAL_COMMITMENT_SHA256
}

provenance_release_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local owner_record run_id pid started identity commitment directory_before owner_before owner_hash
  provenance_lease_exact_owner "$lease_dir" || return 1
  owner_record=$(provenance_read_lease_owner "$owner_file") || return 1
  IFS=$'\t' read -r run_id pid started identity commitment <<<"$owner_record"
  [[ -n ${PROVENANCE_LEASE_OWNER_IDENTITY_SHA256:-} &&
      $identity == "$PROVENANCE_LEASE_OWNER_IDENTITY_SHA256" ]] || return 1
  directory_before=$(provenance_path_identity "$lease_dir") || return 1
  owner_before=$(provenance_path_identity "$owner_file") || return 1
  owner_hash=$(provenance_sha256_file "$owner_file") || return 1
  provenance_lease_exact_owner "$lease_dir" &&
    [[ $(provenance_path_identity "$lease_dir") == "$directory_before" &&
      $(provenance_path_identity "$owner_file") == "$owner_before" &&
      $(provenance_sha256_file "$owner_file") == "$owner_hash" ]] || return 1
  rm -- "$owner_file" || return 1
  rmdir "$lease_dir"
}

provenance_property_once() {
  local key=$1
  local file=$2
  [[ -f $file ]] || return 1
  awk -v prefix="$key=" '
    index($0, prefix) == 1 { count++; value=substr($0, length(prefix) + 1) }
    END { if (count != 1 || value == "") exit 1; print value }
  ' "$file"
}

provenance_write_terminal_receipt() {
  local destination=$1
  local result=$2
  local status=$3
  local evidence_run_id=$4
  local artifact_run_id=$5
  local metadata_sha256=$6
  local owner_pid=$7
  local owner_start=$8
  local owner_identity=$9
  local nonce=${10}
  local commitment=${11}
  local evidence_dir=${12}
  local release_outcome=${13}
  local released_epoch=${14}
  {
    printf 'schema=river-tps-terminal-v1\n'
    printf 'terminal.result=%s\n' "$result"
    printf 'terminal.status=%s\n' "$status"
    printf 'evidence.run_id=%s\n' "$evidence_run_id"
    printf 'artifact.run_id=%s\n' "$artifact_run_id"
    printf 'metadata.sha256=%s\n' "$metadata_sha256"
    printf 'lease.owner_pid=%s\n' "$owner_pid"
    printf 'lease.owner_start=%s\n' "$owner_start"
    printf 'lease.owner_identity_sha256=%s\n' "$owner_identity"
    printf 'terminal.nonce=%s\n' "$nonce"
    printf 'terminal.commitment_sha256=%s\n' "$commitment"
    printf 'host.observations_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-observations.tsv")"
    printf 'host.processes_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-processes.tsv")"
    printf 'host.classifications_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-classifications.tsv")"
    printf 'host.violations_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-violations.tsv")"
    printf 'host.provisional_daemons_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-provisional-daemons.tsv")"
    printf 'provenance.checkpoints_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/provenance-checkpoints.tsv")"
    printf 'lease.release_outcome=%s\n' "$release_outcome"
    printf 'lease.released_epoch=%s\n' "$released_epoch"
  } >"$destination"
}

provenance_validate_terminal_receipt() {
  local metadata=$1
  local artifact=$2
  local receipt=$3
  local evidence_dir=$4
  local expected_result=${5:-success}
  local values schema result status run_id artifact_run_id metadata_hash owner_pid owner_start
  local owner_identity nonce commitment observations_hash processes_hash classifications_hash
  local violations_hash provisional_hash checkpoints_hash release_outcome released_epoch metadata_artifact
  [[ -f $metadata && -f $receipt && -d $evidence_dir ]] || return 1
  values=$(awk '
    BEGIN {
      keys[1]="schema"; keys[2]="terminal.result"; keys[3]="terminal.status";
      keys[4]="evidence.run_id"; keys[5]="artifact.run_id"; keys[6]="metadata.sha256";
      keys[7]="lease.owner_pid"; keys[8]="lease.owner_start";
      keys[9]="lease.owner_identity_sha256"; keys[10]="terminal.nonce";
      keys[11]="terminal.commitment_sha256"; keys[12]="host.observations_sha256";
      keys[13]="host.processes_sha256"; keys[14]="host.classifications_sha256";
      keys[15]="host.violations_sha256"; keys[16]="host.provisional_daemons_sha256";
      keys[17]="provenance.checkpoints_sha256"; keys[18]="lease.release_outcome";
      keys[19]="lease.released_epoch";
    }
    {
      separator=index($0, "=");
      if (separator < 2 || substr($0, 1, separator - 1) != keys[NR]) exit 1;
      value=substr($0, separator + 1);
      if (value == "" || value ~ /[\t\r]/) exit 1;
      values[NR]=value;
    }
    END {
      if (NR != 19) exit 1;
      for (i=1; i<=19; i++) printf "%s%s", values[i], (i == 19 ? "\n" : "\t");
    }
  ' "$receipt") || return 1
  IFS=$'\t' read -r schema result status run_id artifact_run_id metadata_hash owner_pid \
    owner_start owner_identity nonce commitment observations_hash processes_hash \
    classifications_hash violations_hash provisional_hash checkpoints_hash release_outcome released_epoch \
    <<<"$values"
  [[ $schema == river-tps-terminal-v1 && $result == "$expected_result" &&
      $run_id =~ ^[0-9a-f]{64}$ && $metadata_hash =~ ^[0-9a-f]{64}$ &&
      $owner_pid =~ ^[0-9]+$ && $owner_identity =~ ^[0-9a-f]{64}$ &&
      $nonce =~ ^[0-9a-f]{64}$ && $commitment =~ ^[0-9a-f]{64}$ &&
      $released_epoch =~ ^[0-9]+$ ]] || return 1
  [[ $(provenance_property_once tool.schema "$metadata") == river-tps-tool-v2 &&
      $(provenance_property_once run.result "$metadata") == provisional &&
      $(provenance_property_once run.status "$metadata") == TERMINAL_RECEIPT_REQUIRED &&
      $(provenance_property_once terminal.required "$metadata") == true &&
      $(provenance_property_once terminal.path "$metadata") == "$receipt" &&
      $(provenance_property_once evidence.run_id "$metadata") == "$run_id" &&
      $(provenance_property_once lease.owner_pid "$metadata") == "$owner_pid" &&
      $(provenance_property_once lease.owner_start "$metadata") == "$owner_start" &&
      $(provenance_property_once lease.owner_identity_sha256 "$metadata") == "$owner_identity" &&
      $(provenance_property_once terminal.commitment_sha256 "$metadata") == "$commitment" &&
      $(provenance_sha256_file "$metadata") == "$metadata_hash" &&
      $(provenance_owner_identity_hash "$run_id" "$owner_pid" "$owner_start") == "$owner_identity" &&
      $(provenance_terminal_commitment_hash "$run_id" "$owner_identity" "$nonce") == "$commitment" ]] || return 1
  [[ $(provenance_sha256_file "$evidence_dir/host-observations.tsv") == "$observations_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-processes.tsv") == "$processes_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-classifications.tsv") == "$classifications_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-violations.tsv") == "$violations_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-provisional-daemons.tsv") == "$provisional_hash" &&
      $(provenance_sha256_file "$evidence_dir/provenance-checkpoints.tsv") == "$checkpoints_hash" ]] || return 1
  metadata_artifact=$(provenance_property_once artifact.run_id "$metadata") || return 1
  [[ $metadata_artifact == "$artifact_run_id" ]] || return 1
  if [[ $result == success ]]; then
    [[ $status == OK && $release_outcome == released && -f $artifact &&
        $artifact_run_id != unavailable &&
        $(provenance_property_once run.id "$artifact") == "$artifact_run_id" &&
        $(provenance_property_once artifact.sha256 "$metadata") == "$(provenance_sha256_file "$artifact")" &&
        ! -s $evidence_dir/host-violations.tsv ]] || return 1
  else
    [[ $status != OK ]] || return 1
    [[ $release_outcome == released || $release_outcome == failed ]] || return 1
  fi
}

provenance_capture_processes() {
  local timeout_seconds=${1:-5}
  local maximum_bytes=${2:-1048576}
  provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
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
  local timeout_seconds=${2:-2}
  local maximum_bytes=${3:-262144}
  local selected
  command -v jcmd >/dev/null 2>&1 || return 1
  selected=$(provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    jcmd "$pid" Thread.print -l) || return 1
  if grep -E 'org\.gradle\.launcher\.exec\.(ExecuteBuild|ChainingBuildActionRunner)|org\.gradle\.internal\.buildtree' \
      <<<"$selected" >/dev/null; then
    printf 'busy\n'
  else
    printf 'idle\n'
  fi
}

provenance_gradle_daemon_home() {
  local pid=$1
  local timeout_seconds=${2:-2}
  local maximum_bytes=${3:-262144}
  local selected home count
  command -v jcmd >/dev/null 2>&1 || return 1
  selected=$(provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    jcmd "$pid" VM.system_properties) || return 1
  count=$(grep -c '^gradle\.user\.home=' <<<"$selected" || true)
  [[ $count == 1 ]] || return 1
  home=$(sed -n 's/^gradle\.user\.home=//p' <<<"$selected")
  [[ -n $home && $home != *$'\t'* && $home != *$'\r'* ]] || return 1
  printf '%s\n' "$home"
}

provenance_run_bounded() {
  local timeout_seconds=$1
  local maximum_bytes=$2
  local result_file=$3
  shift 3
  provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" "$@" >"$result_file"
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

provenance_remaining_timeout() {
  local deadline=$1
  local maximum=$2
  local remaining=$((deadline - SECONDS))
  ((remaining > 0)) || return 1
  if ((remaining < maximum)); then
    printf '%s\n' "$remaining"
  else
    printf '%s\n' "$maximum"
  fi
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
  local ready_file=${11:-}
  local maximum_observations=${12:-0}
  local process_snapshot_maximum_bytes=${13:-1048576}
  local daemon_inspection_maximum_bytes=${14:-262144}
  local observation_timeout_seconds=${15:-30}
  local retained_maximum=$((maximum_bytes - 256))
  local sequence=0 raw_snapshot_content snapshot raw_classification classification now label pid state daemon_home
  local snapshot_start live_start_before live_start_after phase observation observation_finished provisional_addition
  local observations_completed=0 monitor_stop_requested=false observation_deadline inspection_timeout
  local retained_files=( "$evidence_dir/host-observations.tsv"
    "$evidence_dir/host-processes.tsv" "$evidence_dir/host-classifications.tsv"
    "$evidence_dir/host-violations.tsv" "$provisional_daemons" )
  ((retained_maximum > 0)) || return 1
  if [[ -s $evidence_dir/host-observations.tsv ]]; then
    sequence=$(tail -1 "$evidence_dir/host-observations.tsv" | cut -f1)
    [[ $sequence =~ ^[0-9]+$ ]] || return 1
  fi
  if [[ -n $ready_file ]]; then
    trap 'monitor_stop_requested=true' HUP INT TERM
    printf 'ready\n' >"$ready_file" || return 1
  fi
  while [[ $monitor_stop_requested != true && ! -e $stop_file ]]; do
    sequence=$((sequence + 1))
    now=$(date +%s)
    observation_deadline=$((SECONDS + observation_timeout_seconds))
    phase=unknown
    [[ -f $phase_file ]] && phase=$(tr -d '\r\n' <"$phase_file")
    case $phase in prebuild|build|workload|publication) ;; *) phase=unknown ;; esac
    snapshot="$evidence_dir/host-processes.$(printf '%06d' "$sequence").tsv"
    raw_classification="$evidence_dir/host-classification-raw.$(printf '%06d' "$sequence").tsv"
    classification="$evidence_dir/host-classification.$(printf '%06d' "$sequence").tsv"
    observation="$evidence_dir/host-observation.$(printf '%06d' "$sequence").tsv"
    provisional_addition="$evidence_dir/host-provisional.$(printf '%06d' "$sequence").tsv"
    : >"$provisional_addition"
    raw_snapshot_content=$(provenance_capture_processes \
      "$observation_timeout_seconds" "$process_snapshot_maximum_bytes") || {
      printf 'violation\tprocess_snapshot_failed\t%s\tphase=%s\n' "$now" "$phase" \
        >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$provisional_addition"
      return 1
    }
    if ! provenance_normalize_snapshot \
        <(printf '%s\n' "$raw_snapshot_content") "$snapshot"; then
      printf 'violation\tprocess_normalization_failed\t%s\tphase=%s\n' "$now" "$phase" \
        >>"$evidence_dir/host-violations.tsv"
      rm -f -- "$provisional_addition"
      return 1
    fi
    raw_snapshot_content=
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
      if ! inspection_timeout=$(provenance_remaining_timeout \
          "$observation_deadline" "$inspection_timeout_seconds"); then
        printf 'violation\thost_observation_timeout\t%s\n' "$pid" >>"$classification"
        continue
      fi
      live_start_before=$(provenance_process_start "$pid" "$inspection_timeout" || true)
      if ! inspection_timeout=$(provenance_remaining_timeout \
          "$observation_deadline" "$inspection_timeout_seconds"); then
        printf 'violation\thost_observation_timeout\t%s\n' "$pid" >>"$classification"
        continue
      fi
      state=$(provenance_gradle_daemon_state "$pid" "$inspection_timeout" \
        "$daemon_inspection_maximum_bytes") || state=unknown
      if ! inspection_timeout=$(provenance_remaining_timeout \
          "$observation_deadline" "$inspection_timeout_seconds"); then
        printf 'violation\thost_observation_timeout\t%s\n' "$pid" >>"$classification"
        continue
      fi
      live_start_after=$(provenance_process_start "$pid" "$inspection_timeout" || true)
      if [[ -z $snapshot_start || $live_start_before != "$snapshot_start" ||
          $live_start_after != "$snapshot_start" ]]; then
        printf 'violation\tprocess_identity_race\t%s\n' "$pid" >>"$classification"
        continue
      fi
      if [[ $state == busy && -e $owned_build_marker && -n $owned_gradle_home ]]; then
        if ! inspection_timeout=$(provenance_remaining_timeout \
            "$observation_deadline" "$inspection_timeout_seconds"); then
          printf 'violation\thost_observation_timeout\t%s\n' "$pid" >>"$classification"
          continue
        fi
        daemon_home=$(provenance_gradle_daemon_home "$pid" "$inspection_timeout" \
          "$daemon_inspection_maximum_bytes") || daemon_home=unknown
        if [[ $daemon_home == "$owned_gradle_home" ]]; then
          printf '%s\t%s\n' "$pid" "$snapshot_start" >>"$provisional_addition" || return 1
          printf 'provisional_owned_gradle_daemon\t%s\tstate=busy\thome_match=true\n' "$pid" >>"$classification"
          continue
        fi
      fi
      case $state in
        idle) printf 'allowed_idle_gradle_daemon\t%s\tstate=idle\n' "$pid" >>"$classification" ;;
        busy) printf 'violation\tbusy_gradle_daemon\t%s\thome_match=false\n' "$pid" >>"$classification" ;;
        *) printf 'violation\tuninspectable_gradle_daemon\t%s\n' "$pid" >>"$classification" ;;
      esac
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
        rm -f -- "$snapshot" "$raw_classification" "$classification" \
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
    observations_completed=$((observations_completed + 1))
    if ((maximum_observations > 0 && observations_completed >= maximum_observations)); then
      break
    fi
    sleep "$interval"
  done
}
