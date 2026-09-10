---
id: tic-a73c
status: open
type: story
priority: 1
delivery: code
created: 2026-09-10
parent: tic-6d42
deps:
  - tic-c7e2
---
# Prepare and admit INSERT rows once, then stage the admitted result

## Change

Replace the repeated preparation and validation passes in
`SqlDescriptorPointInsertExecution` and `RelationalDescriptorBatchInsert` with
one bounded statement-owned preparation/admission path consumed by staging.

Evaluate each row's expressions once and retain the values or encoded form needed
for insertion and foreign-key validation. Encode row content and plan keys once;
assign or patch the logical row identity without reevaluating user expressions.
Accumulate resource requirements from that prepared form and reserve before
publishing statement mutations. Remove the re-encoding/fingerprint round trip
used to establish that independently rebuilt rows still match.

Detect intra-statement duplicate keys, acquire transaction-held key protection,
and check published and transaction-local uniqueness once per admitted key.
Staging consumes that admission rather than reopening published-index probes or
reacquiring the same protection through independent layers. Keep the session's
other mutation callers on the same owning policy; no unchecked alternate insert
entry point or caller-controlled bypass flag.

## Correctness and boundaries

The retained result must remain bounded by existing statement/transaction budgets.
Account for its actual memory and return the existing resource status before a
partial statement becomes visible. Keep statement atomicity and existing foreign-key
ordering, including relationships among rows in the same statement. Preserve null,
default, check-constraint and generated-value semantics; repeated evaluation must
not change the meaning of an expression.

Admission stays valid only while the transaction retains the necessary protection
and the prepared row/key is unchanged. Preserve duplicate detection against earlier
writes in the same transaction, delete/reinsert, nullable unique keys, concurrent
same-key inserts, cancellation and rollback/savepoint behavior. A wait or retry must
resume with valid state or explicitly rebuild after invalidation. Do not reuse
admission across statements through a new cache.

## Acceptance

- Real prepared single-row and multi-row INSERT use the same owning mechanism.
  Source and focused diagnostics show one evaluation/encoding/key plan and one
  uniqueness admission per key; no second published probe during staging.
- Focused tests cover successful insertion, intra-batch and concurrent duplicate
  rejection, existing transaction writes, a late constraint/resource failure
  leaving no partial statement, and rollback/retry state reuse. Extend existing
  tests rather than mirroring every helper call in new mocks.
- Reprofile the 5.74-second unique-validation subtree and report preparation,
  probe and lock work removed. Complete the parent epic's matched INSERT/TPS,
  slopmark and correctness checks.

No B-tree format, lock storage layout, clustered-row implementation, commit
pipeline or benchmark change. Lower-level probe efficiency belongs to tic-2e91.
