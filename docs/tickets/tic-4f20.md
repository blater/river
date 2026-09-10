---
id: tic-4f20
status: closed
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

## Source assessment

At `df88f18c`, an ordinary descriptor INSERT encodes a base row and stages it
under `RelationalDescriptorKeyspace.baseRows(tableId)` with an internal logical
row ID. `IndexedTransactionWriteSet.insert` reserves and locks that scalar key
and records the pending base-row mutation. `RelationalDescriptorTupleDeltaStaging`
then stages one tuple mutation for the primary key and one for each maintained
secondary index. Tuple payloads carry the logical row ID as a suffix. Therefore
the primary-only workload has one logical base-row mutation plus one primary-key
tuple mutation; the secondary workload adds one tuple mutation and its key
protection. These logical counts are not physical page-write or sync counts.

At application, `IndexedRelationalTupleApply` resolves each index registry,
copies each staged key into reusable application storage, applies the mutation,
and stages the resulting registry state. The copy crosses a real ownership and
visibility boundary: an SQL-time page position cannot be retained through lock
wait, client work or durable publication. `IndexedGroupCommitBatch` subsequently
publishes the prepared group and completes its durability barrier. Those staged boundaries
are required by River's snapshot, rollback and recovery model; they are not
evidence that every physical write is caused by the base-row representation.

Clustered primary-key rows and immediate leaf mutation remain separate choices.
Clustering could remove the separate primary-key-to-logical-row lookup, but it
changes leaf size, fanout and split behavior. Secondary entries would still need
either stable logical-row identity or primary-key references; the latter make
primary-key updates rewrite secondary entries. Immediate leaf mutation could
avoid a later search, but would need a new uncommitted-state, undo, snapshot and
publication design. A leaf latch cannot span a lock wait or durable commit.

## Step 5 evidence

The matched profiles used the same checkout (`df88f18c`), four workers, 15-second
warmup, 25-second measurement and 20-second wall profile. The primary-only run
committed 234,003 rows at 9,360.04 inserts/s; the one-secondary run committed
216,680 rows at 8,667.09 inserts/s. Both row-count checks passed and both owned
servers and data were cleaned up.

The profiled primary-only versus secondary totals, in estimated thread seconds,
were:

| Group | Primary only | One secondary |
| --- | ---: | ---: |
| locks | 1.904 | 2.990 |
| scalar compilation | 0.755 | 0.680 |
| tuple compilation | 2.123 | 3.238 |
| sync | 4.116 | 3.810 |
| row insert | 0.631 | 0.660 |

The secondary run shows the expected increase in tuple and lock work, while
the primary-only mapping is not isolated as the dominant cost. The largest
visible samples remain sync and ordinary writes, with page-history reclamation,
tuple compilation and lock work also present. These groups overlap in the
profile; they must not be summed into a serial per-row cost. The wall samples
also have different row/tree growth and omit waits while virtual threads are unmounted, so they establish direction and scope rather than a universal
throughput claim.

The source-level mutation count explains why the secondary run cannot be used as
evidence for a clustered replacement by itself: it deliberately performs one
additional logical index mutation. Physical writes include WAL, page images,
history reclamation and publication work, and are not one-for-one with those
logical mutations. Page splits remain part of each tree's ownership and fanout
behavior. The corrected `tic-2e91` leaf binary positioning also removes the old
linear-bound search cost; that change does not require clustered rows.

## Decision

Retain the separate logical base-row and tuple-index layout for now. The matched
profiles do not identify the primary-key mapping as a material dominant cost, so
there is no justified format rewrite or implementation ticket. Continue to treat
the staged publication and bounded application copy as semantic boundaries.

Any future replacement would need to preserve stable logical row identity,
primary-key update handling for secondary references, snapshot visibility,
rollback, WAL/recovery and split correctness in one end-to-end write/read path.
It would be justified only by repeatable matched evidence isolating that mapping
as a material removable cost after accounting for sync, publication, lock
waits, tuple work and tree growth. No such evidence is present here.

Integrator review accepted this bounded decision after all three code tickets
passed their matched performance and correctness gates. No production code or
format changes belong to this ticket. Evidence, scripts, commands and SVGs:
`/private/tmp/insert-step5/8b64-candidate-profile/` and
`/private/tmp/insert-step5/final-secondary-profile/`. Final checkpoint:
`perf-checkpoint-20260910-insert-efficiency`.
