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
  local log=$1 argv_file=$2 command_file=$3 status=0
  shift 3
  PROVENANCE_LOGGED_PID=
  printf '%s\0' "$@" >"$argv_file" || return 125
  printf '%q ' "$@" >"$command_file" || return 125
  printf '\n' >>"$command_file" || return 125
  # A distinct process group lets the caller clean up only its own invocation.
  perl -MPOSIX -e '
    POSIX::setpgid(0, 0) == 0 or exit 126;
    $SIG{INT} = "DEFAULT"; $SIG{TERM} = "DEFAULT";
    exec @ARGV;
    exit 126;
  ' -- "$@" >"$log" 2>&1 &
  PROVENANCE_LOGGED_PID=$!
  wait "$PROVENANCE_LOGGED_PID" || status=$?
  PROVENANCE_LOGGED_PID=
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

PROVENANCE_BUILD_PAYLOAD_FILES=(build.command build.argv build.log source.before.tsv
  source.after.tsv git-status.before.txt git-status.after.txt runtime.properties classpath.tsv
  host-observations.tsv host-processes.tsv host-classifications.tsv host-violations.tsv)

# Build records are immutable and addressed by the descriptor's invocation ID.
# Runtime descriptors and compiler facts are supplied by Gradle, never resolved here.
provenance_validate_compiler_facts() {
  awk -F= '
    /^compiler\./ {
      n=split($1, name, "."); if (n != 3 || seen[$1]++) exit 1;
      value=substr($0, length($1) + 2);
      if (value == "" || value ~ /[\t\r]/) exit 1;
      if (name[3] == "launcher_sha256" || name[3] == "selected_options_sha256") {
        if (length(value) != 64 || value !~ /^[0-9a-f]+$/) exit 1;
      } else if (name[3] != "home" && name[3] != "version" && name[3] != "executable") exit 1;
      modules[name[2]]++; count++;
    }
    END {
      if (count == 0) exit 1;
      for (module in modules) if (modules[module] != 5) exit 1;
    }
  ' "$1"
}

provenance_seal_build_record() {
  local record=$1 id=$2
  local files=("${PROVENANCE_BUILD_PAYLOAD_FILES[@]}")
  local file digest
  [[ $id =~ ^[0-9a-f]{64}$ && -d $record && ! -L $record &&
      ! -e $record/completion.properties && ! -L $record/completion.properties ]] || return 1
  [[ $(provenance_property_once build.id "$record/runtime.properties") == "$id" &&
      $(provenance_property_once schema "$record/runtime.properties") == river-tps-runtime-v3 &&
      $(provenance_property_once build.inputs "$record/runtime.properties") == workspace_declared &&
      $(provenance_property_once build.cache_trust "$record/runtime.properties") == gradle_declared_inputs &&
      -n "$(provenance_property_once gradle.user.home "$record/runtime.properties")" ]] || return 1
  provenance_validate_compiler_facts "$record/runtime.properties" || return 1
  cmp -s "$record/source.before.tsv" "$record/source.after.tsv" || return 1
  cmp -s "$record/git-status.before.txt" "$record/git-status.after.txt" || return 1
  # Unknown symlink target inputs are outside this initial admitted build contract.
  if LC_ALL=C grep -q $'^working\tsymlink\t' "$record/source.before.tsv"; then return 1; fi
  : >"$record/files.tsv" || return 1
  for file in "${files[@]}"; do
    [[ -f $record/$file && ! -L $record/$file ]] || return 1
    digest=$(provenance_observe_file "$record/$file") || return 1
    printf '%s\t%s\n' "$digest" "$file" >>"$record/files.tsv" || return 1
  done
  provenance_observe_file "$record/files.tsv" >/dev/null
}

provenance_complete_build_record() {
  local record=$1 id=$2 lease_run_id=$3 lease_pid=$4 lease_start=$5
  local owner_identity=$6 nonce=$7 commitment=$8
  local digest staged="$record/completion.staged" file
  [[ $id =~ ^[0-9a-f]{64}$ && -d $record && ! -L $record &&
      ! -e $record/completion.properties && ! -L $record/completion.properties ]] || return 1
  digest=$(provenance_observe_file "$record/files.tsv") || return 1
  [[ $lease_run_id =~ ^[0-9a-f]{64}$ && $lease_pid =~ ^[0-9]+$ &&
      -n $lease_start && $owner_identity =~ ^[0-9a-f]{64}$ &&
      $nonce =~ ^[0-9a-f]{64}$ && $commitment =~ ^[0-9a-f]{64}$ &&
      $(provenance_owner_identity_hash "$lease_run_id" "$lease_pid" "$lease_start") == "$owner_identity" &&
      $(provenance_terminal_commitment_hash "$lease_run_id" "$owner_identity" "$nonce") == "$commitment" ]] || return 1
  {
    printf 'schema=river-tps-build-v2\n'
    printf 'build.id=%s\n' "$id"
    printf 'build.exit_status=0\n'
    printf 'build.cache_trust=gradle_declared_inputs\n'
    printf 'build.host_guarantee=qualified\n'
    printf 'build.release_outcome=released\n'
    printf 'build.lease.evidence_run_id=%s\n' "$lease_run_id"
    printf 'build.lease.pid=%s\n' "$lease_pid"
    printf 'build.lease.start=%s\n' "$lease_start"
    printf 'build.lease.owner_identity_sha256=%s\n' "$owner_identity"
    printf 'build.lease.nonce=%s\n' "$nonce"
    printf 'build.lease.terminal_commitment_sha256=%s\n' "$commitment"
    for file in host-observations.tsv host-processes.tsv host-classifications.tsv host-violations.tsv; do
      printf 'build.%s_sha256=%s\n' "${file%.tsv}" \
        "$(provenance_sha256_file "$record/$file")"
    done
    printf 'files.sha256=%s\n' "$digest"
  } >"$staged" || return 1
  provenance_publish_file "$staged" "$record/completion.properties" || {
    rm -f -- "$staged"
    return 1
  }
  rm -f -- "$staged"
}

provenance_validate_build_record() {
  local record=$1 id=$2
  local completion="$record/completion.properties" file expected actual index=0
  local files=("${PROVENANCE_BUILD_PAYLOAD_FILES[@]}")
  [[ $id =~ ^[0-9a-f]{64}$ && -d $record && ! -L $record &&
      -f $record/files.tsv && ! -L $record/files.tsv && -f $completion && ! -L $completion &&
      $(provenance_property_once schema "$completion") == river-tps-build-v2 &&
      $(provenance_property_once build.id "$completion") == "$id" &&
      $(provenance_property_once build.exit_status "$completion") == 0 &&
      $(provenance_property_once build.cache_trust "$completion") == gradle_declared_inputs &&
      $(provenance_property_once build.host_guarantee "$completion") == qualified &&
      $(provenance_property_once build.release_outcome "$completion") == released &&
      $(provenance_property_once build.lease.evidence_run_id "$completion") =~ ^[0-9a-f]{64}$ &&
      $(provenance_property_once build.lease.pid "$completion") =~ ^[0-9]+$ &&
      -n "$(provenance_property_once build.lease.start "$completion")" &&
      $(provenance_property_once build.lease.owner_identity_sha256 "$completion") =~ ^[0-9a-f]{64}$ &&
      $(provenance_property_once build.lease.nonce "$completion") =~ ^[0-9a-f]{64}$ &&
      $(provenance_property_once build.lease.terminal_commitment_sha256 "$completion") =~ ^[0-9a-f]{64}$ &&
      $(provenance_owner_identity_hash \
        "$(provenance_property_once build.lease.evidence_run_id "$completion")" \
        "$(provenance_property_once build.lease.pid "$completion")" \
        "$(provenance_property_once build.lease.start "$completion")") == \
        "$(provenance_property_once build.lease.owner_identity_sha256 "$completion")" &&
      $(provenance_terminal_commitment_hash \
        "$(provenance_property_once build.lease.evidence_run_id "$completion")" \
        "$(provenance_property_once build.lease.owner_identity_sha256 "$completion")" \
        "$(provenance_property_once build.lease.nonce "$completion")") == \
        "$(provenance_property_once build.lease.terminal_commitment_sha256 "$completion")" &&
      $(provenance_property_once build.host-observations_sha256 "$completion") == \
        "$(provenance_sha256_file "$record/host-observations.tsv")" &&
      $(provenance_property_once build.host-processes_sha256 "$completion") == \
        "$(provenance_sha256_file "$record/host-processes.tsv")" &&
      $(provenance_property_once build.host-classifications_sha256 "$completion") == \
        "$(provenance_sha256_file "$record/host-classifications.tsv")" &&
      $(provenance_property_once build.host-violations_sha256 "$completion") == \
        "$(provenance_sha256_file "$record/host-violations.tsv")" &&
      -s "$record/host-observations.tsv" && -s "$record/host-processes.tsv" &&
      -s "$record/host-classifications.tsv" &&
      ! -s "$record/host-violations.tsv" &&
      $(provenance_property_once files.sha256 "$completion") == "$(provenance_observe_file "$record/files.tsv")" ]] || return 1
  provenance_validate_inventory_sequence "$record/host-observations.tsv" build || return 1
  provenance_validate_host_ledgers "$record" || return 1
  while IFS=$'\t' read -r expected file; do
    ((index < ${#files[@]})) || return 1
    [[ $file == "${files[$index]}" && $expected =~ ^[0-9a-f]{64}$ &&
        -f $record/$file && ! -L $record/$file ]] || return 1
    actual=$(provenance_observe_file "$record/$file") || return 1
    [[ $actual == "$expected" ]] || return 1
    index=$((index + 1))
  done <"$record/files.tsv"
  ((index == ${#files[@]})) || return 1
  [[ $(provenance_property_once build.id "$record/runtime.properties") == "$id" &&
      $(provenance_property_once schema "$record/runtime.properties") == river-tps-runtime-v3 &&
      $(provenance_property_once build.inputs "$record/runtime.properties") == workspace_declared &&
      $(provenance_property_once build.cache_trust "$record/runtime.properties") == gradle_declared_inputs &&
      -n "$(provenance_property_once gradle.user.home "$record/runtime.properties")" ]] || return 1
  provenance_validate_compiler_facts "$record/runtime.properties" || return 1
  cmp -s "$record/source.before.tsv" "$record/source.after.tsv" || return 1
  cmp -s "$record/git-status.before.txt" "$record/git-status.after.txt" || return 1
  ! LC_ALL=C grep -q $'^working\tsymlink\t' "$record/source.before.tsv"
}

provenance_copy_build_record() {
  local source=$1 destination=$2 id=$3 file
  provenance_validate_build_record "$source" "$id" || return 1
  mkdir -- "$destination" || return 1
  for file in "${PROVENANCE_BUILD_PAYLOAD_FILES[@]}" files.tsv completion.properties; do
    provenance_publish_file "$source/$file" "$destination/$file" || return 1
  done
  provenance_validate_build_record "$destination" "$id"
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

# This is deliberately independent of TMPDIR and the checkout. Test fixtures
# may override this function after sourcing the script; production callers do
# not accept a lease-path option.
PROVENANCE_CANONICAL_LEASE_DIR=/tmp/river-tps-host-lease
provenance_canonical_lease_dir() {
  printf '%s\n' "$PROVENANCE_CANONICAL_LEASE_DIR"
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

# ps adapters share the C-locale lstart shape; malformed observations are unknown.
PROVENANCE_PROCESS_START_PATTERN='^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) ([1-9]|[12][0-9]|3[01]) [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}$'

provenance_process_start() {
  local pid=$1
  local timeout_seconds=${2:-2}
  local maximum_bytes=${3:-4096}
  local raw
  raw=$(LC_ALL=C provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    ps -p "$pid" -o lstart=) || return 1
  printf '%s\n' "$raw" |
    LC_ALL=C awk -v pattern="$PROVENANCE_PROCESS_START_PATTERN" '
      {
        started=$1 " " $2 " " $3 " " $4 " " $5
        if (NR != 1 || NF != 5 || started !~ pattern) { invalid=1; next }
        result=started
      }
      END {
        if (invalid || NR != 1 || result == "") exit 1
        print result
      }
    '
}

provenance_path_identity() {
  if stat -f '%d:%i:%HT:%l' "$1" >/dev/null 2>&1; then
    stat -f '%d:%i:%HT:%l' "$1"
  else
    stat -c '%d:%i:%F:%h' "$1"
  fi
}

provenance_directory_identity() {
  if stat -f '%d:%i:%HT' "$1" >/dev/null 2>&1; then
    stat -f '%d:%i:%HT' "$1"
  else
    stat -c '%d:%i:%F' "$1"
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

provenance_validate_current_lease() {
  local lease_dir=$1 expected_run_id=$2 expected_nonce=$3
  local owner_file="$lease_dir/owner" record run_id pid started identity commitment current
  local current_pid=${BASHPID:-$$}
  provenance_lease_exact_owner "$lease_dir" || return 1
  record=$(provenance_read_lease_owner "$owner_file") || return 1
  IFS=$'\t' read -r run_id pid started identity commitment <<<"$record"
  [[ $run_id == "$expected_run_id" && $pid == "$current_pid" &&
      $pid =~ ^[0-9]+$ && -n $started &&
      $identity == "$(provenance_owner_identity_hash "$run_id" "$pid" "$started")" &&
      $commitment == "$(provenance_terminal_commitment_hash "$run_id" "$identity" "$expected_nonce")" ]] || return 1
  current=$(provenance_process_start "$pid" || true)
  [[ $current == "$started" ]] || return 1
  [[ ${PROVENANCE_LEASE_RUN_ID:-} == "$run_id" &&
      ${PROVENANCE_LEASE_NONCE:-} == "$expected_nonce" &&
      ${PROVENANCE_LEASE_OWNER_PID:-} == "$pid" &&
      ${PROVENANCE_LEASE_OWNER_START:-} == "$started" &&
      ${PROVENANCE_LEASE_OWNER_IDENTITY_SHA256:-} == "$identity" &&
      ${PROVENANCE_TERMINAL_COMMITMENT_SHA256:-} == "$commitment" ]]
}

provenance_unlink_observed_owner() {
  local lease_dir=$1 directory_before=$2 owner_before=$3 owner_hash=$4
  (
    cd -- "$lease_dir" || exit 1
    [[ $(provenance_directory_identity .) == "$directory_before" ]] || exit 1
    [[ $(provenance_path_identity ./owner) == "$owner_before" ]] || exit 1
    [[ $(provenance_sha256_file ./owner) == "$owner_hash" ]] || exit 1
    rm -- ./owner
  )
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
    directory_before=$(provenance_directory_identity "$lease_dir") || return 1
    owner_before=$(provenance_path_identity "$owner_file") || return 1
    owner_hash_before=$(provenance_sha256_file "$owner_file") || return 1
    provenance_lease_exact_owner "$lease_dir" &&
      [[ $(provenance_directory_identity "$lease_dir") == "$directory_before" &&
        $(provenance_path_identity "$owner_file") == "$owner_before" &&
        $(provenance_sha256_file "$owner_file") == "$owner_hash_before" ]] || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_reclaim_raced
      return 1
    }
    if ! provenance_unlink_observed_owner "$lease_dir" "$directory_before" \
        "$owner_before" "$owner_hash_before" 2>/dev/null; then
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_owner_unlink_failed
      return 1
    fi
    [[ $(provenance_directory_identity "$lease_dir") == "$directory_before" ]] || {
      PROVENANCE_LEASE_ACQUIRE_STATUS=stale_directory_replaced
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
  PROVENANCE_LEASE_RUN_ID=$run_id
  PROVENANCE_LEASE_NONCE=$nonce
  PROVENANCE_TERMINAL_COMMITMENT_SHA256=$commitment
  PROVENANCE_LEASE_ACQUIRE_STATUS=acquired
  export PROVENANCE_LEASE_OWNER_IDENTITY_SHA256 PROVENANCE_LEASE_OWNER_PID \
    PROVENANCE_LEASE_OWNER_START PROVENANCE_LEASE_RUN_ID PROVENANCE_LEASE_NONCE \
    PROVENANCE_TERMINAL_COMMITMENT_SHA256
}

provenance_release_lease() {
  local lease_dir=$1
  local owner_file="$lease_dir/owner"
  local owner_record run_id pid started identity commitment directory_before owner_before owner_hash
  provenance_validate_current_lease "$lease_dir" \
    "${PROVENANCE_LEASE_RUN_ID:-}" "${PROVENANCE_LEASE_NONCE:-}" || return 1
  provenance_lease_exact_owner "$lease_dir" || return 1
  directory_before=$(provenance_directory_identity "$lease_dir") || return 1
  owner_before=$(provenance_path_identity "$owner_file") || return 1
  owner_hash=$(provenance_sha256_file "$owner_file") || return 1
  owner_record=$(provenance_read_lease_owner "$owner_file") || return 1
  IFS=$'\t' read -r run_id pid started identity commitment <<<"$owner_record"
  [[ $run_id == "$PROVENANCE_LEASE_RUN_ID" &&
      $identity == "$PROVENANCE_LEASE_OWNER_IDENTITY_SHA256" &&
      $commitment == "$PROVENANCE_TERMINAL_COMMITMENT_SHA256" ]] || return 1
  provenance_lease_exact_owner "$lease_dir" &&
    [[ $(provenance_directory_identity "$lease_dir") == "$directory_before" &&
      $(provenance_path_identity "$owner_file") == "$owner_before" &&
      $(provenance_sha256_file "$owner_file") == "$owner_hash" ]] || return 1
  provenance_unlink_observed_owner "$lease_dir" "$directory_before" \
    "$owner_before" "$owner_hash" || return 1
  [[ $(provenance_directory_identity "$lease_dir") == "$directory_before" ]] || return 1
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
  local lease_run_id=${14}
  local lease_pid=${15}
  local lease_start=${16}
  local lease_identity=${17}
  local lease_commitment=${18}
  local host_guarantee=${19:-qualified}
  {
    printf 'schema=river-tps-terminal-v3\n'
    printf 'terminal.result=%s\n' "$result"
    printf 'terminal.status=%s\n' "$status"
    printf 'evidence.run_id=%s\n' "$evidence_run_id"
    printf 'artifact.run_id=%s\n' "$artifact_run_id"
    printf 'metadata.sha256=%s\n' "$metadata_sha256"
    printf 'publisher.pid=%s\n' "$owner_pid"
    printf 'publisher.start=%s\n' "$owner_start"
    printf 'publisher.identity_sha256=%s\n' "$owner_identity"
    printf 'terminal.nonce=%s\n' "$nonce"
    printf 'terminal.commitment_sha256=%s\n' "$commitment"
    printf 'host.observations_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-observations.tsv")"
    printf 'host.processes_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-processes.tsv")"
    printf 'host.classifications_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-classifications.tsv")"
    printf 'host.violations_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/host-violations.tsv")"
    printf 'provenance.checkpoints_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/provenance-checkpoints.tsv")"
    printf 'host.release_outcome=%s\n' "$release_outcome"
    printf 'host.guarantee=%s\n' "$host_guarantee"
    printf 'lease.evidence_run_id=%s\n' "$lease_run_id"
    printf 'lease.owner_pid=%s\n' "$lease_pid"
    printf 'lease.owner_start=%s\n' "$lease_start"
    printf 'lease.owner_identity_sha256=%s\n' "$lease_identity"
    printf 'lease.terminal_commitment_sha256=%s\n' "$lease_commitment"
    printf 'build.record_sha256=%s\n' "$(provenance_sha256_file "$evidence_dir/build-record/completion.properties" 2>/dev/null || printf unavailable)"
  } >"$destination"
}

provenance_validate_terminal_receipt() {
  local metadata=$1
  local artifact=$2
  local receipt=$3
  local evidence_dir=$4
  local expected_result=${5:-success} required_guarantee=${6:-diagnostic}
  local values schema result status run_id artifact_run_id metadata_hash owner_pid owner_start
  local owner_identity nonce commitment observations_hash processes_hash classifications_hash
  local violations_hash checkpoints_hash release_outcome metadata_artifact host_guarantee build_hash build_id
  local expected_host_exclusion
  local lease_run_id lease_pid lease_start lease_identity lease_commitment
  local owner_binding_available=false
  [[ -f $metadata && -f $receipt && -d $evidence_dir ]] || return 1
  values=$(awk '
    BEGIN {
      keys[1]="schema"; keys[2]="terminal.result"; keys[3]="terminal.status";
      keys[4]="evidence.run_id"; keys[5]="artifact.run_id"; keys[6]="metadata.sha256";
      keys[7]="publisher.pid"; keys[8]="publisher.start";
      keys[9]="publisher.identity_sha256"; keys[10]="terminal.nonce";
      keys[11]="terminal.commitment_sha256"; keys[12]="host.observations_sha256";
      keys[13]="host.processes_sha256"; keys[14]="host.classifications_sha256";
      keys[15]="host.violations_sha256"; keys[16]="provenance.checkpoints_sha256";
      keys[17]="host.release_outcome"; keys[18]="host.guarantee";
      keys[19]="lease.evidence_run_id"; keys[20]="lease.owner_pid";
      keys[21]="lease.owner_start"; keys[22]="lease.owner_identity_sha256";
      keys[23]="lease.terminal_commitment_sha256"; keys[24]="build.record_sha256";
    }
    {
      separator=index($0, "=");
      if (separator < 2 || substr($0, 1, separator - 1) != keys[NR]) exit 1;
      value=substr($0, separator + 1);
      if (value == "" || value ~ /[\t\r]/) exit 1;
      values[NR]=value;
    }
    END {
      if (NR != 24) exit 1;
      for (i=1; i<=24; i++) printf "%s%s", values[i], (i == 24 ? "\n" : "\t");
    }
  ' "$receipt") || return 1
  IFS=$'\t' read -r schema result status run_id artifact_run_id metadata_hash owner_pid \
    owner_start owner_identity nonce commitment observations_hash processes_hash \
    classifications_hash violations_hash checkpoints_hash release_outcome host_guarantee \
    lease_run_id lease_pid lease_start lease_identity lease_commitment build_hash \
    <<<"$values"
  [[ $required_guarantee == diagnostic || $required_guarantee == promotion ]] || return 1
  expected_host_exclusion=false
  [[ $host_guarantee == qualified ]] && expected_host_exclusion=true
  [[ $schema == river-tps-terminal-v3 && $result == "$expected_result" &&
      $run_id =~ ^[0-9a-f]{64}$ && $metadata_hash =~ ^[0-9a-f]{64}$ ]] || return 1
  if [[ $owner_pid =~ ^[0-9]+$ && $owner_identity =~ ^[0-9a-f]{64}$ &&
      $nonce =~ ^[0-9a-f]{64}$ && $commitment =~ ^[0-9a-f]{64}$ &&
      $lease_run_id == "$run_id" && $lease_pid == "$owner_pid" &&
      -n $lease_start && $lease_identity =~ ^[0-9a-f]{64}$ &&
      $lease_commitment == "$commitment" &&
      $(provenance_owner_identity_hash "$lease_run_id" "$lease_pid" "$lease_start") == "$lease_identity" &&
      $lease_identity == "$owner_identity" ]]; then
    owner_binding_available=true
  else
    [[ $result == evidence_invalid && $host_guarantee == unqualified &&
        $release_outcome == not_acquired && $owner_pid == unavailable &&
        $owner_start == unavailable && $owner_identity == unavailable &&
        $nonce == unavailable && $commitment == unavailable &&
        $lease_run_id == unavailable && $lease_pid == unavailable &&
        $lease_start == unavailable && $lease_identity == unavailable &&
        $lease_commitment == unavailable ]] || return 1
  fi
  [[ $(provenance_property_once tool.schema "$metadata") == river-tps-tool-v4 &&
      $(provenance_property_once run.result "$metadata") == provisional &&
      $(provenance_property_once run.status "$metadata") == TERMINAL_RECEIPT_REQUIRED &&
      $(provenance_property_once terminal.required "$metadata") == true &&
      $(provenance_property_once terminal.path "$metadata") == "$receipt" &&
      $(provenance_property_once evidence.run_id "$metadata") == "$run_id" &&
      $(provenance_property_once host.guarantee "$metadata") == "$host_guarantee" &&
      $(provenance_property_once provenance.host_exclusion_valid "$metadata") == "$expected_host_exclusion" &&
      $(provenance_property_once host.lease.evidence_run_id "$metadata") == "$lease_run_id" &&
      $(provenance_property_once host.lease.owner_pid "$metadata") == "$lease_pid" &&
      $(provenance_property_once host.lease.owner_start "$metadata") == "$lease_start" &&
      $(provenance_property_once host.lease.owner_identity_sha256 "$metadata") == "$lease_identity" &&
      $(provenance_property_once host.lease.nonce "$metadata") == "$nonce" &&
      $(provenance_property_once host.lease.terminal_commitment_sha256 "$metadata") == "$lease_commitment" &&
      $(provenance_sha256_file "$metadata") == "$metadata_hash" ]] || return 1
  if [[ $owner_binding_available == true ]]; then
    [[ $(provenance_property_once publisher.pid "$metadata") == "$owner_pid" &&
        $(provenance_property_once publisher.start "$metadata") == "$owner_start" &&
        $(provenance_property_once publisher.identity_sha256 "$metadata") == "$owner_identity" &&
        $(provenance_property_once terminal.commitment_sha256 "$metadata") == "$commitment" &&
        $(provenance_owner_identity_hash "$run_id" "$owner_pid" "$owner_start") == "$owner_identity" &&
        $(provenance_terminal_commitment_hash "$run_id" "$owner_identity" "$nonce") == "$commitment" ]] || return 1
  else
    [[ $(provenance_property_once publisher.pid "$metadata") == unavailable &&
        $(provenance_property_once publisher.start "$metadata") == unavailable &&
        $(provenance_property_once publisher.identity_sha256 "$metadata") == unavailable &&
        $(provenance_property_once terminal.commitment_sha256 "$metadata") == unavailable ]] || return 1
  fi
  [[ $(provenance_sha256_file "$evidence_dir/host-observations.tsv") == "$observations_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-processes.tsv") == "$processes_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-classifications.tsv") == "$classifications_hash" &&
      $(provenance_sha256_file "$evidence_dir/host-violations.tsv") == "$violations_hash" &&
      -s "$evidence_dir/host-observations.tsv" && -s "$evidence_dir/host-processes.tsv" &&
      -s "$evidence_dir/host-classifications.tsv" &&
      $(provenance_sha256_file "$evidence_dir/provenance-checkpoints.tsv") == "$checkpoints_hash" ]] || return 1
  if [[ $result == success ]]; then
    provenance_validate_inventory_sequence "$evidence_dir/host-observations.tsv" tps-complete || return 1
    provenance_validate_host_ledgers "$evidence_dir" || return 1
    provenance_validate_checkpoint_sequence "$evidence_dir/provenance-checkpoints.tsv" true || return 1
  else
    provenance_validate_inventory_sequence "$evidence_dir/host-observations.tsv" tps-prefix || return 1
    provenance_validate_checkpoint_sequence "$evidence_dir/provenance-checkpoints.tsv" false || return 1
  fi
  metadata_artifact=$(provenance_property_once artifact.run_id "$metadata") || return 1
  [[ $metadata_artifact == "$artifact_run_id" ]] || return 1
  if [[ $result == success ]]; then
    build_id=$(provenance_property_once provenance.build_id "$metadata") || return 1
    provenance_validate_build_record "$evidence_dir/build-record" "$build_id" || return 1
    [[ $build_hash == "$(provenance_sha256_file "$evidence_dir/build-record/completion.properties")" &&
        $(provenance_property_once provenance.build_valid "$metadata") == true &&
        $(provenance_property_once provenance.classpath_sha256 "$metadata") == "$(provenance_sha256_file "$evidence_dir/build-record/classpath.tsv")" &&
        $(provenance_property_once provenance.source_manifest_sha256 "$metadata") == "$(provenance_sha256_file "$evidence_dir/build-record/source.before.tsv")" ]] || return 1
    [[ $status == OK && $host_guarantee == qualified && $release_outcome == released &&
        -f $artifact &&
        $artifact_run_id != unavailable &&
        $(provenance_property_once run.id "$artifact") == "$artifact_run_id" &&
        $(provenance_property_once artifact.sha256 "$metadata") == "$(provenance_sha256_file "$artifact")" &&
        ! -s $evidence_dir/host-violations.tsv ]] || return 1
  else
    [[ $status != OK ]] || return 1
  fi
  [[ $required_guarantee == diagnostic ||
      ($host_guarantee == qualified && $release_outcome == released) ]]
}

provenance_capture_processes() {
  local timeout_seconds=${1:-5}
  local maximum_bytes=${2:-1048576}
  LC_ALL=C provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    ps -axo pid=,ppid=,lstart=,command=
}

provenance_normalize_snapshot() {
  local raw_snapshot=$1
  local normalized_snapshot=$2
  local staged="$normalized_snapshot.staged.$$"
  LC_ALL=C awk -v pattern="$PROVENANCE_PROCESS_START_PATTERN" '
    {
      if ($1 !~ /^[0-9]+$/ || $2 !~ /^[0-9]+$/ || NF < 8 || seen[$1]++) exit 1
      pid=$1; ppid=$2
      started=$3 " " $4 " " $5 " " $6 " " $7
      if (started !~ pattern) exit 1
      command=$0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+/, "", command)
      sub(/^[A-Z][a-z][a-z][[:space:]]+[A-Z][a-z][a-z][[:space:]]+[[:digit:]][[:digit:]]*[[:space:]]+[0-9:]+[[:space:]]+[0-9]{4}[[:space:]]+/, "", command)
      if (command == "") exit 1
      token="none"
      split(command, argv, /[[:space:]]+/)
      executable=argv[1]
      invoked=executable
      if (executable ~ /(^|\/)(bash|sh|zsh|ksh|dash)$/ && argv[2] != "") invoked=argv[2]
      if (executable ~ /(^|\/)java$/ && command ~ /org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon/) token="gradle_daemon"
      else if ((executable ~ /(^|\/)java$/ && command ~ /GradleWrapperMain|GradleWorkerMain/) ||
          (invoked ~ /(^|\/)gradle(w)?$/ && command ~ /(^|[[:space:]])(build|test|check|classes|compile|verify|clean|assemble)([[:space:]]|$)/)) token="gradle_activity"
      else if ((executable ~ /(^|\/)java$/ && command ~ /TpccAcceptanceMain|TpccServerMain/) ||
          invoked ~ /(^|\/)tools\/tps-(test|p4|interleave)\.sh$/) token="river_workload"
      else if (invoked ~ /river-harness\/benchmark$/ && command ~ /(^|[[:space:]])run[[:space:]]+(river|mariadb|postgres)([[:space:]]|$)/) token="database_harness"
      else if (invoked ~ /(^|\/)(async-profiler|jfr)$/ ||
          invoked ~ /(^|\/)tools\/(trace-update|jfr-flamegraph)\.sh$/) token="profile"
      else if (executable ~ /(^|\/)java$/ && command ~ /org\.junit|JUnitStarter|[\/]river[^ ]*\/build\//) token="river_build_or_test"
      else if (invoked ~ /(^|\/)(pgbench|mysqlslap)$/ ||
          command ~ /(^|[[:space:]])(sysbench|tpcc)[[:space:]].*(mysql|pgsql|run|benchmark)/) token="database_workload"
      print pid "\t" ppid "\t" started "\t" token
    }
    END { if (NR == 0) exit 1 }
  ' "$raw_snapshot" >"$staged" || {
    rm -f -- "$staged"
    return 1
  }
  mv -- "$staged" "$normalized_snapshot"
}

provenance_gradle_status() {
  local wrapper=$1
  local gradle_home=${2:-}
  local timeout_seconds=${3:-5}
  local maximum_bytes=${4:-262144}
  local raw
  [[ -x $wrapper && $wrapper != *$'\t'* && $wrapper != *$'\r'* && $wrapper != *$'\n'* ]] || return 1
  local arguments=(--status --no-daemon)
  [[ -z $gradle_home ]] || arguments+=("--gradle-user-home=$gradle_home")
  raw=$(LC_ALL=C provenance_capture_bounded "$timeout_seconds" "$maximum_bytes" \
    "$wrapper" "${arguments[@]}") || return 1
  LC_ALL=C awk '
    BEGIN { rows=0; running=0; malformed=0; header=0; footer=0; none=0 }
    /^[[:space:]]*$/ { next }
    /^No Gradle daemons are running[.]$/ { if (none++) malformed=1; next }
    NF == 3 && $1 == "PID" && $2 == "STATUS" && $3 == "INFO" {
      if (header++ || footer) malformed=1
      next
    }
    /^Only Daemons for the current Gradle version are displayed[.] / {
      if (footer++) malformed=1
      next
    }
    NF == 3 && $1 ~ /^[0-9]+$/ && $2 ~ /^(IDLE|BUSY)$/ &&
        $3 ~ /^[0-9]+([.][0-9]+)*([-.][[:alnum:]]+)*$/ {
      if (!header || footer || seen[$1]++) malformed=1
      print $1 "\t" tolower($2); rows++; running++; next
    }
    $1 ~ /^[0-9]+$/ && $2 == "STOPPED" && $0 ~ /[(].*[)]$/ {
      if (!header || footer || seen[$1]++) malformed=1
      print $1 "\tstopped"; rows++; next
    }
    { malformed=1 }
    END {
      if (malformed || !footer || (!rows && !none) || (none && running)) exit 1
    }
  ' <<<"$raw"
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

provenance_validate_inventory_sequence() {
  local file=$1 kind=$2
  awk -F '\t' -v kind="$kind" '
    BEGIN {
      if (kind == "build") {
        expected[1]="build-pre"; expected[2]="build-post"; maximum=2; complete=1
      } else {
        expected[1]="pre-source"; expected[2]="pre-client"
        expected[3]="post-cleanup"; expected[4]="pre-publication"
        maximum=4; complete=(kind == "tps-complete")
      }
      previous=0; rows=0; invalid=0
    }
    {
      rows++
      if (NF != 2 || $1 != rows || $1 !~ /^[0-9]+$/) { invalid=1; next }
      selected=0
      for (i=previous + 1; i <= maximum; i++) {
        if ($2 == expected[i]) { selected=i; break }
      }
      if (!selected) invalid=1
      previous=selected
    }
    END {
      if (invalid || rows == 0 || (complete && rows != maximum) ||
          (!complete && kind == "build" && rows != maximum) ||
          (complete && previous != maximum)) exit 1
    }
  ' "$file"
}

# The canonical receipt hashes the complete ledgers. Validate their relationships
# here instead of retaining another set of hashes for ephemeral capture files.
provenance_validate_host_ledgers() {
  local directory=$1
  LC_ALL=C awk -F '\t' -v pattern="$PROVENANCE_PROCESS_START_PATTERN" '
    FILENAME == ARGV[1] { phases[$1]=$2; next }
    FILENAME == ARGV[2] {
      if (NF != 5 || !($1 in phases) || $2 !~ /^[0-9]+$/ ||
          $3 !~ /^[0-9]+$/ || $4 !~ pattern ||
          $5 !~ /^(none|gradle_daemon|gradle_activity|river_workload|database_harness|profile|river_build_or_test|database_workload)$/ ||
          (($1 SUBSEP $2) in processes)) { invalid=1; next }
      processes[$1,$2]=$5; process_count[$1]++; next
    }
    FILENAME == ARGV[3] {
      if (NF != 4 || !($1 in phases) || $2 != phases[$1] ||
          (($1 SUBSEP $4) in classified)) { invalid=1; next }
      classified[$1,$4]=1; classification_count[$1]++
      if ($3 == "clean" && $4 == "-") { clean[$1]=1; next }
      if ($3 != "allowed_idle_gradle_daemon" ||
          processes[$1,$4] != "gradle_daemon") invalid=1
    }
    END {
      for (sequence in phases) {
        if (!process_count[sequence] || !classification_count[sequence] ||
            (clean[sequence] && classification_count[sequence] != 1)) invalid=1
      }
      if (invalid) exit 1
    }
  ' "$directory/host-observations.tsv" "$directory/host-processes.tsv" \
    "$directory/host-classifications.tsv"
}

provenance_validate_checkpoint_sequence() {
  local file=$1 complete=${2:-false}
  awk -F '\t' -v complete="$complete" '
    BEGIN {
      expected[1]="startup"; expected[2]="server"; expected[3]="client_start"
      expected[4]="client_finish"; expected[5]="result"; expected[6]="publication"
      expected[7]="metadata"; expected[8]="terminal"
      previous=0; rows=0; invalid=0
    }
    {
      rows++
      if (NF != 6 || $2 !~ /^[0-9]+$/ ||
          (complete == "true" &&
            ($3 !~ /^[0-9a-f]{64}$/ || $4 !~ /^[0-9a-f]{64}$/ ||
              $5 !~ /^[0-9a-f]{64}$/ || $6 !~ /^[0-9a-f]{64}$/)) ||
          (complete != "true" &&
            ($3 !~ /^[0-9a-f]{64}$|^unavailable$/ ||
              $4 !~ /^[0-9a-f]{64}$|^unavailable$/ ||
              $5 !~ /^[0-9a-f]{64}$|^unavailable$/ ||
              $6 !~ /^[0-9a-f]{64}$|^unavailable$/))) { invalid=1; next }
      selected=0
      for (i=previous + 1; i <= 8; i++) {
        if ($1 == expected[i]) { selected=i; break }
      }
      if (!selected) invalid=1
      previous=selected
    }
    END {
      if (invalid || rows == 0 || (complete == "true" &&
          (rows != 8 || previous != 8))) exit 1
    }
  ' "$file"
}

provenance_classify_snapshot() {
  local snapshot=$1
  local self_pid=$2
  local include_owned=${3:-false}
  awk -F '\t' -v self="$self_pid" -v include_owned="$include_owned" '
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
        if (ancestor_of_self(pid)) continue
        if (owned(pid)) {
          if (include_owned == "true" && token[pid] != "none")
            print "owned\t" token[pid] "\t" pid
          continue
        }
        if (kind=="gradle_daemon") {
          print "allowed_idle_gradle_daemon\t" pid
          continue
        }
        if (kind!="none") print "violation\t" kind "\t" pid
      }
    }
  ' "$snapshot"
}

# Take one bounded inventory at a lifecycle boundary. The Gradle status query
# is authoritative for daemon state; no stack inspection or measured-phase
# monitor is allowed here. Raw process output is temporary and only the
# normalized rows and classifications are retained.
provenance_inventory_boundary() {
  local evidence_dir=$1 self_pid=$2 phase=$3 gradle_wrapper=$4 gradle_home=$5
  local maximum_bytes=${6:-16777216} timeout_seconds=${7:-5}
  local reject_owned=${8:-false}
  local sequence raw snapshot raw_classification classification status_rows
  local snapshot_start before after state label kind line current_bytes addition_bytes
  local snapshot_file classification_file observation_file classification_output
  local process_output violation_output inventory_valid=true
  local retained_files=("$evidence_dir/host-observations.tsv"
    "$evidence_dir/host-processes.tsv" "$evidence_dir/host-classifications.tsv"
    "$evidence_dir/host-violations.tsv")
  [[ -d $evidence_dir && $phase =~ ^[A-Za-z0-9_-]+$ ]] || return 1
  for file in "${retained_files[@]}"; do
    [[ -f $file ]] || : >"$file" || return 1
  done
  sequence=$(awk -F '\t' 'END {print ($1 ~ /^[0-9]+$/ ? $1 + 1 : 1)}' \
    "$evidence_dir/host-observations.tsv") || return 1
  snapshot_file=$(mktemp "$evidence_dir/.host-processes.XXXXXX") || return 1
  classification_file=$(mktemp "$evidence_dir/.host-classification.XXXXXX") || {
    rm -f -- "$snapshot_file"
    return 1
  }
  observation_file=$(mktemp "$evidence_dir/.host-observation.XXXXXX") || {
    rm -f -- "$snapshot_file" "$classification_file"
    return 1
  }
  classification_output=$(mktemp "$evidence_dir/.host-classifications.XXXXXX") || {
    rm -f -- "$snapshot_file" "$classification_file" "$observation_file"
    return 1
  }
  process_output=$(mktemp "$evidence_dir/.host-processes.XXXXXX") || {
    rm -f -- "$snapshot_file" "$classification_file" "$observation_file" \
      "$classification_output"
    return 1
  }
  violation_output=$(mktemp "$evidence_dir/.host-violations.XXXXXX") || {
    rm -f -- "$snapshot_file" "$classification_file" "$observation_file" \
      "$classification_output" "$process_output"
    return 1
  }
  cleanup_inventory_temps() {
    rm -f -- "$snapshot_file" "$classification_file" "$observation_file" \
      "$classification_output" "$process_output" "$violation_output"
  }
  if ! status_rows=$(provenance_gradle_status "$gradle_wrapper" "$gradle_home" \
      "$timeout_seconds" 262144); then
    printf '%s\t%s\tgradle_status_unavailable\n' "$sequence" "$phase" \
      >>"$evidence_dir/host-violations.tsv"
    cleanup_inventory_temps
    return 1
  fi
  if ! provenance_capture_processes "$timeout_seconds" 1048576 >"$snapshot_file" ||
      ! provenance_normalize_snapshot "$snapshot_file" "$snapshot_file" ||
      ! provenance_classify_snapshot "$snapshot_file" "$self_pid" "$reject_owned" \
        >"$classification_file"; then
    printf '%s\t%s\tprocess_inventory_unavailable\n' "$sequence" "$phase" \
      >>"$evidence_dir/host-violations.tsv"
    cleanup_inventory_temps
    return 1
  fi
  while IFS=$'\t' read -r label kind pid; do
    [[ -n $label ]] || continue
    case $label in
      violation)
        printf '%s\t%s\t%s\t%s\n' "$sequence" "$phase" "$kind" "$pid" \
          >>"$classification_output"
        printf '%s\t%s\t%s\t%s\n' "$sequence" "$phase" "$kind" "$pid" \
          >>"$violation_output"
        ;;
      owned)
        printf '%s\t%s\towned_%s\t%s\n' "$sequence" "$phase" "$kind" "$pid" \
          >>"$classification_output"
        if [[ $reject_owned == true ]]; then
          printf '%s\t%s\towned_%s\t%s\n' "$sequence" "$phase" "$kind" "$pid" \
            >>"$violation_output"
        fi
        ;;
      allowed_idle_gradle_daemon)
        snapshot_start=$(awk -F '\t' -v wanted="$kind" \
          '$1 == wanted {print $3; count++} END {if (count != 1) exit 1}' \
          "$snapshot_file" 2>/dev/null || true)
        before=$(provenance_process_start "$kind" "$timeout_seconds" 4096 || true)
        state=$(awk -F '\t' -v wanted="$kind" \
          '$1 == wanted {print $2; count++} END {if (count != 1) exit 1}' \
          <<<"$status_rows" 2>/dev/null || true)
        after=$(provenance_process_start "$kind" "$timeout_seconds" 4096 || true)
        if [[ -z $snapshot_start || -z $before || $before != "$snapshot_start" ||
            -z $after || $after != "$snapshot_start" ]]; then
          line="$sequence"$'\t'"$phase"$'\t'"process_identity_race"$'\t'"$kind"
        elif [[ $state == idle ]]; then
          line="$sequence"$'\t'"$phase"$'\t'"allowed_idle_gradle_daemon"$'\t'"$kind"
        elif [[ $state == busy ]]; then
          line="$sequence"$'\t'"$phase"$'\t'"busy_gradle_daemon"$'\t'"$kind"
        else
          line="$sequence"$'\t'"$phase"$'\t'"uninspectable_gradle_daemon"$'\t'"$kind"
        fi
        printf '%s\n' "$line" >>"$classification_output"
        if [[ $line == *$'\tbusy_gradle_daemon\t'* ||
            $line == *$'\tuninspectable_gradle_daemon\t'* ||
            $line == *$'\tprocess_identity_race\t'* ]]; then
          printf '%s\n' "$line" >>"$violation_output"
        fi
        ;;
      *)
        printf '%s\t%s\tunknown_inventory_result\t%s\n' "$sequence" "$phase" "$label" \
          >>"$classification_output"
        printf '%s\t%s\tunknown_inventory_result\t%s\n' "$sequence" "$phase" "$label" \
          >>"$violation_output"
        ;;
    esac
  done <"$classification_file"
  while IFS= read -r line; do
    [[ -n $line ]] || continue
    printf '%s\t%s\n' "$sequence" "$line" >>"$process_output"
  done <"$snapshot_file"
  printf '%s\t%s\n' "$sequence" "$phase" >"$observation_file"
  [[ -s $classification_output ]] ||
    printf '%s\t%s\tclean\t-\n' "$sequence" "$phase" >"$classification_output"
  current_bytes=$(provenance_evidence_bytes "${retained_files[@]}") || {
    cleanup_inventory_temps
    return 1
  }
  addition_bytes=$(provenance_evidence_bytes "$observation_file" "$process_output" \
    "$classification_output" "$violation_output") || {
    cleanup_inventory_temps
    return 1
  }
  ((current_bytes + addition_bytes <= maximum_bytes)) || {
    cleanup_inventory_temps
    return 1
  }
  cat "$observation_file" >>"$evidence_dir/host-observations.tsv" || {
    cleanup_inventory_temps
    return 1
  }
  cat "$process_output" >>"$evidence_dir/host-processes.tsv" || {
    cleanup_inventory_temps
    return 1
  }
  cat "$classification_output" >>"$evidence_dir/host-classifications.tsv" || {
    cleanup_inventory_temps
    return 1
  }
  cat "$violation_output" >>"$evidence_dir/host-violations.tsv" || {
    cleanup_inventory_temps
    return 1
  }
  [[ ! -s $violation_output ]] || inventory_valid=false
  cleanup_inventory_temps
  [[ $inventory_valid == true ]]
}
