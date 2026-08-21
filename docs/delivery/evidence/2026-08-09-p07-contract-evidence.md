# P07 status, ownership, and observability evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Evidence class: reviewed local contract evidence; not P07 or G0 promotion
evidence

## Implemented contracts

`river-base` provides stable status families/codes, caller-owned bounded status
detail, cooperative cancellation, optional close and ownership guards, a
first-failure-wins fatal-state fence, and dependency-neutral boundary identity
types. Routine expected failures return status values; `RiverException` is a
cold public-boundary adapter.

`river-observability-api` provides a versioned event and metric registry,
fixed-field reusable events, singleton disabled sinks, severity gates,
redacted safe-export views, bounded MPSC event rings, explicit drop accounting,
and guarded versus lifecycle-proven producer modes. Audit remains a separate
durable/security concern.

## Independent review corrections

The base review rejected irreversible-lifetime, retry, fatal-I/O, allocation,
and boundary-semantic gaps. Corrections made release terminal, made retryability
code-specific, preserved contextual I/O as the fatal cause without globally
classifying every I/O error fatal, expanded race and boundary coverage, and
documented identity wrappers as control/boundary values until hot-path packed
representations are measured.

The observability review required explicit queue ownership, publication-hole
accounting, honest drop semantics, frozen registry fixtures and tombstones,
safe exporter defaults, active-path allocation checks, and concurrent
wrap/saturation coverage. Those corrections were independently reviewed before
integration.

## Allocation evidence

Local warmed `ThreadMXBean` tests cover:

- disabled diagnostic, event, gated-event, and metric calls with exactly zero
  measured bytes across one million iterations;
- active publish/poll and saturation accounting within a 256-byte total
  measurement-noise allowance across one million iterations; and
- reusable status detail, status codes, cancellation, close, ownership, and
  fatal-admission paths within the same 256-byte total allowance across one
  million iterations.

These are mechanism regressions on the developer JVM. They do not establish an
accepted production allocation budget or contention latency envelope.

## WAL-generation follow-up validation

The first concrete local-WAL consumer now uses `WalGeneration` from
`river-base` rather than a raw `long`. `JournalAppendResult`,
`DurabilityResult`, `JournalPositionMapping`, `WalRecordRange`,
`DurableWalEnd`, and the deterministic provider all preserve that unit. The
provider retains one validated immutable value and reusable carriers return the
same reference; the warmed reserve/publish/wait/force regression reads both
append and durability typed accessors within its existing 256-byte total
measurement-noise allowance.

Local validation on 2026-08-09:

- focused base, journal API, testkit, source-policy, and module-graph gate:
  46 tasks, all successful;
- full `./verify --rerun-tasks`: two 99-task exact archive builds compared
  equal, followed by 149 successful check tasks; and
- final test reports: 220 tests, zero failures, errors, or skips.

Strict independent correctness/allocation review found the follow-up safe: all
extant raw local-WAL-generation seams are typed, the warmed allocation test ran
without a skip, lineage and stale-output tests fail closed, and the scope leaves
`NodeIncarnation` journal-owned without adding unused identities. These local
results do not select a packed, durable, or wire encoding and do not change a
gate or tag.

## Remaining P07 work

- Keep durable/packed identity encodings with ADR 0004/K02. The journal
  contract is the first consumer to require `WalGeneration`; its public append,
  durability, mapping, and range seams now use the dependency-neutral semantic
  type while reusable providers retain one validated value. Unused `FileId`
  and `BackupManifestId` wrappers remain deliberately deferred.
- Extend the integrated exact-method bytecode manifest when concrete WAL,
  buffer, storage, transaction-commit, and vector hot methods are implemented;
  those extensions validate their owning later gates rather than P07/G0.
- Calibrate numeric allocation, queue, contention, and tail-latency budgets on
  the P05 runner and record an independent final review.
- Keep cancellation generation/reset ownership as a required consumer test when
  the session and protocol lifecycles exist. It validates those later slices and
  is not a P07/G0 prerequisite; the base token already requires prior users to
  quiesce before reset.

## Promotion decision

P07 is `implemented`, not `passed`. Its status, diagnostics, ownership,
observability, allocation regressions, initial observability bytecode checks,
and first-consumer `WalGeneration` seam are implemented and independently
reviewed. Declared-host numeric budgets, upstream P02/P03 promotion, and final
promotion review still block `passed`. Future semantic types and exact-method
coverage belong to the gate implementing each consumer or hot path. No G0/M0
status or tag change is authorized.
