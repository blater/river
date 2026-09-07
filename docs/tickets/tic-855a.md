---
id: tic-855a
status: closed
type: task
priority: 2
assignee: blater
delivery: code
base-commit: 5627543e624ac5a22d35e940e1f8bf6cf506b325
branch: ticket/tic-855a-test-kernel
delivered-commit: da09f0156173bf3c9e8a92f858aea003a729524a
tags:
    - testing
    - maintenance
created: 2026-09-07T20:19:28.642435Z
---
# Replace fragile kernel test observations with owned behavior

Implement only the accepted kernel findings from tic-37c1, preserving the named survivor coverage.


## Scope and validation

Use the accepted decisions in [tic-37c1](tic-37c1.md). Test-only changes; preserve
production behavior and named coverage. The lead owns serial Gradle validation
with --no-daemon, integration, and closure. No TPS or provenance redesign.

## Delivery evidence

Lock durations use deterministic zero/positive/backward timestamp inputs,
including accumulation. The lifecycle counter test remains. Deadlock coordination
observes synchronized lock-wait admission rather than JVM thread state; borrowed
token liveness and revocation checks remain.

Descriptor coverage compares unused maximum addressability with a small populated
reservation, then verifies that 128 distinct descriptors stay within reserved
accounting. It no longer reflects exact private cache lengths. An initial lead
review addition incorrectly required identical total memory at small and maximum
capacity; the focused run rejected it. The corrected comparison allows legitimate
radix directory storage while rejecting proportional eager allocation. Existing
decode, stale-shape and allocation tests remain.

Root recovery corrupts the actual flushed root page, verifies every recovered row
and a subsequent insert. Exact root/page numbers and redundant split/WAL smoke
assertions are removed; model-based split and WAL codec tests remain.

Luna authored these rewrites; the lead independently checked concurrency
visibility, memory ownership and durable repair invariants and corrected the
retention comparison. Validation passed: all 147 transaction tests and 42 engine
tests across `IndexedLockWaitTest`, `IndexedRelationalWalHarnessTest` and
`IndexedTableTest`, zero failures/errors/skips. Command:
`./gradlew --no-daemon :river-tx:test :river-engine:test --tests
io.riverdb.engine.table.IndexedLockWaitTest --tests
io.riverdb.engine.table.IndexedRelationalWalHarnessTest --tests
io.riverdb.engine.table.IndexedTableTest`. Corrected run: 32s, transaction tests
reused their passing first-run result. Logs: `kernel.log` and
`kernel-corrected.log` in `/private/tmp/river-test-streamline-evidence-20260907/`.
No production code changed.

Combined clean validation and integration evidence: [test streamlining delivery](../delivery/evidence/2026-09-07-test-streamlining.md).
