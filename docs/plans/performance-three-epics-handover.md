# Performance epics: lead engineer handover

Date: 2026-09-13. Planning ticket: [tic-lalaith](../tickets/tic-lalaith.md).
Source reviewed: `5f7e6eb0`. No production changes or new benchmark runs are part
of this backlog delivery. Rebaseline from the latest pushed stable source when
implementation begins. This document owns the current cross-epic sequence and
shared admission/review contract; individual tickets own their deliverables.

## Current scheduling override — 2026-09-14

Execution and protocol epics are closed. The user now prioritizes impactful WAL
implementation: pursue the existing prerequisites for tic-f1bb force overlap,
with tic-5b3e as its resource-safety enabler. Defer the conditional 4d14 → 845d
lock stream until the overlap decision; retain all completion dependencies.
The [current backlog](../backlog-kanban.md#impact-first-wal-sequence--2026-09-14)
owns the detailed order. The user explicitly deferred P0 scaling/accounting and
removed tic-1dda from WAL admission; the remaining WAL safety gates still apply. Historical ready-frontier and
cross-epic sequencing statements below do not override it. Keep existing scope
and gates; obtain explicit scope agreement before adding a discovered follow-up.

## Decision and ownership

| Epic | Lead responsibility | First actionable work | Completion gate |
| --- | --- | --- | --- |
| [tic-carcharoth](../tickets/tic-carcharoth.md): reusable execution metadata | Relational execution / allocation | tic-da4e current-source profile | tic-miriel |
| [tic-rowlie](../tickets/tic-rowlie.md): commit-force overlap | Storage/recovery and concurrency | Preserve active tic-1dda P0 investigation; then its dependent audits | tic-7a5a |
| [tic-primula](../tickets/tic-primula.md): protocol waits and writes | Boundary/protocol with Java and Go consumer owners | tic-edoras after tic-da4e | tic-rian |

User preference is execution → overlap → protocol. Whole epics do not block one
another merely to enforce preference. Shared evidence can inform independent
work; only dependencies needed by a concrete contract belong in ticket frontmatter.
`tic-da4e` is the immediate implementation-admission frontier. The evidence-only
contract definition tic-8561 is also ready and may predeclare the later formal
campaign without waiting for optimization completion. `tic-1dda` is already in progress:
inspect its recorded claim and evidence rather than stealing or restarting it.
Do not claim epics as coding tasks or interpret an open epic as a ready story.

## Complete delivery map and rolling waves

Execution: profile → four independent mechanism candidates (tic-eowyn immutable
binding views; tic-bracegirdle reverse-FK discovery; tic-telemnar early unchanged-key
discovery avoidance; tic-twofoot active-use scratch reset) → tic-miriel integrated
evidence. The unchanged-key and reverse-discovery candidates do not automatically
depend on each other. Shared files still require serial implementation ownership.

WAL: follow the amended graph, including tic-ca05 → tic-5b3e →
tic-6f81 → tic-92e3 → tic-f1bb, accepted historical b368/7352 inputs, and
4d14 → 845d. tic-7a5a evaluates the composed mechanisms. Existing failure
criteria and independent reviews are retained. Audits may prove current code
already satisfies a requirement; do not invent an implementation to keep them open.

Protocol: profile → tic-edoras contract/consumer admission → tic-gwindor prepared
handle reuse and tic-morgoth bounded ordered pipelining/coalescing → tic-rian.
The lead serializes the code stories if they touch the same exchange/adapter owner.
There is no forced Payment program or one-request requirement. A program feature
is admitted only if the current contract investigation identifies a real consumer
and justifies a separately reviewed owning-boundary change.

These maps expose the whole epic without pretending that unmeasured mechanisms
are ready to code. Before starting a downstream story, its dependency must record
an affirmative mechanism decision, exact affected production path, required
resource/lifetime contract, focused test and predicted denominator movement. If
an investigation rejects a candidate, add a reviewed explicit disposition and
replacement links, update promotion dependencies, and close it as rejected or
superseded evidence rather than claim code was delivered. If a new prerequisite
is discovered, create that narrowly scoped ticket and add a real dependency before
coding. Do not proliferate speculative infrastructure, cache layers or telemetry.

## What already exists

- tic-7a32 shares server plans; tic-5c21 retains transaction bindings; tic-6f28
  removes prepared-close replies. The latter is still in progress because its
  external publication is unresolved; preserve that status and owner.
- `RelationalDescriptorReferenceCheck.changed` already suppresses unchanged-key
  probes. The new hypothesis is avoiding the *earlier* catalog enumeration.
- Group publication, lock handoff and observed-read durability barriers precede
  force already. The remaining overlap concerns successor physical work while
  the canonical writer is blocked. The 92e3 provider contract must cover mapped
  WAL/range sync/atomic group footer, not only historical positional writes.
- Historical profiling and the M5 baseline suggest candidates; neither proves
  their present cost. Native compiler blocker tic-ae17 remains separate. Use an
  explicitly labeled JVM path until native build/recovery evidence is available.

## Shared implementation and acceptance contract

One lead owns integration; add only builders needed by the actual admitted slice.
Use independent adversarial lenses: relational/DDL ownership for execution,
concurrency/recovery for WAL, boundary/security and relational ordering for protocol.
Each story is one coherent source/rollback boundary. Replace superseded internal
APIs and all River-owned callers together. No compatibility wrappers, duplicate
executor/queue/outcome policy, arbitrary cardinality caps or per-row allocations.

Start from the latest pushed stable tag, record source/branch/tool configuration,
and follow AGENTS.md for targeted --no-daemon builds and clean accepted checkpoints.
One build or workload at a time on the host; parallel builders require disjoint
files and isolated worktrees/caches. Use slopmark as a review trigger, not a metric
to game. Test material success and failure boundaries, allocation/copy ownership,
configured-budget pressure and cleanup; expand only for affected behavior.

For every admitted optimization, retain at least two identical short before and
after samples. The local anchor is sample/all, 4 workers, 1 warehouse, seed42,
retries20, 5s warmup/30s measurement, same JVM/resource settings and public server
lifecycle. Use a focused family first where it answers the mechanism. Profiled
samples are separate from uninstrumented timing. Longer interleaved controls are
required for a performance claim or unexplained repeated TPS/p99 shifts; the
accepted campaign records its duration/repetitions before observing candidate
results and increases sampling only to resolve uncertainty, not to select wins.

All samples must pass invariants, zero failed/unknown outcomes, exact owned-server
cleanup and retry accounting. Report TPS/p99 plus per-attempt and per-commit work;
explain retry shifts. Do not sum overlapping CPU percentages or predicted speedups.
Direct optimizations need mechanism movement and repeatable useful benefit;
correctness/resource enablers may be neutral if explicitly labeled. A no-benefit
change is rejected/reverted, not retained as an optimization for speculative value.
Record individual samples, exact commands, immutable artifact paths, independent
review, clean checks and decisions in docs/performance-checkpoints.md. Accepted
performance features use merge commits, annotated checkpoint tags and pushes.

## Cross-database and cross-repository boundaries

The September11 M5 diagnostic used River TCP/TLS and MariaDB Unix sockets; its
roughly447/1126 median TPS is not engine-only parity evidence. Match transport/TLS,
transaction isolation as actually set, commit-flush contract, SQL mix, retries,
resource settings and data manifest before interpreting a gap. If configurations
cannot be matched, label the comparison's limited question and do not certify
parity. Never weaken security, durability or isolation merely to improve a score.

River owns database behavior and public interfaces. The external river-harness
owns equivalent workload execution and its Go adapter; a separately versioned
sidecar owns comparisons. Do not put benchmark-family semantics or comparison
thresholds into River or move the comparator into the harness. Protocol changes
must name, test and deliver both Java and Go consumers. Audit the existing harness
publication limitation before claiming interoperability complete; unresolved
external delivery blocks only the affected acceptance boundary.

The existing tic-45a7 lifecycle certification and tic-61c2/tic-e305 sidecar work
continue under their existing owners. They gate formal claims, not ordinary
functional/diagnostic harness runs. tic-c7bb and actual campaign tic-630d now depend on the three real
integrated evidence gates instead of retired tic-723f. Contract definition
tic-8561 uses those as design inputs, not false completion prerequisites. tic-9c58 and its existing
parity/Alpha3 children remain the formal comparison owner. Inspect evidence
outcomes in addition to graph status: closing a superseded epic is not passing P1
or the 500-TPS/parity campaign. The 500-TPS milestone is retained pending its
existing contract owner; current short samples do not certify it.

## Backlog disposition audit

Scope: every ticket that was open or in progress at the start of this delivery.
Closed accepted tickets remain immutable history. In-progress unrelated work is
not reclaimed, reparented or silently certified. Retained items below remain
outside the three epics unless their current parent is stated.

| Ticket | Previous state / parent | Disposition |
| --- | --- | --- |
| [tic-00e1](../tickets/tic-00e1.md) | open / tic-723f | Supersede unconditional Payment pilot with tic-edoras; tic-morgoth owns only a subsequently admitted generic transport implementation. No Payment code delivery claimed. |
| [tic-1dda](../tickets/tic-1dda.md) | in_progress / tic-5db4 | Retain P0 ownership, failed criteria and active claim; hard overlap prerequisites unchanged. |
| [tic-2109](../tickets/tic-2109.md) | open / tic-e1c9 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-229c](../tickets/tic-229c.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-25cf](../tickets/tic-25cf.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-30c3](../tickets/tic-30c3.md) | open / root | Retain umbrella; update strategy and diagnostic baseline. |
| [tic-348c](../tickets/tic-348c.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-35dd](../tickets/tic-35dd.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-3c7d](../tickets/tic-3c7d.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-3f57](../tickets/tic-3f57.md) | open / tic-761e | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-45a7](../tickets/tic-45a7.md) | open / tic-761e | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-4944](../tickets/tic-4944.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-4d14](../tickets/tic-4d14.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-4ec3](../tickets/tic-4ec3.md) | open / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-5b3e](../tickets/tic-5b3e.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-5db4](../tickets/tic-5db4.md) | open / tic-30c3 | Retain P0 ownership, failed criteria and active claim; hard overlap prerequisites unchanged. |
| [tic-615d](../tickets/tic-615d.md) | in_progress / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-61c2](../tickets/tic-61c2.md) | open / tic-9c58 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-630d](../tickets/tic-630d.md) | open / tic-c7bb | Retain campaign; explicitly require tic-miriel, tic-7a5a, tic-rian and tic-45a7 in addition to external automation tic-bd79. |
| [tic-69c5](../tickets/tic-69c5.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-6a7f](../tickets/tic-6a7f.md) | in_progress / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-6c82](../tickets/tic-6c82.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-6f28](../tickets/tic-6f28.md) | in_progress / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-6f81](../tickets/tic-6f81.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-701f](../tickets/tic-701f.md) | open / tic-ef07 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-723f](../tickets/tic-723f.md) | open / tic-30c3 | Supersede with tic-carcharoth and tic-primula; replace downstream hard dependencies with their real evidence gates plus tic-7a5a. |
| [tic-72ea](../tickets/tic-72ea.md) | open / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-761e](../tickets/tic-761e.md) | open / tic-e1c9 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-7a5a](../tickets/tic-7a5a.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-7ca1](../tickets/tic-7ca1.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-7ec5](../tickets/tic-7ec5.md) | open / tic-9c58 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-814d](../tickets/tic-814d.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-845d](../tickets/tic-845d.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-8561](../tickets/tic-8561.md) | open / tic-c7bb | Retain contract definition; remove false completed-optimization/lifecycle prerequisites here and preserve them on actual campaign tic-630d and epic tic-c7bb. |
| [tic-91e1](../tickets/tic-91e1.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-92e3](../tickets/tic-92e3.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-95e8](../tickets/tic-95e8.md) | open / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-9640](../tickets/tic-9640.md) | open / tic-2109 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-9c58](../tickets/tic-9c58.md) | open / tic-30c3 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-a133](../tickets/tic-a133.md) | open / tic-9c58 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-a459](../tickets/tic-a459.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-ae17](../tickets/tic-ae17.md) | open / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-af0a](../tickets/tic-af0a.md) | open / tic-723f | Supersede unconditional Payment pilot with tic-edoras; tic-morgoth owns only a subsequently admitted generic transport implementation. No Payment code delivery claimed. |
| [tic-b089](../tickets/tic-b089.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-b1a1](../tickets/tic-b1a1.md) | open / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-b75d](../tickets/tic-b75d.md) | in_progress / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-b901](../tickets/tic-b901.md) | open / tic-2109 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-bd79](../tickets/tic-bd79.md) | open / tic-c7bb | Retain parent; correct producer/sidecar ownership and add real tic-e305 delivery prerequisite for gate verification. |
| [tic-bf0b](../tickets/tic-bf0b.md) | open / tic-e1c9 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-c7bb](../tickets/tic-c7bb.md) | open / tic-30c3 | Retain formal milestone; rewire retired phase dependency to tic-miriel, tic-7a5a and tic-rian; preserve lifecycle gate. |
| [tic-c8d1](../tickets/tic-c8d1.md) | in_progress / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-ca05](../tickets/tic-ca05.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-cc41](../tickets/tic-cc41.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-ccb9](../tickets/tic-ccb9.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-da4e](../tickets/tic-da4e.md) | open / tic-723f | Move to tic-carcharoth; replace post-Payment profile scope with immediate shared source admission. |
| [tic-dd80](../tickets/tic-dd80.md) | open / tic-ef07 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-e1c9](../tickets/tic-e1c9.md) | open / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-e2b7](../tickets/tic-e2b7.md) | open / tic-e1c9 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-e305](../tickets/tic-e305.md) | open / tic-9c58 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-e5ff](../tickets/tic-e5ff.md) | open / tic-30c3 | Supersede with tic-rowlie; move all open children; keep closed children as history. |
| [tic-e6c5](../tickets/tic-e6c5.md) | open / tic-9c58 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-ec50](../tickets/tic-ec50.md) | in_progress / tic-bf0b | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-ef07](../tickets/tic-ef07.md) | open / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-effc](../tickets/tic-effc.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-f1bb](../tickets/tic-f1bb.md) | open / tic-e5ff | Move to tic-rowlie; preserve dependency and correctness contract; refresh source assumptions where noted. |
| [tic-f737](../tickets/tic-f737.md) | open / root | Retain current parent, scope, state and dependencies; outside this performance reorganization. |
| [tic-f805](../tickets/tic-f805.md) | open / tic-c8d1 | Retain current parent, scope, state and dependencies; outside this performance reorganization. |

## Tool and graph verification

Ticket creation, status changes and validation use the clean local ~/src/ticket v0.6.0 source at
`93b3e2f128ad008ebda2faaae55e0b3702f7eabd`. The installed executable reports dev;
a temporary executable `/private/tmp/river-tk-0.6.0` was built from that tagged
checkout and reports `tk 0.6.0`. PATH/global installation is unchanged. That
version uses named IDs; older hexadecimal ticket IDs remain supported. The CLI serializer only preserves
canonical top-level sections reliably; custom sections in touched tickets use
level-three headings within the description. Temporary-copy CLI rewrite checks
verify all ticket body text survives future status/note operations.

The unchanged repository already has153 ticket-validation findings, chiefly
missing delivery metadata on historically closed tickets. Baseline JSON is
`/private/tmp/river-ticket-v060-validation-before.json`. Do not fabricate delivery
links or rewrite unrelated history to make this planning change look clean.
Acceptance for this delivery requires no added validation findings, no graph
cycles/dangling references, no open child under a superseded epic, valid links
and commits for changed tickets, and explicit readiness/disposition checks.
The independent review and final validation outcome are recorded below.

## Independent adversarial review and final verification

Reviewer: independent read-only agent `performance_backlog_adversary`,
2026-09-13. Accepted for lead-engineer handover after two draft passes, with no
remaining blocking planning findings. The reviewer inspected source/evidence,
ticket contracts and the parsed v0.6.0 dependency graph; it did not author tickets,
run production tests or approve an implementation/performance claim.

Resolved findings:

1. Existing FK equality, plan sharing and one-way close must not be recreated.
   Candidate scope now identifies the missing earlier discovery/remaining ownership.
2. WAL force concurrency must cover current mapped writes, atomic footer/range
   ordering and mapping lifetime; the historical provider proof is insufficient.
3. Remove Payment-before-profile and blanket phase dependencies while retaining
   actual P0/overlap prerequisites and all historical failure evidence.
4. Superseded epic closure must not unlock a formal claim: actual evidence gates
   replace the retired dependency, and promotion inspects explicit dispositions.
5. Correct tic-bd79: harness produces artifacts; independent sidecar owns pairing,
   eligibility/confidence/gate evaluation, with real tic-e305 delivery dependency.
6. Correct tic-8561: predeclare the contract without waiting for optimization;
   actual campaign tic-630d retains integrated/lifecycle/external delivery gates.

Final checks:201 tickets;31 changed/new tickets with complete CLI body-preservation
round trips;215 relative document links resolve; no cycles, dangling references,
or open children under superseded epics. All previously closed ticket bytes and
all retired original scope text are preserved (retired headings normalized for
CLI safety). Original overlap dependencies remain unchanged. Every prior pending
status is retained except the four explicitly superseded tickets. The ready
frontier is tic-da4e plus optional evidence-only contract tic-8561; all nine new
child tickets await real dependencies. Validation still reports exactly153
pre-existing findings with zero added findings; the global validator is therefore
not claimed green. Verification artifacts:
`/private/tmp/river-performance-verification-summary.json`,
`/private/tmp/river-performance-validation-final.json`, and
`/private/tmp/river-performance-verify.py`.

Next lead action: claim tic-da4e from the latest pushed stable source, record the
current executable/runtime and workload configuration, and produce the admission
matrix. Preserve the existing tic-1dda claim; coordinate resumption explicitly.
The lead receives an actionable investigation frontier and bounded downstream
contracts, not permission to implement all hypothetical optimizations blindly.

Planning delivery `674e64b8` was merged and pushed as `453a2ede`; tic-lalaith
records the delivered commit. The three performance epics remain open for the
lead's evidence-gated execution. No performance implementation was started.
