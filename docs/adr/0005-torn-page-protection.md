# ADR 0005: Full-page-image torn-page protection

Status: Proposed — Phase 0 P09/prototype review required; K10/G1 validates the production crash matrix

## Context

Checksums detect torn or corrupt pages but cannot reconstruct them. The initial
in-place engine needs one exact recovery mechanism before page and WAL formats
freeze. River may later choose copy-on-write checkpoints, but R5 is independent
and cannot be assumed by Phase 1.

## Decision

Select full-page images (FPI) for the first-dirty-after-checkpoint epoch as the
provisional Phase 1 mechanism. A checkpoint epoch starts at its journaled begin
boundary, but starting or publishing a fuzzy checkpoint does not itself release
any page-recovery pin.

The dirty-page table (DPT) owns, per page identity/generation:

- the earliest `recoveryRecToken` not proved present in a forced page or newer
  validated recovery base;
- the current `PageWalToken`, dirty/writeback epoch, and optional in-flight
  flush-image token;
- the FPI record/base token protecting each still-required checkpoint epoch;
- the latest page token proved stable by a completed data-file force.

Before the first modification in an epoch, while holding the page exclusively,
River captures the complete validated pre-modification page, including its
`PageWalToken`, into immutable owned bytes. It publishes a checksummed FPI
record containing those bytes, page/database/file identities and generations,
and checkpoint epoch before publishing/applying that modification. If the page
was already dirty at the epoch boundary, the FPI may contain earlier unforced
changes; therefore the older DPT/transaction pins remain until the FPI and all
required predecessor state are forced and covered by a published checkpoint.

Writeback has two different completion facts:

1. Under the page latch, capture an immutable `PageFlushImage` with identity,
   allocation generation, dirty epoch, checksum, and exact `PageWalToken`.
2. Release the latch and wait for a same-lineage `DurableWalEnd` covering the
   image's exclusive `recordEndLsn`.
3. Write the complete image. Successful full write completion makes it
   `written`, but not stable or eligible to release any recovery pin.
4. Force the containing data file through the platform durability contract.
   Only successful force makes that exact image `stable` and permits the
   stable-page token to advance.
5. Relatch. Clear dirty state only if page identity, allocation generation,
   dirty epoch, and current token still equal the forced image. If the page was
   redirtied, retain the earliest record not covered by the forced image and all
   newer epoch/FPI pins.

Recovery detecting a bad page checksum restores a same-lineage validated FPI
or checkpoint base and redoes the complete forced suffix in token order. A
missing, corrupt, wrong-generation, or ambiguous base fails closed; it never
becomes an empty or guessed page.

## Invariants

- The first-dirty epoch transition is atomic with respect to page latching; at
  most one required FPI is selected and no change precedes it in WAL order.
- FPI bytes are an immutable validated snapshot and cannot alias a reusable
  buffer after publication.
- An FPI is usable only for the exact database, file, page, generation, and
  checkpoint/WAL lineage encoded in the record.
- Fuzzy-checkpoint capture includes DPT entries, in-flight flushes, FPI pins,
  stable-page tokens, and transaction predecessor/undo horizons. Epoch rotation
  cannot discard an older pin merely because an end-checkpoint was appended.
- A redirty during write or force cannot be cleared by the older completion and
  cannot move `recoveryRecToken` past the first unforced change.
- Page flush uses same-lineage `DurableWalEnd`, never journal acceptance,
  replication memory, quorum position, or `CHECKPOINTED`.
- A page/FPI redo pin may advance past a record only after either (a) the exact
  checked page image covering it is data-file-forced or (b) a validated forced
  recovery base plus complete required forced suffix covers it. Transaction
  predecessor/undo, checkpoint, backup, and other lease pins must also release.
- `safeTruncate` is the minimum of those exact per-page proofs and every other
  recovery/retention consumer. Written-but-unforced pages contribute no proof.
- Recovery never repairs a checksum failure from an unverified image.

## Consequences

FPI avoids a separate double-write area's allocation, overwrite, and recovery
protocol, but can increase WAL volume after checkpoints. Checkpoint cadence and
FPI compression are policy/measurement questions, not changes to the invariant.

## Alternatives

- A double-write area remains the fallback if P09 proves FPI amplification or
  tail latency unacceptable. Selecting it requires a replacement ADR with
  slot-reuse, force ordering, checksum, and recovery invariants.
- Checksums alone were rejected because detection is not repair.
- Assuming atomic page writes was rejected absent platform proof.

## Required evidence

Before G0 accepts this decision, P09 supplies measured FPI-versus-double-write
amplification, copy, and latency evidence plus a deterministic prototype review
of the ordering below. K10/K11 then run the complete production crash matrix
before G1; production-kernel evidence is not a prerequisite for authorizing the
kernel implementation.

- Crash before FPI publication proves no associated mutation can reach disk.
- Crash after FPI/change publication but before WAL force proves the page was
  not written and the unforced tail is ignored.
- Crash after WAL force but before page write replays from the old page/FPI.
- Crash during or after page write but before data-file force treats the page
  as possibly old, new, or torn and reconstructs it from FPI plus redo.
- Crash after data-file force but before stable-token/DPT bookkeeping validates
  the page while retaining WAL conservatively.
- Crash after an older flush force while the page is redirtied retains the
  newer DPT/FPI/redo pins and never clears dirty state.
- Crash after checkpoint records are forced but before master-control install
  ignores that checkpoint; after install it uses the captured DPT/pin set.
- Segment removal tests prove a forced page or validated base-plus-suffix proof
  and all transaction/lease releases precede durable directory removal.
- Torn/short/reordered write tests and corrupt-FPI rejection.
- P09 WAL amplification, checkpoint storm, copy, and p99/p99.9 measurements
  against a double-write prototype on the declared P05 reference host.

## Authoritative context

- [High-level buffer/recovery plan](../plans/river-high-level-plan.md)
- [Implementation plan K10](../plans/river-project-implementation-plan.md)
- [Replicated journal plan](../plans/river-replicated-journal-durability-plan.md)
