# ADR 0003: Runtime and durable I/O

Status: Accepted

## Context

River needs one portable correctness path and explicit crash semantics without
creating a P08/K01 dependency loop. Java and filesystem calls do not by
themselves prove survival across power loss.

## Decision

Use JDK 25 and Gradle 9.7.0. The Java NIO provider uses mapped I/O for WAL files and positional
`FileChannel` I/O for other database files. Platform-specific native calls may be required for
correctness where Java NIO
cannot supply a required guarantee. They remain behind the same River-owned
platform contracts. WAL mappings are the normal implementation, with no positional WAL fallback.
Direct I/O is not part of this delivery.

Phase 0 P08 defines the minimal `FileIoProvider`, `DurableFile`,
`DurableDirectory`, clock, scheduler, memory, and fault SPIs plus
deterministic/faulting fakes. K01 implements the NIO provider and qualifies real
JVM/filesystem combinations. Durable-record owners compose their synchronous
installation protocol directly over `DurableDirectory`; there is no generic
installer lifecycle without a second production consumer. Thus tests can start
from a contract without pretending that a fake implements durable storage.

Durable installation uses: create same-directory temporary file, write full
content, force file content and required metadata, atomically rename or
replace, then force the parent directory. Redundant control slots include
identity, format, generation, length, checksum, and covered recovery boundary;
open chooses the newest fully valid generation and fails closed if none exists.
Creation, rename, replacement, truncation, and deletion have separately tested
directory-durability protocols.

## Mapped WAL

The mapped provider keeps a 4 KiB header window and one 16 MiB moving data
window per WAL handle. These bound mapping resources, not file size: positions
remain long-addressed, and moving a window forces its dirty contents and closes
its arena. Closing, invalidating or physically resizing a handle releases its
mappings before closing or truncating the channel.

WAL format v3 uses a 64-byte identity header. Each forced group consists of
its records followed by a checksummed footer binding the group boundaries,
record sequence and contents to its predecessor. The footer is appended to the
same WAL stream before one provider force. Only after that force succeeds may
River publish the captured durable frontier and acknowledge the transactions.
There is no independently synchronized end pointer.

Record coverage ends before the footer; a reader's next-record offset skips it.
Persisted page references use record coverage, while replay and forced cursors
use the next-record offset. The captured force range includes the footer.

Recovery accepts complete validated groups in order. It does not publish
transaction sequence or identifier maxima from an incomplete group. Before
admitting writes after recovery, physical tail repair removes any incomplete
suffix and stale preallocated bytes. Decisionless logical continuations retain
their existing recovery semantics across complete force groups.

An invalid final group is discarded in full, including an intact footer with
a torn body: an interrupted sync can persist either part first. A later valid
group makes preceding damage interior corruption, which recovery rejects.
Without a separate durable frontier, damage to the final acknowledged group
cannot always be distinguished from an interrupted final append.
Group atomicity is a recovery rule, not an assumption that an arbitrary-sized
filesystem write is atomic. Crash recovery assumes previously synchronized
bytes remain intact under the platform's durability contract.

Normal commit performs one provider force for the appended group. Moving a
mapping, growing an extent, creating a file and repairing a tail may require
additional maintenance synchronization. Ordinary commits use mapped
synchronization (`msync(MS_SYNC)` in the current macOS JVM), not a per-commit
`F_FULLFSYNC`. Successful process-recovery tests do not establish equivalent
hardware power-loss behavior across synchronization primitives.

## Invariants

- Page/WAL code depends on platform SPIs, never `os.name` or raw NIO handles.
- I/O exceptions are converted once at the adapter to stable status and may
  trigger the single fatal fence when durability becomes indeterminate.
- A successful force is trusted only for a qualified JVM/filesystem/mount
  combination and the exact file/directory protocol tested.
- Native or mapped providers must pass the reference contract and crash matrix.
- No power-loss support claim follows merely from unit tests or API success.

## Consequences

River retains one portable durability contract, implemented by NIO and, where
necessary, platform-specific native operations. The standalone server must
support macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS under the amended
[ADR 0014](0014-riverd-instance-security.md). Each provider needs actual crash
and power-loss evidence; those platform requirements do not claim completed
qualification. Evidence is reassessed when relevant operations or platform
dependencies change, not automatically for every unrelated launcher rebuild.

## Alternatives

- Mandatory native I/O was rejected as a portability and proof dependency.
- Mapping all database files remains outside the focused WAL delivery.
- Having K01 invent its own untestable SPI was rejected because it recreates
  the P08/K01 loop.

## Required evidence

- P08 durable-directory contract tests and named crash points.
- K01 owner-specific short-I/O, force-failure, replacement, directory-force,
  stale-temporary, and crash-image tests for database and checkpoint control
  records.
- A declared JVM/filesystem/mount/power-loss matrix before support claims.
- P09 NIO versus mapped/native and force-pattern results on P05 hardware.

## Authoritative context

- [Implementation plan P08, P09, and K01](../plans/river-project-implementation-plan.md)
- [High-level platform boundary](../plans/river-high-level-plan.md)
- [Performance plan](../plans/river-performance-review-and-benchmark-plan.md)
