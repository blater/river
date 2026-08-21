# ADR 0003: Runtime and durable I/O

Status: Accepted

## Context

River needs one portable correctness path and explicit crash semantics without
creating a P08/K01 dependency loop. Java and filesystem calls do not by
themselves prove survival across power loss.

## Decision

Use JDK 25 and Gradle 9.7.0. The Java NIO `FileChannel` provider is the Phase 1
reference implementation. Mapped memory and native/direct-I/O providers are
optional accelerators behind the same River-owned platform contracts.

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

## Invariants

- Page/WAL code depends on platform SPIs, never `os.name` or raw NIO handles.
- I/O exceptions are converted once at the adapter to stable status and may
  trigger the single fatal fence when durability becomes indeterminate.
- A successful force is trusted only for a qualified JVM/filesystem/mount
  combination and the exact file/directory protocol tested.
- Native or mapped providers must pass the reference contract and crash matrix.
- No power-loss support claim follows merely from unit tests or API success.

## Consequences

Portable NIO remains the correctness baseline and native optimization stays
replaceable. Supported deployments may initially be narrower than platforms on
which River merely starts.

## Alternatives

- Mandatory native I/O was rejected as a portability and proof dependency.
- Mapped memory for all durable mutation was rejected until force, lifetime,
  and failure behavior are measured.
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
