---
id: tic-5c21
status: closed
type: story
priority: 1
delivery: code
created: 2026-09-10
branch: ticket/tic-5c21-transaction-bindings
base-commit: 56f73a811bcc6c12cd6849cb2e826f19256cb917
worktree: /private/tmp/river-transaction-bindings
---
# Resolve each table once within its admitted transaction

The current four-worker full-mix profile spends 16.06% of sampled request/commit
CPU resolving descriptors: durable name-map scanning and catalog head/manifest
reads precede an existing descriptor-cache lookup. See the latest
[benchmark comparison](../benchmark-log.md). The earlier tic-186e moved catalog
reads into the owning transaction; this ticket removes repeated resolution.

## Scope

Retain each successfully resolved table binding within the admitted relational
transaction. Reuse the existing schema ownership and memory budget, with bounded,
reusable storage and allocation-free warmed hits. Preserve caller pin lifetimes.
Private DDL overlays remain authoritative. Invalidate bindings when owned DDL or
savepoint rollback changes visibility, and release pins on transaction/session
completion, including failures. No cross-transaction SQL cache, new configuration,
benchmark changes, weaker isolation/durability, or storage-format rewrite.

Other repeated calculations discovered during implementation are recorded below
with concrete source/evidence and kept outside this change unless necessary for
this binding owner. Do not create a permanent method inventory.

## Acceptance

Prove repeated lookup avoids catalog/name work, with correct independent pin
release. Cover commit/rollback, DDL rename/drop/recreate or successor visibility,
savepoint rollback, schema admission, resource pressure and session cleanup.
Independent correctness review checks visibility and retained ownership. Run
focused and affected engine tests, slopmark before/after, clean checkpoint and
matched TPS controls/candidates. Profile the same full mix to verify work removed;
keep instrumented throughput separate. Finish with one native lifecycle/recovery
smoke, merge/tag/push and record evidence in the performance ledger.

## Repeated work found during implementation

- `RelationalDescriptorForeignKeyChecks.scan` enumerates every catalog name for
  parent UPDATE/DELETE, then opens each descriptor to discover references.
  The full-mix profile attributed 7.66% inclusive CPU to update FK checks.
  This ticket removes repeated descriptor resolution within that scan. Compiling
  reverse FK dependencies and avoiding checks when referenced keys are unchanged
  are separate follow-up work; preserve constraint/DDL semantics.
- `SqlBindingTableResolver.resolve` calls
  `RelationalDescriptorJoinTableView.prepare` each time: it clears a binder view,
  copies every column name/type/nullability and rebuilds index metadata from an
  immutable descriptor. Consider retaining that derived view with its schema
  identity later; do not add a second binding cache here.
- `IndexedPageFrameCache.reclaimHistorical` scans the configured frame array on
  every commit preflight. It was only 0.35% of sampled full-mix CPU, so it ranks
  behind catalog work despite its larger share in the single-INSERT profile.


## Controls before implementation

At `56f73a81`, unchanged current JVM distribution: four-terminal tiny standard
mix, serializable, seed42, warm5/duration30 using `tools/tps-test.sh`:
450.367 / 416.500 TPS, both capture/reconciliation OK. Artifact root
`/private/tmp/river-tic-5c21/control-{1,2}`; versions
`master-56f73a81-bindings-control-{1,2}`. Preserve this host variation.

External full-mix controls: four workers, sample/all, READ COMMITTED with explicit
FOR UPDATE, one warehouse, seed42, max retries20, warm15/duration30: 307.18 / 303.26
TPS. Both passed invariants, zero failed/unknown outcomes and graceful cleanup.
Commands and report paths: `/private/tmp/river-tic-5c21/full-control-{1,2}.log`.
Executable `/private/tmp/river-maria-20260910-final/river-jvm`, versions
`master-56f73a81-bindings-full-control-{1,2}`. JVM GraalVM25.0.4, -Xmx1g.
Baseline CPU/wall profiles are from the immediately preceding matched diagnostic
at the same source in `/private/tmp/river-maria-20260910-final/`.

## Implementation and review

One session-owned `RelationalDescriptorBindingStorage` retains reusable slots and
published pins, charged to the existing shared shape budget through a runtime
lease. Transaction completion releases pins; session close releases capacity.
Cache pin sharing returns independent caller ownership. Schema admission clears
bindings and active DDL bypasses retention; savepoint rollback invalidates them.
The persistent schema-change entry point now uses the same admission owner.

Independent review covered DDL overlays, admission exclusion, uncertain terminal
outcomes and pin lifetimes. Integrator review rejected per-transaction workspace
allocation, consolidated the budget-release owner, and made internal close retryable.
Focused visibility, pin lifetime, warmed allocation and pressure/retry tests passed.
Affected tests that left helper-created sessions open now close them; the savepoint
budget test fills the actual shared remainder rather than assuming sole ownership.
No correctness assertion or allocation allowance was weakened.

Slopmark: descriptor session 42.3192 → 42.6587; relational session 96.153 → 101.325;
new binding storage 15. The session change adds invalidation to its existing schema
admission/rollback responsibility and removes duplicated persistent admission code;
it does not acquire a new subsystem responsibility. Raw module reports:
`/private/tmp/river-tic-5c21/slopmark-{before,after}.txt`.


## Performance acceptance

Candidate `2dcf5c22` passed the clean full check and installTps checkpoint:
`/private/tmp/river-tic-5c21/clean-check.log`. River-specific candidates were
507.300 / 423.667 TPS against controls 450.367 / 416.500 and a later 349.900
recheck. Host variation prevents a precise claim from those samples.

The targeted full-mix candidates passed at 338.792 / 332.524 TPS against
307.178 / 303.259 controls and an interleaved 286.997 control. Candidate p99
was lower; failed/unknown outcomes remained zero, invariants and cleanup passed.
The repeat CPU profile reduced descriptor resolution from 16.061% to 1.839% of
request/commit samples, confirming the intended work removal. Accept this bounded
change; detailed commands, samples and limitations are in the performance ledger.

The same profile still shows preparation at 16.895% and socket write self time at
15.141%; these are observations for future investigation, not additional scope
or additive end-to-end latency percentages.


## Delivery

The O3/PGO native build passed (`native-build.log`). The actual executable
committed and verified 100 indexed rows, rejected a duplicate, recovered all
acknowledged rows after SIGKILL, and stopped through public `river stop` with
readiness/data cleanup (`native-smoke.log`), under `/private/tmp/river-tic-5c21/`.
Native throughput was not measured for this ticket; performance conclusions are
JVM diagnostics. No remaining blocker. Checkpoint:
`perf-checkpoint-20260910-transaction-bindings`.
