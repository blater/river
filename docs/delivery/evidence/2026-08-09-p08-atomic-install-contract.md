# P08 atomic-install contract evidence

Date: 2026-08-09

Status: local deterministic fake evidence for the atomic-install subset; P08
directory breadth and filesystem qualification remain open

## Contract delivered

`river-platform` now separates file-content durability from namespace
durability through `DurableDirectory` and a resumable `AtomicFileInstaller`.
The installer exposes these ordered boundaries:

1. create a same-directory temporary entry;
2. write all content, including explicit short-write progress;
3. force temporary-file content and required metadata;
4. replace the destination with the temporary entry;
5. force the parent directory;
6. reopen and verify the installed bytes.

Caller-owned request, opaque progress, result, and snapshot carriers make each
acknowledged and applied half-step explicit. Only the installer-owned state
machine capability can mutate or inspect progress. It enforces provider
ownership, provider-issued per-install identity, monotonic transitions, exact
byte counts, and authenticated pending operation identities. The opaque
`AtomicInstallId` binds once to the immutable temporary name, destination name,
content length, and fingerprint. It can reconstruct an equivalent request
carrier, but a second install from the same provider cannot borrow its progress,
even when request versions and lengths match. Active reset is rejected without
mutation. A delayed after-boundary completion records the applied phase without
acknowledging it. The next `advance` polls that operation identity; it does not
repeat a write, replacement, or force. A process-generation change immediately
turns progress into `RECOVERY_REQUIRED`, including an injected restart that is
already running when the call returns.

`DurableDirectory` is synchronous. It cannot return an ambiguous
applied-but-pending result; delayed completion is absorbed before return. The
temporary file's content-and-required-metadata force is a different step from
the parent-directory namespace force.

The operation path uses stable `StatusCode` values rather than exception control
flow. Request/progress/result/snapshot storage and traces are fixed or
caller-owned. The deterministic installer reuses its directory and snapshot
carriers; payload storage and traces are bounded at construction. Install
content is borrowed until terminal progress/reset. Its position, limit, and
fingerprint are checked before every modeled boundary, so mutation fails before
further I/O.

## Deterministic evidence

The `FaultingAtomicFileStore` keeps independent volatile and durable images for
file bytes and directory names. Its contract suite proves:

- the exact create, write, file-force, replace, directory-force, verify order;
- survival only after both content and parent-directory force;
- safe delayed start and delayed completion at every install boundary;
- exact before/after fault admission, with unsupported scripts rejected before
  mutation;
- short-write, short-verification-read, and disk-full prefix reporting with
  bounded resumption;
- file-force and directory-force failure without false phase promotion;
- crash immediately before and after every boundary;
- restart immediately before and after every boundary, with no stale phase or
  handle promotion;
- restoration of the old destination after replacement but before directory
  force;
- reopen verification failure on injected corruption; and
- bounded trace saturation without changing the install outcome;
- same-provider, same-version, same-length cross-install rejection at every
  reached phase; and
- crash restoration proving rejected cross-wiring cannot promote an unforced
  second install.

`AtomicFileInstallerContractSuite` is provider-neutral. A provider factory and
adapter run ordering, delayed completion, crash visibility, old-destination
restoration, file/directory force failures, bounded trace, and cross-install
identity scenarios against the deterministic fake today and future NIO, mapped,
or native providers.

## Review and integrated validation

The first independent review rejected caller-forgeable progress, stale phase
promotion after restart, a fake-only rather than provider-neutral suite,
post-mutation fault rejection, ambiguous asynchronous directory operations,
and underspecified borrowed buffers. The repaired contracts authenticate
provider-owned transitions, fail closed on generation changes, distinguish
before/after fault compatibility, make directory operations synchronous, and
fingerprint borrowed content through its terminal lifetime.

The re-review then reproduced a same-provider cross-install exploit: two
same-version/same-length requests could share progress and let the second skip
its file force. Provider-issued `AtomicInstallId` binding and the exact crash
regression close that path. The final reviewer found no remaining required
issue for the explicitly bounded atomic-install subset.

At integrated commit `33f6aa2`,
`RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks`
completed successfully. Both clean archive builds matched, the final gate
executed 117 tasks, and 192 tests ran with zero failures, errors, or skips.

## Deliberate limits

This fake models the protocol state machine, ordering, short I/O, failure, and
process-crash visibility. It does not model an operating-system page cache,
stale reads, controller caches, torn directory blocks, device flush commands,
or power removal. It therefore supplies P08 contract evidence only.

K01 must implement the NIO provider and rerun the provider-neutral contract on
declared JVM, filesystem, mount, and device combinations. Physical durability
claims additionally require the real crash/power-loss matrix named by ADR 0003
and cannot be inferred from this fake or from successful Java API calls.

This slice intentionally implemented only the `DurableDirectory` operations
needed by atomic file installation: same-directory temporary creation,
replacement, parent force, and reopen. The separate
[general-directory contract evidence](2026-08-09-p08-directory-contract.md)
now closes that local breadth gap without changing the reviewed installer state
machine. P08 is `implemented`, not `passed`: neither deterministic slice
substitutes for K01 physical qualification, and later owning slices must still
register their remaining named failure points.
