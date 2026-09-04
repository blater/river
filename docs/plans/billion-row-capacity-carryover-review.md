# `billion-row-capacity` carry-over review

Status: reviewed for extraction; no production source accepted by this review

Date: 2026-09-04

Stable comparison source: `master` at
`5b120a179055a9ec1c640152b4e2bf057d23f5ac`

Dirty evidence source: `/Users/blater/src/ingres/river` on
`feature/billion-row-capacity` at
`18b185921173a74f0297dd46c5a53533a3c65680`

## Decision

Do not merge, copy wholesale, or treat the dirty worktree as a feature branch.
Its committed head is an ancestor of `master` with no unique commits. Most of
the coherent work up to the launcher/authentication cutoff is already preserved
by recovery commit `adccf7172e74450cf4518a561b3712c4e8927c0d`. The remaining
filesystem differences mix later experiments, test edits, authentication and
server work, formatting, logs, and a few small candidate changes without one
shared evidence boundary.

Two pieces of knowledge are worth carrying into existing tickets:

1. remeasure the page-frame payload-view allocation after P1/P2 profiling and
   only then consider the small ownership-preserving reuse change; and
2. add explicit uncertain-durability and fenced-admission cases to the P1
   visibility/fencing design and fault matrix.

This review carries those observations, not their uncommitted implementations.
Everything else listed below is either already on `master`, lacks evidence, is
outside the current priority, or belongs to the superseded launcher/auth path.

## Method and evidence boundary

The review compared file contents in the dirty worktree directly with the clean
`origin/master` worktree. It did not use the dirty branch index as the baseline,
because that index predates the recovery snapshot and incorrectly presents many
files already on `master` as untracked or modified.

Acceptance for direct code extraction required all of the following:

- a named current ticket and owning subsystem;
- a measured or correctness-proved problem on the stable source;
- a coherent minimal source delta;
- a focused test proving success and the material failure boundary; and
- no dependency on the rejected launcher/authentication path or unrelated dirty
  edits.

No remaining production delta satisfies all five conditions. Untracked local
TPS logs are not accepted evidence: they report materially different outcomes
(including 34.6, 52.9, 77.8, 111.3, and 114.0 TPS, plus one
`RESOURCE_EXHAUSTED` failure) without an immutable source fingerprint and
artifact reference sufficient to reproduce or attribute them.

`docs/perf_review.md` does retain a stronger clean-build diagnostic A/B over
individual suspect slices. Restored controls were 110.4 and 110.9 TPS; one
lock-radix-cache sample was 117.8 TPS; and one combined-slice sample was 125.4
TPS. The review itself correctly classifies these as ten-second diagnostics,
not improvement claims. There are no repeated/interleaved candidate samples,
immutable artifacts, or retained candidate commits. They exonerate the slices
as the cause of the earlier collapse, but they do not authorize carrying any
slice into `master` as an optimization.

## Already preserved on `master`

The following committed work is in the ancestry of `master`; it needs no
carry-over action:

- transaction optimization route prototype (`55e96ba`);
- configured program isolation propagation (`1bb3ec3`);
- hardened no-argument TPS evidence harness (`3a0d156`);
- bounded deadlock diagnostics (`18b1859`); and
- the coherent pre-launcher recovery snapshot (`adccf71`).

The later ticket, manifesto, riverd-plan, and priority-Kanban commits are also on
`master`. Their apparent absence from the dirty worktree is branch age, not lost
work.

## Carry observations into existing tickets

### Page-frame payload-view reuse

Dirty delta:

- `IndexedPageFrame.payload` becomes constructor-owned and final;
- the constructor creates the duplicate/slice view once; and
- `prepare()` clears the existing view instead of allocating another view.

Why it is retained as an observation: the recovery notes recorded 80,896 bytes
of warmed commit-path allocation across 64 single-row transactions and
attributed it to page-frame acquisition. The delta expresses a valid ownership
idea: the frame owns both the direct page and its lifetime-bound payload view.

Why the code is not carried now:

- the recorded capture has no immutable artifact reference;
- stable `master` has no focused test asserting payload identity, position,
  limit, and visibility across repeated preparation;
- the dirty cache test instead pins first-available-slot selection and does not
  prove the payload-view mechanism; and
- P3 work is evidence-selected after P1 and the Payment pilot, with
  [`tic-da4e`](../tickets/tic-da4e.md) owning the inclusive measured-phase
  profile.

Action: preserve the observation in `tic-da4e` evidence. If a stable-source
allocation capture again attributes material measured-phase cost to repeated
payload-view creation, create one P3 story with the focused lifetime test,
warmed allocation guard, affected engine tests, and matched TPS samples. Do not
cherry-pick the dirty file before that discriminator.

### Uncertain group durability and fenced admission

Dirty deltas:

- `IndexedGroupCommitBatch` fences the commit writer instead of attempting
  ordinary group cancellation after a decision may have been appended; and
- `IndexedTableStore.transactionAdmissionStatus()` checks the store's admission
  status before durable-version pressure.

These identify concrete failure cases, not accepted fixes. They concern exactly
the durability/visibility state machine owned by
[`tic-b368`](../tickets/tic-b368.md) and the implementation/fault matrix owned
by [`tic-f1bb`](../tickets/tic-f1bb.md). The dirty worktree contains no focused
test that drives the changed failure branch and reconciles WAL state,
visibility, acknowledgement, fencing, transaction outcome, and restart.

Action: add both cases to the `tic-b368` state-machine review and `tic-f1bb`
fault plan. Re-derive the implementation from the accepted contract. Do not
cherry-pick either conditional.

## Do not carry

| Dirty change | Decision | Evidence-based reason |
| --- | --- | --- |
| `LockRadixDirectory` one-entry locality cache and its new test | Reject | Its one 117.8 TPS diagnostic is not repeated or attributable, and no profile identifies radix traversal as material. The change adds mutable shared state, while its test proves reuse correctness only and no concurrency boundary. |
| Page-cache test requiring the first available slot without scanning later slots | Reject as policy | It contradicts the old checkpoint narrative and pins a selection rule without a measured cache/reclamation requirement. Slot policy belongs to a separately evidenced cache story. |
| Deletion of legacy/default/resource tests and relocation of shared test fixtures | Reject wholesale | No completed clean gate proves the deletions preserve meaningful coverage. Fixture consolidation spans modules and is not an immediate product consumer. |
| `SqlUnionExecution` constructor removal and materialized test-fixture rewrite | Defer, no ticket | The change may improve test realism, but it has no measured TPC-C effect and does not unblock the current riverd or P0/P1 milestones. |
| `tools/tps-test.sh` delta | Reject | It removes the explicit skip-build path and runtime resource directories that exist on `master`; it is not the hardened go-forward script. |
| Authentication, JDBC, CLI, server, dependency-metadata, and launcher deltas | Reject wholesale | They are inseparable from post-cutoff work that previously introduced synchronous audit-force and lifecycle regressions. `tic-de1d`, `tic-a221`, and the riverd chain require a fresh inventory and accepted contract before implementation. |
| Formatting-only `IndexedTransactionSession` change | Ignore | It has no behavioral content. |
| Local logs, Gradle caches, Obsidian workspace state, and other scratch output | Do not import | They are not source or reproducible evidence and have no repository consumer. |

## Extraction rule

If future evidence selects one of the two retained observations, start a new
ticket branch from the latest pushed stable checkpoint and reproduce the problem
there first. Implement the smallest coherent mechanism from the current owner
and contract; use the dirty worktree only to compare ideas. The resulting code
must pass its own focused tests, affected-module tests, clean feature gate,
slopmark review, and matched performance samples. No future delivery should use
the phrase “carried from `billion-row-capacity`” as evidence by itself.
