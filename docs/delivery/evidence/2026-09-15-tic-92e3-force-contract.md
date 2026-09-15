# tic-92e3 force overlap contract

Date: 2026-09-15
Source commit: `61d24d0060cdb42495b4473c00e0f9d611104149`
Status: design accepted for implementation; no implementation or workload evidence in this document

## Scope and immediate consumer

`tic-6f81` is closed and integrated at the source commit above. Its WAL admission,
prepared-page ownership, footer, quorum, and fault semantics are prerequisites for
this contract.

The immediate production path is `EmbeddedDatabaseOpener` ->
`NioDurableDirectory` -> `LocalWal`, with the WAL opened in `MAPPED` mode. Primary
and follower WAL files therefore use `NioDurableFile`. The native
`ApfsRiverFile`, `LinuxRiverFile`, and `WindowsRiverFile` implementations serve
daemon file lifecycle; they are not WAL providers on this path. `tic-92e3` does
not refactor or qualify those native wrappers. Fault providers used by focused
WAL tests must preserve the NIO force handoff behavior they wrap.

This is an internal construction invariant, not a new capability flag or a
widening of the `RiverFile` contract. A future non-NIO WAL provider must supply
equivalent overlap and lifetime semantics when it becomes an immediate consumer.

## One writer and one force worker

The existing group-commit coordinator remains the sole transaction executor,
queue owner, WAL writer, quorum owner, and completion publisher. Add exactly one
force worker to `LocalWal`. Its single command/result mailbox carries only the
captured file identity, WAL generation and token, half-open force range, force
mode/cause, record count, terminal CSN/digest, timings, and status. It owns no
transaction, lock, page, quorum, or mutable WAL-assembly state.

There is no second transaction executor or queue. There is at most one submitted
force target because the mailbox has one command slot. Later commits accumulate
in the writer-owned sealed suffix; its size follows admitted transaction and page
resources rather than an arbitrary cohort cap.

At a safe cohort boundary the writer first consumes a completed force result and
reclaims every covered cohort in order. If no force is active and a sealed suffix
exists, it submits that exact suffix immediately. Only then may it select more
work. If admission pressure depends on the retained durable prefix, the untouched
head is requeued and waits for the independently observable force completion.
The force worker performs no admission, and it releases provider pins before
publishing completion, so neither side can wait for capacity held by itself.

## Cohort and publication ownership

The writer keeps an open append group, a sealed but unsubmitted scalar suffix,
and one submitted target. Before sealing its footer, the writer performs the
existing publication/lock handoff for every member and transfers installed-page
ownership into the cohort. It then finishes the footer and seals an immutable
file object, database/WAL generation, token, `[start, end)` range, aggregate
record count, final CSN, and final digest. Every seal advances the append tail and
digest and resets the open scratch, including while a prior target is active; it
does not advance the local durable prefix. Submission later captures the sealed
suffix and publishes only that immutable target with the mailbox release edge.

The worker acquires the command, performs the provider force, and releases the
result. The writer acquires that result and validates identity, generation,
token, range, digest, and status. Successful local force advances the exact local
`durableEnd`, durable CSN, and durable digest immediately. Quorum is a subsequent
acknowledgement gate: a later quorum failure fences the database and fails the
affected members, but does not roll back the locally durable prefix. Members
complete in FIFO order only after the captured target's required quorum has
confirmed that same generation, end, and digest. A later target may cover
several sealed groups; durable-cursor advancement skips their already-written
footers without rewriting them.

Installed `IndexedPreparedPageBatch` publication pins transfer into a flat
cohort-owned frame chain before the batch scratch is reset. The existing
transaction lease stays live until completion, so logical write, staging,
version, and WAL demand remain charged; `tic-5b3e` remains the physical page
owner. There is no per-cohort page-array allocation.

Any partial record/footer write, force failure, stale or mismatched result,
digest failure, or quorum failure fences the database before pending waiters are
woken. An earlier acknowledged durable prefix remains valid. Maintenance,
direct/logical streams, checkpoint, vacuum, rotation, and close drain this same
pipeline; they cannot call a second force path.

## NIO mapped lifetime and overlap

`NioDurableFile` currently holds the `mappedData` monitor through both mapped
transfer and blocking force. Replace that long critical section with a short
mapping-lifecycle lock. Keep the existing one 4 KiB header mapping and one 16 MiB
data window; do not add a mapping pool.

When a mapping is created, create separate writer and force duplicates over the
same shared mapping. Each duplicate object is thread-confined, so buffer
position/limit/mark are never shared. Under the lifecycle lock, force submission:

1. captures the dirty data prefix intersecting `[start, end)` and the current
   header/metadata obligation;
2. pins the header and data mappings and records their file generation; and
3. publishes the immutable provider force slot.

The worker forces those pinned views outside the lifecycle lock. The append
writer may concurrently write only the disjoint suffix at offsets greater than
or equal to the captured end and records that suffix as new dirt under the lock.
Success subtracts only the captured dirty interval and captured metadata epoch.
Failure merges both obligations back before unpinning. Thus a later append can
never be cleared by an earlier force result.

Pins prohibit unmap, remap, truncate, rotation, and close of the captured file
generation. Prior evicted mappings need no descriptor because their existing
release path synchronously forces them before closing their arena. If an append
crosses the active mapping boundary while force holds a pin, the transfer waits
for that independent provider pin to clear, synchronously forces any remaining
suffix dirt, remaps, and continues the same transfer. It must not return `RETRY`
after partial record bytes. The worker unpins in `finally` without waiting for
the writer to consume the force result, so this boundary wait cannot deadlock.
Close and rotation first stop new admission, then join the provider pin without
holding the lifecycle lock needed by the worker.

The memory-order chain is explicit: writer byte stores precede the lifecycle-lock
dirty publication; the force snapshot acquires that lock; command publication and
worker acquisition order the snapshot; result publication and writer acquisition
order completion. This contract relies on the JDK 25 APIs for
[`ByteBuffer.duplicate()`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/ByteBuffer.html#duplicate()),
under which duplicates share content but have independent buffer state, and
[`MappedByteBuffer.force(int,int)`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/MappedByteBuffer.html#force(int,int)),
which writes the selected mapped region to local storage while permitting wider
writeback. The disjoint-suffix rule plus mapping pins is the design inference
from those guarantees. Focused held-force tests must validate it on the
repository's pinned JDK 25 before acceptance.

## Exact retained-byte accounting

Use the current page-cache accounting constants: 16-byte array/object header,
8-byte reference, and 8-byte alignment. Define, with checked add/multiply,

`A(n, e) = align8(16 + n * e)`.

Let `T = TransactionManager.maximumActiveTransactions()` and let `F` be the
compiled current-frame count. The flat pending structure is exactly:

- five `T`-entry 8-byte arrays: request references, member CSNs, member row
  counts, cohort tokens, and required WAL ends;
- three `T`-entry 4-byte arrays: cohort member starts, cohort member counts, and
  cohort frame heads; and
- one conservatively modeled ring owner: a 16-byte object header, eight array
  references (64 bytes), six 4-byte member/cohort head-tail-count counters (24
  bytes), and one 8-byte next-token field, aligned to 112 bytes.

The force worker, one-slot command/result mailbox, and their bounded owner and
carrier objects receive a conservative 4,096-byte admitted fixed-state budget.
Implementation must keep their retained Java objects within that budget; it is
not asserted as their exact JVM footprint. The runtime-managed worker thread
stack remains part of the existing runtime allocation. Therefore the checked
database-lifetime pipeline charge is:

`retainedBytes(T) = 5 * A(T, 8) + 3 * A(T, 4) + 112 + 4096`.

`retainedBytes(T)` is computed before coordinator construction and added, with
the already-admitted diagnostic payload, to the existing
`DatabaseProviderLease` claim. Arithmetic overflow or a claim beyond the
configured database retained-byte budget returns `RESOURCE_EXHAUSTED` before
these objects or the force worker are created. No pending-cohort limit exists
apart from the budget-derived `T`.

Publication linkage uses per-frame scalar storage charged by
`DatabasePageCacheRetainedLayout`: one `long[F]` durability-owner-token array and
one `int[F]` durability-next-slot array. Its exact additional charge is
`A(F, 8) + A(F, 4)`. The plan compiler includes this before choosing `F`, so the
existing page-cache lease covers the resulting compiled maximum. It is not also
charged to `retainedBytes(T)`.

## Required implementation evidence

Focused deterministic tests must demonstrate:

- a held NIO data-range force overlaps a same-mapping append wholly at or beyond
  the captured end, and the later dirt remains for the next force;
- a held force at a mapping boundary lets the partially progressed transfer join
  the provider pin, remap, and finish without a false retry, deadlock, use after
  close, or lost dirt;
- captured header/metadata lifetime survives rotation and close, which wait for
  unpin and cannot accept a stale generation result;
- transactions A, B, and C can install successive generations of the same page
  while A's force is held; A completion releases only A's cohort ownership and
  cannot release, expose, or reclaim B/C publication pins or versions;
- several sealed footer-bearing cohorts can be covered by one later force and
  complete in FIFO order, while a force failure fences all affected waiters and
  preserves an earlier acknowledged prefix;
- cancellation before publication remains with the transaction's original
  cleanup owner; after publication/lock handoff the cohort remains the sole owner
  through local force, quorum, cancellation, or fence cleanup, with no leak or
  double release;
- local force success advances local `durableEnd`, CSN, and digest even when its
  later quorum fails; acknowledgement still requires quorum for that exact
  target, and no later suffix generation/end/digest can satisfy it;
- force and follower fault injection cannot bypass the single writer-owned
  completion path or release successor-generation state; and
- the exact formulas above admit at the configured boundary and fail one byte
  below it before coordinator/worker construction.

Affected NIO, WAL, engine, transaction, and resource-governor tests are the
implementation gate. Native daemon-file qualification and extra platform
matrices are outside this ticket because they have no immediate WAL consumer.

## Independent review disposition

The independent force-contract review conditionally accepted the design after
three corrections, all incorporated here: native daemon wrappers were removed
from scope; mapped overlap now requires thread-confined duplicates, a suffix at
or beyond the captured end, and pins across remap/close; and retained bytes are
enumerated and claimed before coordinator construction. Final integrator review
also separated local durability from quorum acknowledgement and restored the
same-page-generation and cancellation ownership cases. No design issue remains
open. Implementation acceptance still depends on the focused evidence above.
