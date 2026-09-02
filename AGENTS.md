# River Agent Working Agreement

This file is the short operational contract for coding agents and contributors.
The detailed rationale lives in
[`docs/plans/river-engineering-personas-and-performance-charter.md`](docs/plans/river-engineering-personas-and-performance-charter.md).

## Replace; do not preserve legacy behavior

- Architectural improvements replace the superseded design completely. Delete
  the old behavior, APIs, adapters, feature flags, tests, scratch state, and
  duplicate code paths in the same delivery.
- Backward compatibility with unreleased River behavior is a negative: it
  increases ambiguity, impedes delivery, and is not a reason to retain code.
- Do not introduce transitional wrappers or leave a legacy path for incremental
  caller migration. Change all River-owned callers and their tests together.
- Preserve compatibility only for an explicit external contract required by
  the user. Such an exception must name the contract, its owner, and its removal
  condition; implementation convenience is never sufficient.

## Delivery priority

- Deliver the smallest end-to-end database capability that advances the
  current functional milestone.
- Prefer working kernel, transaction, recovery, and relational behavior over
  scaffolding, governance, observability, or speculative future modules.
- Add infrastructure only for a named immediate consumer, and stop when that
  consumer is unblocked.
- Keep planned modules unwired and empty until production code needs the
  boundary.
- River is greenfield before V1.0. Change internal APIs and on-disk formats
  directly when that produces a cleaner design.

## Persona selection

Use personas as focused responsibilities, not as a reason to multiply agents.
The lead integrator owns the vertical slice and adds only the domain builders
needed for it: storage/recovery, transactions/concurrency, relational
execution, replication, runtime/performance, or boundary/operations.

Correctness-critical work receives an independent review lens appropriate to
the change: correctness adversary, architecture, performance/allocation,
relational semantics, boundary/security, or operations/compatibility. The
author may self-check through those lenses, but durable-format, recovery,
concurrency, consensus, and security work is not approved solely by its author.

Parallel agents must have disjoint file ownership and one integrator. Do not
start multiple agents merely to create activity, and do not let two agents
redefine the same contract. The full persona and review matrix is in sections 2
and 3 of the engineering charter linked above.

## Fast build loop

Use targeted, daemon-backed Gradle tasks while editing:

```sh
./gradlew :river-engine:compileJava
./gradlew :river-engine:test \
  --tests io.riverdb.engine.relational.RelationalDatabaseTest
```

- Run only one Gradle build at a time in a shared checkout.
- Never run `clean` concurrently with another build.
- Do not use `--no-daemon` for ordinary edit/compile/test feedback.
- Select the narrowest module, test class, or test method that proves the
  change. Expand to affected-module tests and policy checks before commit.
- Reserve `./verify` and `./verify-clean-checkout` for integration checkpoints,
  release evidence, or changes to the build itself. They intentionally perform
  destructive cold/reproducibility work and are not iteration commands.
- Do not enable the Gradle configuration cache globally without a measured
  trial and compatibility fixes.

Parallel agents that need to build must use separate Git worktrees. Each
worktree also needs its own Gradle user home and project cache:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-agent-a \
  ./gradlew --project-cache-dir /private/tmp/river-project-cache-agent-a \
  :river-engine:test
```

Changing only `GRADLE_USER_HOME` is insufficient when agents share a checkout,
because module `build/` outputs still collide. See the Gradle
[daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html) and
[command-line](https://docs.gradle.org/current/userguide/command_line_interface.html)
documentation.

## Hot-path engineering

- Zero steady-state allocation is the aspiration for WAL, page, lock,
  transaction, queue, and vector inner paths. SQL execution must not allocate
  per row.
- Zero-copy is an ownership discipline, not permission for unsafe aliasing.
  State who owns a buffer, how long it is valid, when it becomes immutable, and
  when it can be reused.
- Encode directly into provider-owned reserved storage when practical. Reuse
  checksums, buffers, result carriers, and I/O state.
- Count River-owned copies and allocations on important paths. A bounded copy
  is acceptable when it creates a necessary lifetime or consistency boundary.
- Prefer primitive fields, arrays, bitsets, and caller-owned result objects in
  the kernel. Avoid boxing, streams, iterators, varargs, captured lambdas, and
  formatted strings in hot loops.
- Keep every queue, arena, history, lock table, and retained view bounded. A
  bound must have an explicit status and recovery/backpressure behavior.

## Errors and trust boundaries

- Expected outcomes return `StatusCode` and optionally populate a reusable
  `StatusDetail`. They do not throw.
- Do not use exceptions for retry, conflict, cancellation, resource pressure,
  validation, I/O status, or SQL errors. Translate exceptions from Java APIs at
  the adapter boundary. JDBC may create `SQLException` only at its public
  boundary.
- Validate external/user input, SQL, protocol frames, configuration, persisted
  bytes, and replica messages before admitting them.
- Trust validated typed values passed between River-owned internal services.
  Do not scatter redundant null/range checks where River controls every caller.
- Diagnostics explain an outcome; they are not control flow. Avoid duplicate
  logging while propagating a status.

## Coupling and ceremony

- Use two-space indentation and no tabs.
- Prefer concrete, local code and shallow control flow.
- Introduce an interface only for a real ownership/architecture boundary or a
  second implementation/test provider.
- Gradle project dependencies use `implementation` by default. An `api` edge
  needs an explicit public-contract reason and a compile-visibility test.
- The dependency graph is a maximum allowlist, never a requirement to declare
  every permitted edge. Declare only dependencies used by current source.
- Do not leak implementation packages or kernel types through public, protocol,
  or client boundaries.
- Add an ADR only for a durable, public, cross-module, or hard-to-reverse
  decision. A local reversible implementation choice needs tests and a clear
  commit message, not a ceremony trail.
- Comments document invariants, ownership, units, memory ordering, and durable
  ordering. They do not narrate syntax.

## Completion standard

A change is complete when its user-visible behavior works through the real
path, focused tests cover success and the material failure/recovery boundary,
hot-path allocation/copy expectations are checked where relevant, affected
module tests pass, and unrelated files remain untouched.
