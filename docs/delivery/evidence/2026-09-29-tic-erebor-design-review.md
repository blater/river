# Erebor independent design review

Date: 2026-09-29. Reviewed worktree: `/private/tmp/river-erebor`.
Source base: `2ada6350`. Subject: proposed
[ADR 0015](../../adr/0015-clustered-relational-row-store.md), before production
implementation. The reviewer inspected the proposal independently of its author,
using the current format, tuple storage, relational read and commit code.

**Decision: changes required before production implementation.** The clustered
row direction is supported. Four specific design issues below need resolution;
none requires another general architecture survey or user permission. The ADR
line references refer to the proposal as reviewed, before the review appendix.

## R1 — P1: Preserve publication before force in the shared commit path

**Location:** ADR lines 149–155, especially “publish none of their generations
until all chunks are durable.”

This contradicts both the ADR's promise to preserve existing ordering and the
ticket's exclusion of WAL force-policy changes. The shared coordinator publishes
an irrevocably appended group before force completes. It retains publication
pins so those pages cannot be written to the data file prematurely, and gates
dependent results and commit acknowledgments on durability. The direct path
does force first; it is not the shared-path contract.

Evidence: `IndexedGroupCommitBatch.publishPrepared`,
`IndexedHybridCommitGroup.preparePublication`, `installPublication` and
`sealPublication`; the explicit publication-pin comment is at line 243 of
`IndexedHybridCommitGroup.java`. Held-force behavior is part of `tic-e544`.

**Required revision:** distinguish complete WAL append/decision, visibility
publication, WAL force, data-page write eligibility and acknowledgment. Preserve
the shared pipeline: append the complete logical group and its decision, install
all member pages atomically at publication, retain durability ownership until
force, then release eligible pins and complete dependent results. A partial WAL
group must never publish. Force failure retains the existing fencing behavior.
Do not introduce a new force-before-visibility requirement for clustered rows.

**Implementation proof:** extend the existing held-force coverage to clustered
rows: a durable independent row completes while an observed updated row waits;
failure fences dependent results. Cover an update spanning multiple WAL chunks.

## R2 — P1: Choose the existing logical WAL representation consistently

**Location:** ADR lines 139–159, especially the 63-page limit and proposed
multiple page-image chunks at lines 151–155.

The proposal first extends logical relational mutations and replays through the
same applier, then prescribes page-image records. The referenced 63-page limit
belongs to `IndexedPageBatchCodec`. A repository-wide reference search found
that codec in its format validator/tests, with no current engine caller.
It is not the limit on the active relational commit path.

The active path uses `IndexedRelationalWalPlan.prepareChunks` and
`IndexedRelationalWalCodec` to encode logical mutation items. Cumulative page
staging/freezing is owned separately by `IndexedHybridGroupPreflight`. A limit
on an unused page-image codec does not justify adding another WAL representation
or logging full pages alongside row mutations.

**Required revision:** remove the page-image chunk requirement and the claim
that 63 changed pages limits current relational WAL. Define clustered put/delete,
locator and overflow allocation/retirement evidence within the existing logical
mutation stream, including expected/resulting identities and deterministic
replay. Keep chunk-byte admission and aggregate staged-page admission distinct.
If page images are actually necessary, identify the concrete replay blocker and
replace the conflicting logical-WAL decision explicitly before implementation.

**Implementation proof:** recovery from a complete multi-chunk mutation and
rejection of an incomplete group; exact primary/secondary/locator/overflow
results after replay. A transaction changing more than 63 pages must be limited
by its configured resources and actual format bounds, not that unused constant.

## R3 — P1: Separate structural root publication from membership dependency

**Location:** ADR lines 100–106, together with lines 148–149.

The distinction between a row's modification sequence and index membership is
correct, but the persisted representation and structural case are missing.
A non-key value replacement can grow an inline row, split its leaf and replace
the root without changing key membership. The new root still has to be published
and recovered at the correct snapshot. Conversely, updating the existing root
registry row makes `IndexedTupleRootSnapshot.observedCommitSequence()` return
that registry version's sequence. `IndexedTransactionTupleScans.begin` observes
it unconditionally, broadening an unrelated row's durability wait.

The existing `TupleIndexRootRecord` has structural generation/root fields but
no separate membership dependency sequence. Merely saying that payload-only
updates do not change membership does not specify how to handle this case.

**Required revision:** specify the root record's structural version/root pointer
and its logical membership dependency separately, including format, write,
replay and read ownership. One concrete solution is a persisted membership
sequence carried unchanged through structural-only root replacement, with
per-entry sequences observed for every examined row before value-dependent
filtering. Insert/delete/key move advances membership; row replacement advances
the row sequence; a structural split alone advances neither unrelated rows nor
logical membership. Include secondary membership and identity-map absence.
An alternative must establish the same snapshot and dependency guarantees.

**Implementation proof:** grow a row enough to split a root while force is held.
Reading another durable row remains independent; reading the changed row waits.
An old snapshot still traverses its coherent root/pages. Deletion and range
absence wait for the membership change that establishes their result. A row
excluded by a value predicate still contributes its observed row dependency.

## R4 — P2: Define overflow placement without undoing the read improvement

**Location:** ADR lines 35–43: “If the entry cannot fit” and placement following
“actual leaf occupancy.”

As written, a normally sized inserted row can be placed on an overflow page
merely because its target leaf is full. Subsequent ordinary rows can then incur
one extra data-page pin each. This undermines the intended inline primary-read
path, and does not define whether a growing value causes a split or overflow.
It also makes allocation/replay behavior depend on an unspecified decision.

**Required revision:** specify one deterministic storage-owned placement rule.
Prefer keeping an entry inline when it can fit in a valid leaf after splitting;
ordinary leaf fullness should invoke byte-aware splitting. Derive any overflow
threshold from the page, slot, key and fence structural requirements, preserving
the admitted maximum row size. State how value growth, shrinkage, split movement
and overflow replacement affect placement and retirement. Do not make placement
depend on available cache frames or introduce a workload-specific threshold.

**Implementation proof:** insert enough ordinary harness-sized rows to split
leaves and show they remain inline; grow/shrink a row across the selected
structural boundary, then verify old/new snapshots and replay. Measure actual
inline/overflow counts in the performance evidence.

## Findings that support the proposal

- The 40-byte leaf-slot field total is correct. Keeping values out of comparison
  and separators preserves key ordering ownership.
- The maximum existing heap row is 16,216 bytes. A 16,256-byte payload with a
  32-byte overflow header can hold it. The proposed maximum secondary key plus
  primary locator fits a leaf; include the fence in final split arithmetic.
- Cumulative member compilation followed by per-member freezing already exists.
  Reusing it is a sound basis for preserving two different-row updates to the
  same leaf, including splits. No new commit coordinator is justified.
- Same-snapshot secondary locators and atomic locator replacement on primary-key
  moves are necessary and correctly specified. The identity map is locator-only
  and excluded from ordinary primary reads.
- Borrowing immutable pinned leaf bytes, with copying only for longer consumer
  lifetimes, fits the existing cursor/page-generation mechanism.
- Explicit old-format rejection before mutation and removal of descriptor
  scalar row authority are appropriate for this pre-V1 replacement.

## Implementation review notes

These do not require a broader prerequisite investigation:

- The quoted 96/28 entries are nominal packed capacities before fences and
  split occupancy, not minimum realized leaf occupancy. Correct “at least” in
  the ADR and report measured distribution, tree height and overflow counts.
- Overflow retirement must use the commit that removes the last current
  reference, while preserving every snapshot-reachable older leaf reference.
  An old leaf can reference an overflow page without pinning it yet. Neither
  zero overflow pins nor the overflow creation sequence establishes reuse
  eligibility. Persist/replay retirement and free-page ownership under R2.
- Audit actual identity-only consumers before routing backfill or FK scans
  through the new locator tree. A scan already holding the primary row should
  consume that row directly. For a hidden logical-ID primary key, derive the
  locator where sufficient rather than adding an unnecessary lookup.
- Per-entry modification sequences must survive unrelated leaf rewrites and
  splits unchanged. Stamp only actual row/locator changes, in both compilation
  and replay. The original selected page generation is not the row dependency.

## Review completion

This was source and design analysis; no production code changed and no build or
benchmark was run. Existing controls are evidence of the current mechanism,
not evidence that the proposed layout is faster. Revise the ADR for R1–R4 and
record a focused independent follow-up decision here before implementation.
Final durable-format/concurrency/recovery review and the ticket's performance
gates remain necessary before promotion.

### Correction handoff — 2026-09-29

At the user's request, the ticket and ADR have been revised for R1–R4. The
original findings above remain the review record. The revised ADR now preserves
the shared publication pipeline, uses logical WAL throughout, adds an explicit
persisted membership sequence, and defines a page-derived inline boundary with
byte-aware splitting. It also clarifies overflow retirement, nominal occupancy
and identity-only consumers. The ticket names the corresponding focused proof
obligations.

The correction author has not independently approved these revisions. A focused
follow-up must check the membership/structural dependency distinction and the
placement/split rule, as well as resolution of the WAL findings, before production
implementation. In particular, variable-sized values may require more than two
sibling leaves; the revised contract explicitly includes that case. This is a
documentation correction, with no production tests or performance claim.

### Focused follow-up — 2026-09-29

The original proposal author reviewed the R1–R4 corrections made after the
initial independent review. The initial review therefore remains the
independent assessment of the proposal; this follow-up checks the corrected
text against the owners it identified. **Decision: corrected design accepted
for implementation.** This is not approval of code, recovery behavior or a
performance result.

- R1 matches `IndexedGroupCommitBatch.publishPrepared` and
  `IndexedHybridCommitGroup.preparePublication`/`sealPublication`: the complete
  append precedes shared visibility publication, publication pins remain
  until force ownership resolves, and direct commit retains force first.
- R2 matches `IndexedRelationalWalGroupAppender` and
  `IndexedRelationalWalPlan.prepareChunks`; `IndexedPageBatchCodec` has no
  engine caller. The active `IndexedPageState` caps only operations marked as
  page-image operations at 63. One tuple-root path still calls
  `IndexedPageFrameCache.pinScalarOperationPage` from
  `IndexedTuplePageProvider.begin`, imposing that same 63-page limit when a
  structural root change follows many logical tuple changes. Implement the
  ADR's configured logical-page admission there and prove the greater-than-63
  case before accepting the code. This is an implementation obligation under
  the corrected design, not a reason to add page-image WAL.
- R3 separates structural root/version selection from the persisted membership
  sequence and per-entry modification sequence. `IndexedTupleRootSnapshot`
  currently exposes the root registry row's observed commit; the tuple read
  owner must switch that observation to the new membership field and observe
  each examined entry before value filtering. A structural-only root rewrite
  carries membership unchanged. The ticket's held-force and old-snapshot
  tests directly exercise those conditions.
- R4's `S + K + V <= P - H - F` rule uses structural page sizes, admits the
  existing maximum row through one overflow page, and keeps all harness-sized
  rows inline regardless of leaf fullness. Byte-aware split planning must
  support more than two output leaves for a large middle entry. The ticket
  now names that test explicitly.

Overflow retirement, hidden-primary identity derivation and nominal occupancy
wording also resolve the initial notes. The final independent
durable-format/concurrency/recovery review remains mandatory after code is
implemented.
