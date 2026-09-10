---
id: tic-5c21
status: in_progress
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
