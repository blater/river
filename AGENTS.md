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

## TPC-C performance loop

Use the standalone local harness at
`~/src/ingres/river-harness/benchmark` for TPC-C-derived workload checks and
River/MariaDB comparisons. Run it from the River repository root. The wrapper
builds only the required River classes, starts a fresh ephemeral River server
on an unused port, and stops it on success, failure, or interruption. It does
not run `clean`.

The harness provides two data profiles:

- `sample` has reduced cardinalities and is the normal development profile;
- `full` has standard TPC-C cardinalities and is an occasional wider sanity or
  capacity profile.

Both profiles implement `new-order`, `payment`, `order-status`, `delivery`, and
`stock-level`; `all` selects the standard 45/43/4/4/4 mix. A selected subset
retains and normalizes those relative weights. These are engineering workloads,
not audited TPC-C runs, and must not be reported as `tpmC`.

Choose the smallest level that can answer the current question:

1. **Focused smoke:** one affected category, sample data, one worker, and a
   short window. Use `--no-report` when immutable comparison evidence is not
   needed.

   ```sh
   ~/src/ingres/river-harness/benchmark run river tpcc sample new-order \
     --river-home="$PWD" --warmup=1s --duration=3s --workers=1 \
     --warehouses=1 --seed=42 --max-retries=3 --no-report
   ```

2. **Targeted contention or transaction interaction:** use only the implicated
   category or pair, a fixed seed, and enough workers to reproduce the boundary.
   Start with sample data and 10 seconds; lengthen the run before widening the
   workload.

   ```sh
   ~/src/ingres/river-harness/benchmark run river tpcc sample \
     new-order payment \
     --river-home="$PWD" --warmup=2s --duration=10s --workers=4 \
     --warehouses=1 --seed=42 --max-retries=3
   ```

3. **Paired mature-system comparison:** run exactly the same profile,
   categories, seed, warehouse count, worker count, retry limit, warmup, and
   measured duration against MariaDB and River. Change only the target and
   River lifecycle option.

   ```sh
   ~/src/ingres/river-harness/benchmark run mariadb tpcc sample new-order \
     --warmup=5s --duration=30s --workers=4 --warehouses=1 \
     --seed=42 --max-retries=3

   ~/src/ingres/river-harness/benchmark run river tpcc sample new-order \
     --river-home="$PWD" --warmup=5s --duration=30s --workers=4 \
     --warehouses=1 --seed=42 --max-retries=3
   ```

   The harness owns report locations below `river-harness/runs`; its friendly
   command does not accept an output-directory override. Both runs must report
   `status: passed`, zero failed/unknown outcomes, and successful invariants.
   Open the printed report paths and require
   `.comparison.eligibility == "eligible"` and identical `.comparison.key`
   values in `result.json` before comparing `.workload.committed_tps`, latency,
   or retries. A quick pair is diagnostic only. Any performance claim requires
   multiple longer interleaved samples, normally MariaDB/River/River/MariaDB,
   because short local runs exhibit substantial host variability.

4. **Occasional wider sanity:** after focused runs pass, exercise all five
   families. Use `sample all` for routine integration checkpoints; use
   `full all` only when data cardinality, access paths, cache behavior, page
   splits, or capacity are in scope.

   ```sh
   ~/src/ingres/river-harness/benchmark run river tpcc sample all \
     --river-home="$PWD" --warmup=5s --duration=30s --workers=8 \
     --warehouses=1 --seed=42 --max-retries=3

   ~/src/ingres/river-harness/benchmark run river tpcc full all \
     --river-home="$PWD" --warmup=5s --duration=1m --workers=8 \
     --warehouses=1 --seed=42 --max-retries=3
   ```

Run the equivalent MariaDB command only when a mature-system control is useful;
do not make a full cross-target matrix part of every edit loop. Warehouse and
worker sweeps belong to an explicit scaling investigation or promotion gate,
not routine correctness work.

The harness comparison and `tools/tps-test.sh` serve different purposes:

- use the harness for a shared logical workload and cross-engine behavioral or
  performance comparison;
- use `tools/tps-test.sh` for River-specific isolation, retry/deadlock,
  protocol, lock-wait, WAL, JFR, and workspace-fingerprint evidence;
- never compare a harness MariaDB TPS figure directly with a
  `tools/tps-test.sh` River figure because the workload implementations,
  profiles, and isolation contracts differ.

A River harness invocation is also a Gradle build and must obey the one-build-
at-a-time rule. Do not overlap any harness run with compilation, tests,
profiling, another harness run, or another database workload on the same host.
For MariaDB, use the wrapper rather than the low-level Go command so its guarded
Homebrew lifecycle and owned-database cleanup apply. If MariaDB is already
service-managed or active, do not stop the user's server; let the harness fail
safely and ask before changing external state. Set
`RIVER_HARNESS_MARIADB_PASSWORD` only when the selected local account requires
it; never print or persist the value.

## Hot-path engineering

- Do not impose arbitrary low row, byte, cardinality, or concurrency caps to
  make a prototype work. Implement against long-addressed or configured
  resource boundaries from the first production slice. A finite limit is
  acceptable only when it follows from an external format, an admitted runtime
  budget, addressability, or another named correctness contract. Page size,
  address or slot width, protocol framing, and configured resource budgets are
  legitimate structural bounds when their owning invariant is explicit.
  Document the status and recovery/backpressure behavior at every finite
  boundary; scale beyond it by paging, streaming, spill, continuation, or
  backpressure rather than a convenience or prototype cap.
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

## Slopmark boundary

Use `slopmark` as a stop-and-review signal while changing performance-critical
code. Capture a compact baseline over the affected production modules before a
slice and compare the touched files afterward, for example:

```sh
slopmark -compact -limit 50 \
  river-engine/src/main/java river-tx/src/main/java \
  river-bench/src/main/java river-jdbc/src/main/java \
  river-client/src/main/java river-protocol/src/main/java \
  river-server/src/main/java river-wal/src/main/java tools
```

The score is a review trigger, not a mechanical quality verdict. Stop adding
behavior and reconsider the design when a touched high-scoring file gains
another technical responsibility, its score materially worsens, or equivalent
policy starts appearing in multiple layers. In particular, stop when:

- benchmark-family semantics enter engine, transaction, WAL, or protocol
  internals;
- diagnostics, metrics, or formatting can alter transaction control flow;
- lock grant/fairness predicates are reimplemented outside one transaction-
  layer owner;
- commit stages or failure reasons are represented by overlapping enums or
  recorded by unrelated components;
- shell code duplicates Java defaults, semantic validation, classpath logic,
  or evidence validity decisions;
- a second executor, retry loop, value representation, result encoder, or
  commit path appears for implementation convenience.

Refactor toward the existing owning boundary before continuing: share policy,
not incidental syntax. DRY applies most strongly to technical responsibilities
and semantic decisions; small local duplication is preferable to coupling
unrelated modules through a speculative abstraction. Record the slopmark
before/after scores with performance evidence when the tool materially shapes
the refactor.

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
