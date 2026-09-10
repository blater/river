---
id: tic-4f20
status: in_progress
type: investigation
priority: 1
delivery: evidence
created: 2026-09-10
parent: tic-6d42
deps:
  - tic-2e91
  - tic-8b64
---
# Decide the next row-storage change from the remaining insert cost

## Question

After repeated admission, probing and lock storage work is removed, does River's
separate logical base row plus primary-key tuple mapping remain a material insert
cost? Would clustered primary-key rows and leaf-local mutation remove enough work
to justify changing the storage architecture?

## Bounded output

Use the new profile and current source to explain actual row/index mutations,
searches, copies and lock work for a table with only a primary key and one with a
secondary index. Compare River's staged publication with InnoDB's positioned,
latched leaf insert. State which work is necessary for River's semantics and
which is a consequence of its present layout.

Produce one short recommendation: retain the layout, or propose the smallest
end-to-end replacement. Address logical row identity, primary-key updates,
secondary-index references, snapshot visibility, rollback, WAL/recovery and page
splits. Do not assume a latched cursor can survive a durable wait. Clustered rows
and in-place leaf mutation are separate decisions; adopt neither by default.

If implementation is justified, create a bounded implementation ticket with
explicit format/recovery scope and measurable work to remove. River is pre-V1:
a replacement changes owned callers and formats directly, without a legacy path.
A small disposable experiment is allowed only for a specific unresolved question.
Stop at the decision; no production rewrite, new benchmark framework or exhaustive
survey of other databases.

## Completion

Record the evidence, recommendation and any resulting ticket. No TPS increase is
expected from this decision ticket and no routine build matrix is required for
a documentation-only result.

## Provisional source assessment — before step 5

At `ad1db42f`, an ordinary descriptor INSERT encodes a base row and stages it under
`RelationalDescriptorKeyspace.baseRows(tableId)` with an internal logical row ID.
`IndexedTransactionWriteSet.insert` reserves/locks that scalar key and records a
pending base-row mutation. `RelationalDescriptorTupleDeltaStaging` separately
stages a tuple key for the primary key and each maintained secondary index.
Tuple payloads carry the logical row ID as a suffix. Consequently, the example
with only a primary key has a base-row mutation and a primary-key tuple mutation;
adding one secondary index adds another tuple mutation and its key protection.
These are logical mutation counts, not counts of physical page writes or syncs.

The prepared logical commit subsequently enters shared-group preflight and append
through `IndexedGroupCommitBatch`. `IndexedRelationalTupleApply` resolves each
index registry, copies each staged key into reusable application storage, applies
it and stages the resulting registry state. This split is a real ownership and
visibility boundary: an SQL-time page position is not automatically valid when
physical application occurs. A bounded copy that crosses this lifetime boundary
must not be removed merely to obtain a lower copy count.

Two distinct architecture choices must remain separate:

- Cluster primary-key rows: putting the row in the primary tree could remove the
  separate primary-key-to-logical-row lookup/mutation. It also changes leaf size,
  fanout and split cost. Secondary indexes must identify either a stable logical
  row or a primary key. Stable identity requires a resolution path; primary-key
  references make primary-key updates affect secondary entries and their size.
  Neither cost is eliminated by relabelling the representation.
- Mutate a located leaf immediately: this can avoid a later search, but changes
  how uncommitted state, undo, snapshots and publication work. A latch cannot be
  carried across client think time, a lock wait or durable commit. Preserving
  River's current staged publication may instead favor finding the leaf once
  during application; that does not require clustered storage.

Provisional recommendation: keep both changes out of steps 1–3. Consolidate
admission and remove repeated validation/search and lock bookkeeping first.
There is no post-change performance evidence yet, so no new storage-format
implementation ticket or final retain/replace decision is justified at this
stage. This is not a claim that the current layout is efficient.

Step 5 must inspect the remaining base-row, primary-tuple and secondary-tuple
application costs and identify whether search, representation, copies, page
mutation or publication dominates. If a replacement is warranted, its ticket
must cover one coherent write/read/recovery path, primary-key changes, secondary
references, rollback and snapshot visibility, and splits under concurrent access.
A format replacement needs an ADR and independent recovery review, but no
compatibility path for River's unreleased format. Close this assessment only
once that measured decision and any resulting bounded ticket are recorded.

The lookup implementation review also found that the old cursor always started
at the edge of the selected leaf and walked to its bound. `tic-2e91` replaces
that linear positioning with the existing leaf binary-search owner. This is an
algorithmic search cost, not evidence that clustered storage is required. Step 5
must measure the corrected lookup before attributing its former cost to layout.
