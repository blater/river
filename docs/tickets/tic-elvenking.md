---
id: tic-elvenking
status: open
type: feature
priority: 1
assignee: blater
delivery: code
tags:
  - performance
  - storage
  - validation
created: 2026-09-28T12:36:53.50558Z
---
# Remove repeated content validation from trusted read and update paths

Remove repeated defensive content validation from ordinary reads, updates and
internal value transfers. A numeric query or update must not rescan unrelated
stored strings to prove that they are still valid UTF-8. Preserve validation of
new external input, newly computed values and actual SQL constraint changes.

The governing policy is [Storage trust and content integrity](../../AGENTS.md#storage-trust-and-content-integrity).
The [indexed-read design document](../plans/river-indexed-read-work-amplification.md)
explains the wider costs; this ticket owns only the validation and trusted-value
transfer change. The earlier [read-validation delivery](tic-fine-barad-dur.md)
removed descriptor rechecks and page payload CRCs but retained whole-row content
validation. Do not interpret its completion as completion of this work.

## Outcome and owner

The row/value boundary owns one distinction: external or newly produced values
whose validity must be established, and already admitted or River-written values
whose content is trusted. Downstream readers, encoders and mutation builders use
that established validity without repeating semantic validation.

Storage devices are trusted. Ordinary execution retains lightweight structural
checks needed for safe addressing and correct identity, visibility and ownership;
it does not provide exhaustive detection of post-admission content corruption.
Deep inspection belongs outside critical paths, preferably in separately invoked
standalone utilities. Those utilities are explicitly deferred and are not a
dependency of this ticket.

## Implementation scope

1. Trace value admission through the protocol and embedded API, stored-row
   decoding, SQL mutation construction, encoding and internal publication. For
   each repeated check being removed, identify the boundary that established the
   invariant, or the storage-trust rule that makes a content recheck unnecessary.
   Keep this a focused review, not a permanent inventory of method signatures.
2. Remove whole-field UTF-8/scalar-count scans from ordinary stored-row reads,
   including unprojected fields and rows fetched to perform an update. Remove
   repeated type-domain, nullability, padding and canonical-content checks where
   they merely revalidate trusted unchanged values. Keep structural offset and
   length checks needed to bound the bytes actually accessed.
3. Replace internal calls that re-admit trusted text through validating setters
   with one owned transfer operation that preserves the declared type and byte
   lifetime. `StoredTableRowPublisher` currently calls
   `SqlValueBuffer.setTextBytes`, whose arena validates UTF-8 again. Migrate all
   River-owned callers of the replaced internal contract together. Public raw
   byte input must still pass its admission validation; do not expose an
   unchecked external bypass or a caller-selectable validation flag.
4. Change update construction so copying an unchanged field preserves its typed
   bytes without semantic revalidation or a UTF-8 to UTF-16 to UTF-8 conversion.
   `SqlDescriptorMutationValues.copyFetched` currently performs that conversion.
   Retain checks for new assignments, casts, narrower target constraints,
   arithmetic overflow, nullability changes, uniqueness and foreign keys at
   their existing semantic owners. Do not repeat those checks merely while
   encoding an already accepted mutation.
5. Replace tests that require ordinary reads to perform exhaustive content
   inspection. Continue testing malformed input rejection at admission, safe
   handling of invalid structural metadata, exact value preservation and the
   changed content-integrity contract. Do not migrate old tests into an unused
   production validator or introduce a compatibility mode to keep them passing.
6. Align affected codec contracts and ADR 0004/0010 with the working agreement.
   State that post-admission content damage may remain undetected. Do not shift
   full semantic scans into automatic cache fills, startup, recovery, checkpoint
   or commit to preserve the old guarantee under a different owner.

## Scope limits and stop conditions

One production policy changes: ownership of value validity. Its necessary
implementation may span base value storage, row codecs, relational access and
SQL mutation/publication callers. There is no arbitrary file-count limit.

This ticket does not redesign indexes, row identity, MVCC, WAL, recovery,
projection planning or the storage format. It does not remove required WAL
framing/recovery checks or change durability. It does not add standalone
integrity tools, background scans, inspection schedulers, validation caches,
trusted/legacy execution modes or another value representation. Existing
unrelated inspection tools remain outside its scope.

If removing a check exposes an unvalidated external entry point, repair that
entry point in the same value contract before removing its last admission
check. If a separate storage/concurrency redesign is required, record that
prerequisite rather than expanding this ticket. Stop acceptance on incorrect
results, unsafe buffer lifetimes, recovery failures or an unexplained repeated
performance regression. A source-level simplification alone is not evidence
of a throughput gain.

## Acceptance criteria

- Reads, numeric updates and unchanged-text transfers perform no repeated UTF-8
  or whole-content validation of trusted stored fields. The trusted transfer
  preserves bytes, nulls, descriptors and ownership without per-row allocation.
- External malformed UTF-8 and invalid character-count input remain rejected.
  Newly assigned/computed values still enforce their target SQL domain and
  constraints. Cover multibyte text, empty/null text, retained unchanged text,
  same-type copies, narrowing assignments and buffer reuse.
- Focused tests exercise the real SELECT and UPDATE paths, pending writes,
  rollback, restart and the material boundary failures. Incorrect lengths,
  offsets, identities or ownership never permit out-of-bounds access. Tests do
  not demand exhaustive detection of otherwise trusted damaged content.
- Temporary counters or focused profiling establish that the targeted read and
  update paths no longer scan trusted text for validation or transcode unchanged
  text. Use workload evidence for cost; do not add source-token ceilings or a
  duplicate static API inventory.
- Use the accepted current build and fixed harness configuration for at least
  two before/after samples. Exercise `full stock-level` for reads and a focused
  `sample new-order` workload for numeric stock updates carrying unchanged text.
  Check adjacent `sample stock-level` behavior. Serialize builds and workloads;
  lengthen/interleave only to resolve a surprising or repeated shift. Preserve
  successful invariants, cleanup and zero failed/unknown outcomes. Do not claim
  that removing validation alone will close the MariaDB gap.
- Retained hot-path validation is lightweight and has no measurable
  steady-state regression against adjacent controls. Record raw samples and
  uncertainty; do not declare an arbitrary acceptable percentage loss.
- Affected-module tests and policy checks pass, followed by the required clean
  feature checkpoint. Independent boundary/correctness review verifies input
  admission, trusted ownership and the intentional integrity reduction. Record
  source, configuration, measurements and the decision in the performance
  checkpoints before the normal reviewed merge/tag/push delivery.

## Compatibility and deferred work

There is no backward-compatibility requirement for superseded River behavior.
Replace the old internal path and its tests in the same delivery; retain no
legacy decoder, flag or adapter. This does not remove the requirement to reject
malformed new user input.

A future standalone utility may explicitly validate stored UTF-8, value domains,
row/index consistency and other deep integrity properties. Its command,
implementation, scheduling and scaffolding are not part of this ticket and must
not delay it. Creating this ticket does not start production implementation or
reopen the historically parked backlog wholesale.
