---
id: tic-0b7e
status: closed
type: story
priority: 1
assignee: blater
parent: tic-5db4
delivery: code
branch: fix/tps-run-labels
tags:
    - performance
    - benchmark
    - cleanup
created: 2026-09-08T00:00:00Z
---
# Remove runtime descriptor machinery from TPS diagnostics

Remove the runtime descriptor, provenance, host-lease, source/workspace
fingerprint, build-seal, and terminal-receipt machinery from the TPS build and
run path. Keep the workflow simple: `make.sh` builds the runnable benchmark
distribution through `:river-bench:installTps`, and `tools/tps-test.sh` runs the
distribution without invoking Gradle or inspecting source files.

## Outcome

TPS diagnostics identify a run with the current branch name by default. The
runner accepts `--version=<meaningful-variation>` for multiple variants on one
branch and records that exact value in the TPS log or artifact. The recorded
workload configuration, result, correctness checks, resource checks,
and owned-resource cleanup remain part of the diagnostic contract.

## Scope

Delete the superseded runtime descriptor and provenance code, tests, temporary
state, and duplicate validation paths. Update River-owned callers and tests in
the same delivery. Keep the normal correctness and resource cleanup checks;
they must run on success, failure, and interruption. Do not add a replacement
attestation or compatibility reader for the removed machinery.

## Acceptance criteria

- `make.sh` produces the runnable TPS distribution using
  `:river-bench:installTps`.
- `tools/tps-test.sh` consumes that distribution without a Gradle invocation,
  source/workspace verification, runtime descriptor, build seal, host lease,
  or terminal receipt.
- The default version is the current Git branch; `--version` overrides it and
  the selected value is visible in the retained TPS log or artifact.
- Runs retain the version and workload/resource configuration and preserve
  correctness, failure reporting, and cleanup of the owned database, server,
  temporary files, and JFR processes.

The closed `tic-0636`, `tic-ed12`, and `tic-d7c2` tickets remain historical
delivery records. Their measurements and artifacts are not rewritten; their
runtime descriptor and provenance contracts are superseded by this ticket.

## Validation (2026-09-08)

- `./make.sh`: passed with `--no-daemon`, producing ordinary runner JARs.
- Two-second sample with `--version=descriptor-removal`, four terminals,
  seed 42: completed with zero errors; deadlock reconciliation and performance
  capture both OK. Evidence: `/private/tmp/tps-simple-smoke-2/` and
  `/private/tmp/tps-simple-smoke-2.log`. This is functional smoke evidence,
  not a TPS improvement claim.
- Occupied-port check: default branch label recorded, `SERVER_NOT_READY`, exit 1.
- Interruption check: owned processes stopped, `INTERRUPTED`, exit 143.
  Evidence: `/private/tmp/tps-simple-occupied/` and
  `/private/tmp/tps-simple-interrupt/`.
- Six P4 workload/metadata fixtures passed; interleave dry-run records explicit
  variation labels; shell syntax and whitespace checks passed.
- Removed the old generated descriptor and build-record directories from the
  active checkouts. Historical run evidence outside the build tree is retained.

The first smoke run was invalidated by editing the shell script while it was
running; it failed with a shell syntax error. The completed second run above
used the finished script. No failed sample is reported as successful.

Independent focused review found an early temporary-directory leak on invalid
output arguments. An initial cleanup trap fixes it; validation confirms that
an existing artifact is preserved and no runner directory remains. No
replacement provenance checks were added.
