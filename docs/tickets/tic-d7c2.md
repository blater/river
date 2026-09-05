---
id: tic-d7c2
status: open
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
    - tic-0636
created: 2026-09-04T23:19:29Z
---
# Enforce exclusive-host ownership for P0 diagnostics

Make overlapping River builds and database workloads a fail-closed evidence
condition without perturbing the measured workload.

## Outcome

Every retained P0 diagnostic proves that one canonical host-ownership lease
covered its complete build, server, client, and publication interval, and that
no detected unowned in-scope process overlapped it.

## In Scope / Owning Mechanism

`tools/tps-test.sh` acquires one canonical exclusive-host lease before any
build or workload process starts, retains its identity in the artifact, and
releases it on success, failure, or interruption. A bounded pre/post process
inventory rejects already-active or uncooperative River builds, tests,
profiles, clients, servers, harnesses, and database workloads and distinguishes
idle from busy Gradle daemons.

## Non-goals

- Hash source, toolchain, classpath, or built bytes; `tic-0636` owns provenance
  and immutable artifact publication.
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

One exclusive-host lease protocol and one bounded process-inventory adapter may
be added around the existing TPS lifecycle, with cleanup traps and focused race
fixtures. Do not add a daemon, process killer, second lock protocol, workload
runner, or database lifecycle implementation.

## Design

Acquire exclusive ownership before preflight and retain it until provenance
publication completes. Fail closed on an existing owner or detected in-scope
activity, validate ownership again at lifecycle boundaries, and preserve the
ownership record for failed and interrupted runs. Keep all checks outside the
measured server path and never expose command-line secrets.

## Acceptance Criteria

Focused tests cover uncontended ownership, competing invocations, stale-owner
recovery, active and idle Gradle daemons, each declared River process family,
an uncooperative-process race at lifecycle boundaries, interruption, failed
build or workload cleanup, and non-owner release refusal. The retained record
identifies one owner for the complete interval, and enabled exclusion adds no
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
