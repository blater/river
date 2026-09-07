---
id: tic-ed12
status: open
type: story
priority: 2
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - performance
    - tpcc
    - p0
    - benchmark
    - provenance
deps:
    - tic-1fe7
links:
    - tic-0636
    - tic-d7c2
created: 2026-09-07T11:32:07.023997Z
---
# Bind TPS diagnostics to the separately built runtime artifact

make.sh produces a verifiable source/build/runtime artifact record; tps-test validates and retains that record without invoking a build. Current publication and consumer validation must reject missing or mismatched provenance without asserting unimplemented host ownership.

## Outcome

A TPS record identifies and verifies the exact runtime produced by a successful
separate `make.sh` invocation. A stale, incomplete, changed or mismatched build
cannot become accepted provenance. TPS never invokes a build.

## Owning mechanism and maximum change shape

Extend the existing make/runtime-descriptor and canonical provenance/publication
path. `make.sh` invalidates the prior completion record before a new attempt,
retains exact build command/result/log and toolchain identity, and publishes a
completed record only after matching before/after source and runtime checks.
Bind ordered classpath entries and bytes, not only a source Git SHA or file
mtime. Prevent an incomplete or concurrent build from reusing a stale success.

`tps-test` verifies the sealed build record against current source and launched
bytes before starting the server/client, rechecks at lifecycle/publication
boundaries, and preserves the build binding with immutable run evidence. Reuse
one canonical manifest, publication and validation owner; do not duplicate
Gradle classpath resolution or semantic evidence policy in shell.

The shared receipt validator and River-owned consumers change together.
Record unsupported host ownership honestly until `tic-d7c2` delivers it;
workload success alone is not promotion eligibility. Remove synthetic lease
claims and reject missing required guarantees in `tps-p4`/P0. Version the
changed live contract and reject its superseded schema rather than maintaining
two readers. Failed/interrupted evidence remains preserved and non-consumable
for promotion. No new metrics framework, workload runner or provider boundary.

## Non-goals

- Restore build execution inside TPS or acquire a lease across separate commands.
- Implement host inventory, ownership enforcement or polling; `tic-d7c2` owns it.
- Change database behavior, workload semantics, retry/isolation/durability,
  capacity settings, P0 statistics or external comparison contracts.
- Introduce a cache service, artifact repository or signing infrastructure.

## Stop conditions

Reject stale source, missing or failed build completion, changed/reordered
classpath, concurrent replacement, mutation at publication, and conflicting
immutable destinations. Stop and resolve any source-to-build binding that
relies only on timestamps, unknown build inputs, or fabricated host facts.
Document the cooperative trust boundary; endpoint checks alone cannot prove
that nonparticipating code never changed and restored bytes during a run.

## Acceptance criteria

Focused real-script fixtures cover successful prebuilt execution with no build
inside TPS, stale classes, source and classpath mutation/reordering, missing
entries/record, failed and interrupted make, incomplete/concurrent publication,
no-overwrite receipts, and current consumer rejection of absent provenance or
host capability. A real serialized smoke reproduces the retained build/source/
runtime hashes. Capture slopmark before/after over touched tooling, independently
review operations/evidence integrity, and complete relevant build-policy checks.
No TPS improvement is expected or claimed from artifact verification.
