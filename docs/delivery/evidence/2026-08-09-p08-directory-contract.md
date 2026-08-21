# P08 general-directory contract evidence

Date: 2026-08-09

Status: local deterministic fake evidence; K01 physical qualification and
cross-slice failure registries remain mandatory at their owning later gates

## Contract delivered

`DurableDirectory` now covers the general Phase 0 breadth required by ADR 0003:

- validated direct-child directory and named-file creation;
- caller-owned fixed-capacity listing with file/directory kinds;
- same-directory non-replacing rename and explicit replacing rename;
- file or empty-child removal;
- named-file truncation returning the file capability required to force its
  content and length;
- parent-directory namespace force; and
- named-file reopen.

Every method is synchronous. A before-boundary delay returns `RETRY` without
mutation. An after-boundary delay is absorbed before return, so callers never
observe an applied-but-pending directory operation. Non-OK outcomes do not
imply non-application: the caller-owned `DirectoryOperationResult` reports
`NOT_APPLIED`, `VISIBLE_NOT_DURABLE`, `DURABLE`, or `UNKNOWN` explicitly.

File force and directory force are deliberately independent. Truncated bytes
and length survive only after `CONTENT_AND_METADATA` file force. Parent force
does not publish file bytes. Creation, rename, replacement, and removal become
namespace-durable only after parent-directory force. This retains the exact
temporary-write/file-force/replace/directory-force protocol of the separately
reviewed atomic installer.

Names are validated at the external/path boundary and must be non-blank direct
children of at most 128 characters; path separators, `.` and `..` are rejected
before mutation. Internal result and buffer carriers are otherwise trusted.

## Bounded ownership model

`DirectoryListResult` allocates its arrays once at construction and borrows the
provider's stable immutable name references during scans. A full carrier
returns `RESOURCE_EXHAUSTED`, preserves its bounded prefix, and marks the scan
incomplete. Callers may retry with a larger explicitly bounded carrier. The
provider clears and reuses the carrier without per-entry result allocation.

Each completed listing records the provider generation. Crash increments that
generation and invalidates all previously opened `DurableFile` capabilities;
stale read, write, force, truncate, size, and close paths return stable status
codes rather than throwing for routine lifecycle control. Reusable entry slots
carry a monotonically changing epoch, so a capability for a removed entry
cannot alias a later file that occupies the same slot.

Operation and injected-fault traces are fixed-capacity. Trace saturation is
reported separately as `RESOURCE_EXHAUSTED` and never changes the storage
operation's outcome.

## Deterministic evidence

`FaultingDurableDirectory` keeps independent volatile and durable images for
file bytes/length and directory names/kinds. Its provider-neutral
`DurableDirectoryContractSuite` and direct regressions cover:

- successful create, write, file force, directory force, crash, restart, list,
  reopen, size, and read;
- old-name restoration after an unforced rename and new-name survival after
  parent force;
- restoration after unforced removal and durable absence after parent force;
- restoration of the old length when truncation is not file-forced, including
  the proof that directory force is not a substitute;
- survival of the new truncated length only when file force was applied;
- process crash and in-call restart immediately before and after every modeled
  create, list, rename, remove, truncate, read, write, file-force,
  directory-force, and reopen boundary;
- exact old/new visibility at every mutation and force boundary;
- short-write, partial-write, and disk-full prefix accounting followed by safe
  resumption;
- file-force and directory-force failure without false durability promotion;
- bounded list backpressure, stable borrowed names, and generation changes;
- stale file-handle rejection after restart;
- before/after delayed synchronous completion;
- fail-closed rejection of incompatible fault scripts before mutation; and
- bounded trace saturation without changing storage outcomes.

The suite is provider-neutral through `DurableDirectoryContractProvider` and
its factory. K01 adapters can run the same semantic scenarios while supplying
their own process-control and fault mechanisms.

The independently reviewed changes were integrated on `codex/implementation`
through commit `f105eb5`. The combined local gate reran dependency inventory,
source policy, module graph, and both platform and testkit suites: 74 Gradle
tasks executed and 120 tests passed with zero failures or errors.
The authoritative `./verify --rerun-tasks` gate then reproduced all 58 declared
archives across two clean uncached builds and executed the final 117-task
check; 218 tests passed with zero failures, errors, or skips.

## Deliberate physical limits

The fake proves River's state machine, ownership, ordering, and result
semantics. It does not model an operating-system page cache, writeback races,
stale controller reads, torn directory blocks, filesystem journal modes,
device volatile caches, flush command translation, or power removal. Its flat
child-directory entries also do not pretend to be a recursive filesystem.

K01 must implement the Java NIO adapter, declare the JVM/filesystem/mount/device
matrix, run short-I/O and force-failure integration tests, and perform external
crash/power-loss qualification. No production durability claim follows from
this deterministic evidence or from a successful Java force call alone.

P08 is therefore `implemented`, not `passed`: the Phase 0 deterministic
mechanics and ADR 0003 directory breadth exist and have independent review,
while P02/P03/P07 promotion and final foundation review remain open. WAL, page,
checkpoint, backup, and consensus crash registries are mandatory at their
owning later gates; they are not circular P08/G0 prerequisites.
