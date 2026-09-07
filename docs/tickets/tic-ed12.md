---
id: tic-ed12
status: in_progress
base-commit: 04d4c09c67f30c1330da1c0833e53618b9866a07
branch: ticket/tic-ed12-prebuilt-tps-provenance
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

## Pre-implementation proof boundary

The handoff uses a required, fresh make invocation ID as a declared descriptor
task input. A completion record belongs to that ID and is published last.
The descriptor and its ID, source record, exact ordered runtime manifest and
sealed completion record must still match at every required TPS boundary.

Record actual selected JavaCompile toolchain identities separately from the
Gradle daemon JVM. The executable hash identifies the javac launcher, and the
selected-options summary covers release, encoding and allCompilerArgs. These
are observed facts, not a digest of the entire JDK, full compiler invocation or
every compile-only/annotation-processor dependency. Keep normal Gradle incremental
and build-cache behavior: provenance explicitly trusts Gradle's declared-input
and cache correctness, retains task outcomes/logs, and binds the actual output
bytes. It does not claim clean compilation or hermetic reproducibility.

The initial supported input contract is the repository's declared build, its
verified resolved dependencies, selected compiler toolchains, and the fixed
make invocation. External user/distribution init scripts or user Gradle
properties and nonempty Gradle/JVM/project-property injection are unsupported
until explicitly admitted. Source symlinks must not silently contribute only
link-text identity where target bytes are compilation inputs. Unknown input
authority must make provenance unsupported, never silently valid. Any retained
environment/configuration fact uses safe identity or hashes, not secret values.

The implementation must resolve this input check through the existing build
authority without copying Gradle's init-script or classpath discovery rules
into shell. Failure to expose the admitted input/toolchain facts through that
authority is a stop condition for a named design decision, not permission to
weaken the provenance claim.

## Implementation and review

The existing Gradle descriptor now reports a fresh build invocation, selected
compiler facts, and the admitted input/cache contract. `make.sh` retains its
command, result, source boundaries, and ordered runtime bytes, publishes
completion last, and cleans up only its own build process group on interruption.
TPS consumes this record without building and rechecks it at lifecycle boundaries.
The canonical receipt validator and P4 consumer migrate together; current
host ownership remains explicitly unsupported.

Independent reviews identified and resolved owned-child cleanup, classpath
metadata binding, late-mutation failure reporting, symmetric cache-trust
validation, and Gradle system-property input classification. The lead's systems
review rejected a proposed common build-ID requirement: invocation identity is
unique by design, while comparison checks source bytes, ordered launched bytes,
and runtime launcher identity. Separate builds with equivalent runtime inputs
must not be rejected solely because their evidence identities differ.

Validation evidence is retained at
`/private/tmp/river-tic-ed12-evidence-20260907`. Java test outcomes restored by
the clean check are explicitly labelled as cached; shell boundary suites execute
fresh. Existing source/bytecode, SQL-shape and dependency-ledger policy failures
are compared with unchanged `04d4c09`, not waived or represented as passing.
The installed slopmark does not support shell/Kotlin, so architecture review
supplies the review signal without a fabricated score. This remains required
evidence correctness work; no database behavior or TPS improvement is claimed.
