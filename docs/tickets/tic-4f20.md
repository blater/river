---
id: tic-4f20
status: open
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
