---
id: tic-d7c2
status: in_progress
base-commit: e470a5d962bc5f0ad431be544661e3e46b97c871
branch: ticket/tic-d7c2-invocation-host-ownership
type: story
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - performance
    - tpcc
    - p0
    - benchmark
    - operations
deps:
    - tic-ed12
links:
    - tic-ed12
    - tic-0636
    - tic-1fe7
created: 2026-09-04T23:19:29Z
---
# Enforce exclusive-host ownership for P0 diagnostics

Make overlapping River builds and database workloads a fail-closed evidence
condition without perturbing the measured workload.

## Outcome

Every retained P0 diagnostic proves that one canonical host-ownership lease
covered its complete measurement preflight, server, client, cleanup and
protected-payload publication interval, and that no detected unowned in-scope
process overlapped it. The final release attestation is published afterward. Its separately produced
build record proves a distinct build interval
under the same cooperative exclusion domain; no lease transfer or idle-gap
ownership is claimed.

## In Scope / Owning Mechanism

`make.sh` and `tools/tps-test.sh` each acquire the same canonical exclusive-host
lease for their own invocation before source capture or owned process startup,
retain the real identity in their artifact, and release it on success, failure
or interruption. TPS never builds. Artifact matching across the gap is owned
by `tic-ed12`, not a transferred or long-lived lease. A bounded pre/post process
inventory rejects already-active or uncooperative River builds, tests,
profiles, clients, servers, harnesses, and database workloads and distinguishes
idle from busy Gradle daemons.

## Non-goals

- Hash source, toolchain, classpath, or built bytes; `tic-ed12` owns current
  prebuilt provenance and canonical immutable artifact publication.
- Stop user processes, reconfigure Gradle or service managers, provide a
  machine-wide scheduler, or guarantee exclusion from unrelated host activity.
- Poll continuously during the measured phase, tune the database, execute the
  P0 matrix, or turn process observations into a throughput claim.

## Stop Conditions

Stop and reject the run when the lease is unavailable, an in-scope unowned
process is active, ownership changes unexpectedly, or full-interval cooperative
ownership cannot be established. If the supported host cannot distinguish an
idle Gradle daemon from active build work without measured-phase polling, mark
the platform unsupported for promotion evidence rather than adding a sampler.

## Maximum Change Shape

Wire the existing exclusive-host lease protocol and one bounded process-
inventory adapter around the separate make and TPS lifecycles, with cleanup
traps and focused race fixtures. Replace superseded unused monitor code and
tests in the same delivery; do not add a second lease or receipt path. Do not add a daemon, process killer, second lock protocol, workload
runner, or database lifecycle implementation.

## Design

Acquire exclusive ownership before preflight and retain it until protected
provenance payload publication completes. Preserve the accepted final receipt
after exact-owner release; that attestation is outside the protected interval. Fail closed on an existing owner or detected in-scope
activity, validate ownership again at lifecycle boundaries, and preserve the
ownership record for failed and interrupted runs. Keep all checks outside the
measured server path and never expose command-line secrets.

## Acceptance Criteria

Focused tests cover uncontended ownership, competing invocations, stale-owner
recovery, active and idle Gradle daemons, each declared River process family,
an uncooperative-process race at lifecycle boundaries, interruption, failed
build or workload cleanup, and non-owner release refusal. Each retained record
identifies its actual owner for its complete invocation interval, and exclusion adds no
measured-phase polling, server allocation, or server control-flow change.

## Notes

### 2026-09-05 accepted-provenance reconciliation

`tic-0636` closed with the existing cooperative lease, bounded periodic host
observations, and v2 terminal receipts. Start by identifying the exact gap
between that accepted implementation and this ticket's prohibition on polling
during the measured phase. Reuse the canonical lease and receipt/publication path;
replace superseded host observation behavior in the same delivery. Do not
reimplement launched-byte provenance or claim the stronger host contract from
the historical `tic-0636` evidence.

### 2026-09-07 current-workflow reconciliation

The 2026-09-05 notes above describe the historical delivery, not current wiring.
`866f1f4` separated `make.sh`; `8609d49` removed the host/classpath gates.
`tic-1fe7` preserves the separate build workflow, and `tic-ed12` now owns its
artifact binding and receipt truthfulness. This ticket consumes that contract
and wires real build/measurement ownership with bounded boundary observations.
Do not restore periodic measured-phase polling or pretend that empty ledgers
prove an acquired lease. The exclusion claim covers participating workflows;
nonparticipant absence between observations and unrelated user load are not
proved. Unsupported idle/busy daemon classification must fail closed for
promotion evidence as specified above.

## Lead implementation contract (2026-09-07)

Outcome: separately invoked make and TPS each own one cooperative host interval,
with bounded process observations outside measurement. Retained build and TPS
records attest the real interval. P4 consumes the same canonical validator.
No database behavior, workload, resource admission, durability or TPS claim.

Ownership: reuse the existing lease protocol and provenance publication owner.
Use one canonical host lease path independent of checkout and per-process TMPDIR;
production must not allow a per-invocation path override to bypass exclusion.
Keep test injection inside fixtures, not a new production bypass. No idle-gap
lease transfer. Make acquires before descriptor invalidation/source capture or
build launch. TPS acquires before source capture or server/client launch.

Observe only at boundaries with no owned build active: make before launch and
after its runner exits; TPS before source capture, before client launch and after
runner/server cleanup, before immutable evidence publication. Revalidate exact
lease ownership at existing provenance checkpoints. No background monitor,
measured polling, profiler, second executor, daemon or process killer. Delete
unused periodic monitor/marker/provisional-daemon behavior and its tests.
Since observations never overlap the owned build, observed Gradle daemons must
be demonstrably idle; no provisional busy-daemon allowance is needed.

One inventory adapter owns normalization/classification and admitted byte/time
budgets. Reuse existing bounded collectors and PID/start identities. Retain only
normalized process identities and classified outcomes, never raw command lines
or system-property values. Unknown/truncated/timed-out inventory or unknown
Gradle state fails closed. Gradle owns daemon-state classification: use the pinned wrapper's bounded
`--status` query, never a build command or stack heuristic. Actual local probes
show PID 5450 IDLE, BUSY during the controlled task, then IDLE after joining it.
Make's status query uses its normal Gradle context; the descriptor adds the
Gradle-owned `gradle.user.home` fact so separate TPS uses the same registry.
No copied Gradle home/default-resolution rules. An observed daemon absent from
that registry/current-version response is unknown and rejects admission. Remove
the superseded jcmd state/home heuristics and provisional-daemon validator.

The exclusion claim covers participating workflows under the canonical lease
and unowned in-scope processes detected at boundaries. It does not prove absence
of nonparticipants between observations, unrelated host load, clock stability,
or whole-machine exclusivity. Do not stop external/user processes. An observed foreign workload is a failed
admission, not authority to kill it.

Make/TPS retain bounded sanitized host facts in their existing immutable records.
All protected source/runtime/workload/host payloads and provisional metadata are
published while the lease is held. Then exact-owner release occurs; the final
no-replace completion/terminal attestation records release outside the protected
interval. This preserves the accepted 0636 nonce/commitment design; clarify
'publication' in ticket wording rather than inventing another receipt protocol.
Missing/failed release or incomplete publication cannot create accepted success.
Both build and TPS intervals must qualify for P4; no synthetic ownership or old
unsupported-success branch survives. Change current writers/readers/fixtures
in one delivery, without dual-schema readers.

Concurrency review concern: existing stale recovery and release validate a path,
then unlink owner through that path. A competing proper reclaimer could replace
the directory between validation and unlink. Bind namespace mutation to the
observed directory (for example a checked working-directory handle with relative
owner operations) so an old reclaimer cannot unlink the replacement owner's
record. Only the actor that successfully unlinks the observed owner may attempt
directory removal; an unlink failure returns without another path mutation.
Proper contenders never remove ownerless/initializing directories. This invariant
must protect the replacement directory too. After acquiring/reclaiming the lease,
complete admission inventory before any protected mutation or work: surviving
orphan build/workload processes and unknown state reject admission. Final cleanup
must explicitly reject surviving owned runner/server/client activity; the normal
owned-process exemption cannot hide leaked descendants. Prove the race using real fixture interleaving. Reuse the lease owner;
do not add another lock protocol. Escalate a concrete unsatisfied invariant to
the lead before redesigning the mechanism.

Required proof: uncontended independent build/TPS intervals, same-domain competing
invocations and differing TMPDIRs, stale recovery and replacement-owner race,
non-owner release refusal, busy/idle/unknown Gradle, declared process families,
pre/post race, bounded capture/storage, interrupted/failed make and TPS, failed
release/publication, positive qualified diagnostic/P4 and missing-host rejection.
Do not edit executing production/test scripts; isolated fixtures may be materialized as test data. Root owns the build/workload queue;
no agent runs tests/builds/profilers until root schedules them. Start from e470a5d,
record slopmark's unsupported shell limitation, retain paired TPS diagnostics and
run relevant clean/policy checkpoint with existing failures reported honestly.

Implementation ownership (after baseline and final contract acceptance): one Luna
builder owns make.sh and the five existing TPS/provenance/P4 scripts including
their two test scripts. Root owns ticket, evidence ledger, delivery map and scope.
A separate Luna reviewer reviews concurrency, truthful evidence and system-wide
assumptions. The only planned Kotlin edit is the descriptor's immediate-consumer Gradle
user-home fact. No Java/module or unrelated documentation changes are planned.
Current schema replacement: runtime-v3, build-v2, tool-v4, terminal-v3, P4-v4.
Use the existing completion object for the build's final post-release attestation:
prepare/publish payload hashes under lease, then complete last after release.
Do not introduce a separate host receipt alongside it. Keep host validation and
acceptance policy under the existing shared provenance owner.


### Preimplementation evidence (2026-09-07)

Clean `e470a5d` controls recorded 159.300 and 163.900 committed TPS, zero
retries/errors, successful invariants and capture, and complete cleanup. Each
used seed 42, tiny standard mix, serializable isolation, ten terminals, one
warehouse, synchronous WAL, one-second warmup and ten-second measurement.
The user confirms AC power; no power probe is required. Raw evidence is
`/private/tmp/river-tic-d7c2-evidence-20260907/baseline-1` and `baseline-2`.
These legacy controls do not certify the new invocation-ownership contract.
Slopmark does not support the touched shell/Kotlin source; no numeric score is
available for this slice.

The independent stale-reclaimer probe reproduced two successful owners with
the old `e470a5d` helper: A paused before stale-owner unlink, B reclaimed and
published its replacement, then A removed B's owner and reported acquired.
Evidence: `race-probe/run.kNbskB/result.properties` below the same evidence
root. Worker PIDs and filesystem replacement were real; the sandbox denied
`ps`, so start identities came from a probe-local fixture. This proves the
filesystem interleaving, not the operating-system identity adapter. Candidate
validation must prove that A fails and B's owner and directory survive.


### User-directed Gradle lifecycle change (2026-09-07)

The user requires all subsequent Gradle invocations to use `--no-daemon`.
This supersedes the earlier daemon-backed iteration instruction. `make.sh`
and the bounded status query pass the flag explicitly; repository defaults
and the working agreement match. Existing unrelated idle daemons remain an
observation concern, not authority to stop user processes. Final build and TPS
validation uses this updated contract. Earlier controls used the former build
lifecycle and remain diagnostic controls; no build-time performance claim is
made across that change.


### User-directed review economy (2026-09-07)

The user requires 50–75% less provenance overhead in future work. The working
agreement now limits ordinary provenance overhead to eight minutes and review
of explicitly requested provenance changes to fifteen minutes, with one scoped
review and re-review only of demonstrated blockers. Count reconciliation and
fixture rework rather than hiding them outside the review total. Known invalid
evidence still blocks acceptance. This slice has source approval after the
known timeout/publication corrections; proceed to the existing focused suites
and real make/TPS validation without opening another broad review.
