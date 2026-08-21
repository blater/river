# River Engineering Personas and Performance Charter

<!-- markdownlint-disable MD013 -->

Status: Accepted working agreement

Accepted: 2026-08-09 through the project-owner Phase 0 decisions and the
instruction to implement this chartered agent, review, style, trust-boundary,
status, and performance model

Audience: River contributors, coding agents, reviewers, and maintainers

Related plans:

- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)
- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)
- [River Replicated Journal, Durability, and Storage Evolution Plan](river-replicated-journal-durability-plan.md)
- [River and TigerBeetle: Comparative Analysis and Recommended Choices](river-tigerbeetle-comparison-and-recommendations.md)
- [River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)

## 1. Purpose

This charter defines how a mixed team of implementation and review agents
should build River. It also establishes the initial engineering agreement for:

- architectural ownership and review;
- coding style;
- trusted and untrusted input boundaries;
- status propagation, diagnostics, and fatal failure;
- low-GC and zero-copy design;
- performance evidence and merge gates.

The personas are review lenses, not organizational silos. One person or agent
may hold several personas on a small change, but the author must not be the only
reviewer for correctness-critical, durable-format, consensus, concurrency, or
security work.

## 2. Recommended persona mix

### 2.1 Core build personas

| Persona | Primary responsibility | Typical deliverables | Mandatory review requested from |
| --- | --- | --- | --- |
| Lead integrator | Own the slice, keep changes small, resolve cross-module decisions, and maintain the delivery plan | Slice brief, dependency map, integration PR, decision log | Architecture steward and relevant specialist |
| Storage and recovery engineer | Pages, buffer ownership, WAL, checkpoint, recovery, corruption handling, and storage inspection | Formats, state machines, fault matrix, recovery tests | Correctness adversary and performance reviewer |
| Transaction and concurrency engineer | MVCC, locks/latches, commit visibility, deadlock, transaction status, and isolation | History model, ordering rules, concurrency tests | Correctness adversary |
| Relational execution engineer | SQL semantics, catalog, planner, vectors, operators, indexes, and constraints | Semantic tests, plans, vector kernels, execution profiles | Relational semantics reviewer and performance reviewer |
| Replication and distributed-systems engineer | Consensus adapter, journal frontiers, membership, failover, state sync, and idempotency | Protocol model, simulation tests, failure proofs | Correctness adversary and architecture steward |
| Runtime and performance engineer | Memory ownership, queues, I/O mechanics, allocation/copy budgets, scheduling, and profiling | JMH/JFR evidence, allocation reports, capacity model | Owning subsystem engineer |
| Boundary and operations engineer | Embedded API, protocol/JDBC, authentication, validation, administration, observability, and upgrades | Boundary tests, threat model, operational runbooks | Boundary/security reviewer and operations reviewer |

Not every slice needs every persona. Start with the lead integrator and one or
two domain builders. Add a specialist when the slice crosses that specialist's
invariants; do not add parallel implementers merely to increase activity.

### 2.2 Review personas

| Persona | Review question | Blocking findings |
| --- | --- | --- |
| Architecture steward | Does this preserve module direction, ownership, simple interfaces, and the agreed storage/transaction/replication model? | New dependency cycles, leaked implementation types, duplicate sources of truth, or an unexplained new abstraction |
| Correctness adversary | How does this fail under crash, retry, race, cancellation, partial I/O, disk full, failover, or replay? | Missing invariant, ambiguous transaction outcome, unsafe recovery, unbounded retry, or untested state transition |
| Performance and allocation reviewer | What allocates, copies, blocks, contends, or grows on the hot path, and what evidence supports the choice? | Per-row/per-record allocation in a designated zero-allocation path, unbounded queue/history, avoidable copy, or absent benchmark for a material hot-path change |
| Relational semantics reviewer | Are SQL visibility, null semantics, types, indexes, constraints, catalog, and transaction behavior still one atomic model? | Index/base-table disagreement, incorrect SQL result, or semantics moved into a non-authoritative asynchronous path |
| Boundary and security reviewer | Is validation located exactly at the trust boundary, with safe resource limits and error disclosure? | Unvalidated external length/count/identity, unsafe allocation, trust-boundary bypass, or redundant validation pushed throughout trusted internals |
| Operations and compatibility reviewer | Can an operator diagnose, recover, upgrade, and safely stop this behavior? | Silent data-risk state, incompatible format change, missing diagnostic identity, or no supported recovery path |

The architecture steward is a reviewer, not a second implementer. They should
prefer deleting or narrowing abstractions, require an ADR only for durable or
hard-to-reverse choices, and avoid redesigning a local change without evidence.

### 2.3 Suggested mix by change type

| Change | Builder mix | Required independent lenses |
| --- | --- | --- |
| Local refactor with no behavior/API change | Owning engineer | One domain reviewer; architecture only if dependencies move |
| New SQL/operator feature | Relational execution engineer | Relational semantics; performance if on a row/batch hot path |
| Page, WAL, recovery, or checkpoint change | Storage/recovery engineer, runtime engineer as needed | Correctness adversary, performance, architecture |
| MVCC, lock, or commit change | Transaction engineer plus affected storage/execution owner | Correctness adversary, relational semantics |
| Consensus, failover, or state sync | Replication engineer plus storage/recovery owner | Correctness adversary, architecture, operations |
| Protocol, JDBC, authentication, migration, or admin | Boundary/operations engineer | Boundary/security, compatibility; performance for streaming paths |
| Durable format or public API | Owning engineer and lead integrator | Architecture, compatibility, domain correctness |

## 3. Agent operating model

### 3.1 Start every slice with a small contract

Before implementation, the lead integrator records:

1. the user-visible outcome;
2. the authoritative state being changed;
3. invariants and trust boundaries;
4. the hot paths and expected allocation/copy behavior;
5. failure, cancellation, retry, and recovery behavior;
6. tests and measurements required to merge;
7. modules/files intentionally out of scope.

This is normally a short PR description or design note. A public API, durable
format, recovery rule, or consensus transition requires an ADR.

### 3.2 Implementation and review sequence

1. The owning builder produces the simplest reference behavior and focused
   tests.
2. The correctness adversary attacks state transitions and failure points.
3. The performance reviewer measures designated hot paths and identifies
   allocations, copies, contention, and queue growth.
4. The architecture steward reviews only after the behavior and measurements
   are visible, unless a dependency or format choice must be settled first.
5. The lead integrator resolves findings, reruns gates, and records any accepted
   debt with an owner and trigger for removal.

Review feedback uses four levels:

- `BLOCKER`: correctness, durability, security, compatibility, or unbounded
  resource risk;
- `REQUIRED`: violates this charter or an accepted ADR;
- `SUGGESTION`: simpler or clearer implementation with no contract violation;
- `QUESTION`: missing rationale or evidence.

Every blocking comment names the violated invariant or supplies a reproducer,
test, profile, or architectural rule. Style preferences enforced by tooling are
not repeated manually in review.

### 3.3 Independence rules

- The author may run a reviewer persona as a self-check, but that does not count
  as the independent review for critical work.
- A reviewer does not silently edit the author's branch to resolve a disputed
  design; they state the finding and expected property.
- Cross-cutting changes have one integrator. Multiple agents may implement
  disjoint pieces, but no two agents independently redefine the same contract.
- Review agents read the slice contract and relevant ADRs before commenting.
- Generated volume is not progress. Small, proved vertical slices are preferred
  to parallel scaffolding across many modules.

### 3.4 Fast build and worktree discipline

Iteration uses the narrowest daemon-backed Gradle command that proves the
current edit. Typical commands are:

```sh
./gradlew :river-engine:compileJava
./gradlew :river-engine:test \
  --tests io.riverdb.engine.relational.RelationalDatabaseTest
```

`./verify` is an integration gate, not an edit/compile loop. It intentionally
runs reproducibility work and a clean check with disposable Gradle processes.
Running it after every small edit wastes warm compiler/daemon state, destroys
incremental outputs, and makes agents sharing one checkout contend on locks and
module `build/` directories.

The working rules are:

- run only one Gradle build at a time in a shared checkout;
- never run `clean` concurrently;
- use the Gradle daemon for targeted compile and test feedback;
- expand from a test method or class to affected-module tests and policy checks
  before commit;
- reserve `./verify` and `./verify-clean-checkout` for integration, release
  evidence, or changes to the build itself;
- give every parallel building agent a separate Git worktree, a separate
  `GRADLE_USER_HOME`, and a separate `--project-cache-dir`;
- do not assume a distinct Gradle home makes shared module outputs safe; and
- trial configuration-cache support separately before enabling it globally.

This follows Gradle's documented
[daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html) and
[command-line](https://docs.gradle.org/current/userguide/command_line_interface.html)
models. River's root [`AGENTS.md`](../../AGENTS.md) is the concise operational
entry point for new agents.

## 4. Coding agreement

### 4.1 Formatting and readability

- Use two spaces for indentation. Tabs are prohibited in source, build files,
  configuration, and documentation examples.
- Enforce deterministic two-space text/style rules in CI. Add an automated
  reformatter only if inconsistent formatting becomes an observed maintenance
  problem.
- Braces are mandatory for conditional and loop bodies.
- Prefer early return and shallow control flow.
- Names describe ownership and units: for example `walBytes`, `deadlineNanos`,
  and `ownedBatch`.
- Comments explain invariants, ownership, memory ordering, durable ordering, or
  why a surprising choice is required. They do not narrate ordinary syntax.
- Avoid speculative generalization. Introduce an interface when it protects an
  architectural boundary or when a second implementation/test double exists.

The Phase 0 tooling decision pins deterministic style validation, static
analysis, forbidden APIs, dependency checks, and the allocation-test mechanism.
A reformatter is deliberately deferred while the existing checks produce a
reproducible two-space result.

### 4.2 Trusted internal calls and validation boundaries

River validates once at an ownership or trust boundary, then trusts the typed
internal contract. Internal services do not add defensive null, range, or
shape checks merely because a value crosses a method call.

| Source | Boundary policy | Interior policy |
| --- | --- | --- |
| Public embedded API and JDBC | Validate nullability, ranges, state, sizes, and lifecycle | Pass typed validated values without repeated checks |
| Native client protocol and SQL text | Authenticate, frame-limit, parse, validate counts/lengths/encodings before allocation | Trust decoded bounded command objects |
| Configuration, admin commands, and migration input | Validate type, range, permissions, paths, and resource effects | Trust immutable normalized configuration |
| Persisted pages, WAL, manifests, and backups | Validate identity, version, length, checksum, and structural bounds when read | Trust a validated view only for its declared lifetime |
| Replica messages | Authenticate and validate framing, epoch/term, identity, bounds, and protocol state | Trust the accepted decoded message inside the state-machine step |
| In-process River module call | Enforce its declared typed contract at the caller/constructor | No redundant null or range checks in the callee hot path |

Disk and replica data are boundary inputs even though River originally produced
them: storage can tear or corrupt, nodes can restart, and versions can differ.
This does not justify checking the same fields at every layer. Decode or admit
once into a type whose construction proves the required invariants.

Assertions may document internal invariants in tests and diagnostic builds.
They are not the normal external validation path and correctness must not depend
on assertions being enabled.

### 4.3 Low coupling and low ceremony

- Project dependencies use `implementation` by default. An `api` dependency is
  exceptional and requires a public-contract reason plus a compile-visibility
  test.
- The architecture dependency graph is a maximum allowlist. Permitted edges are
  not mandatory edges; source declares only what it currently uses.
- Empty future modules do not receive dependencies merely to mirror a plan.
- Prefer a concrete implementation until an interface protects a real
  ownership boundary or a second implementation/test provider exists.
- Keep changes local and reversible. Do not introduce registries, factories,
  adapters, compatibility layers, or extension points for hypothetical needs.
- Require an ADR for durable formats, public contracts, cross-module authority,
  or hard-to-reverse state-machine decisions. Local implementation choices need
  focused tests, not governance ceremony.
- Before River V1.0, internal and durable formats may change directly when the
  result is cleaner. Self-compatibility and migration machinery begin only when
  the project explicitly freezes a release format.

## 5. Status, diagnostics, and exception policy

### 5.1 Separate control flow from diagnostics

Expected failure is returned as a stable status; diagnostic emission explains
an event to operators. A log entry is never the only indication to a caller
that an operation failed, and a status is not automatically logged at every
layer.

The core model is:

```text
StatusCode        stable machine-readable outcome
StatusDetail      optional bounded detail in caller-owned/reused storage
DiagnosticEvent  stable event ID plus fixed typed fields
Severity          DEBUG | INFO | WARN | ERROR | FATAL
DiagnosticSink    allocation-free/no-op-capable event consumer
```

Recommended status families include `OK`, `RETRY`, `CANCELLED`,
`INVALID_EXTERNAL_INPUT`, `CONFLICT`, `RESOURCE_EXHAUSTED`, `TIMEOUT`,
`IO_FAILURE`, `CORRUPTION`, and `INVARIANT_BROKEN`. More specific stable codes
live within these families and map to SQLSTATE at a public SQL boundary.

Hot-path APIs return a singleton enum or compact integer code and place any
result into caller-owned storage. They do not create an `Outcome<T>`, exception,
formatted string, varargs array, map, or captured lambda per operation. At
coarser public/control-plane boundaries, immutable result objects are acceptable
when profiles show they are immaterial.

### 5.2 Diagnostic rules

- Events use stable IDs and fixed typed fields; message templates are registered
  outside the hot path.
- Disabled diagnostics are allocation-free and reduce to a predictable level
  check.
- Do not eagerly format strings, allocate varargs, attach arbitrary maps, or
  include SQL literals and secrets.
- Log once at the layer that can add operational meaning or make the final
  boundary decision. Propagating a status does not emit another error.
- `DEBUG` describes detailed diagnostic state; `INFO` records meaningful normal
  lifecycle events; `WARN` records degraded but continuing behavior; `ERROR`
  records a failed operation or component requiring attention; `FATAL` records
  that the database/process cannot safely continue.
- Metrics cover repeated conditions. Logs do not become high-volume counters.
- Audit events use their own durable security path, not the best-effort
  diagnostic sink.

A `FATAL` event is followed by an explicit state transition that fences new
work, preserves diagnosable state where safe, and performs controlled shutdown
or recovery. Severity alone does not terminate a process.

### 5.3 Exception boundary

Exceptions are not used for expected engine control flow, retries, validation
failure, lock conflict, cancellation, resource exhaustion, I/O status, or SQL
errors. In particular, kernel loops must not pay for stack traces and exception
objects during foreseeable failures.

Exceptions remain permitted only at narrow boundaries:

- JDBC requires `SQLException`; the JDBC adapter creates it from a returned
  status only when crossing that API.
- A Java library may throw, such as `FileChannel`; the River adapter catches it
  at that boundary and converts it to a status without leaking it through the
  kernel.
- Startup, tooling, and other cold control-plane code may use exceptions when
  doing so makes the code materially clearer and the exception is not an
  expected high-rate outcome.
- JVM `Error` conditions are not broadly caught. An invariant breach is reported
  and fenced if safe; tests should fail immediately.

`null` is not an error carrier. Absence is encoded explicitly, and a valid
status never relies on a diagnostic having been consumed.

## 6. Low-GC objective

### 6.1 Performance zones

River uses different allocation expectations for different paths:

| Zone | Objective | Examples |
| --- | --- | --- |
| Kernel data plane | Zero steady-state allocation after warm-up, or a specifically approved fixed amortized budget | WAL reserve/encode/publish, consensus message step, page lookup/latch, lock-table common path, vector inner loops, queue operations |
| SQL batch plane | No per-row allocation; bounded allocation per statement or batch under a query memory budget | Parsing/planning, vectors, hash tables, sorts, result batches |
| Control plane | Clarity first, while remaining bounded | Startup, DDL metadata construction, admin, backup planning, diagnostics rendering |

Zero allocation is not claimed globally. SQL parsing, plan construction, rare
error reporting, and public API objects will allocate. The objective is to keep
allocation frequency proportional to statements/batches or bounded lifecycle
events, never rows, WAL records, locks, messages, or queue entries on designated
hot paths.

### 6.2 Memory architecture

- Use startup-sized or bounded arenas/rings for WAL, replication messages, I/O
  requests, lock entries, recovery descriptors, and diagnostic events.
- Prefer primitive IDs, arrays, bitsets, and specialized collections in kernel
  structures. Avoid boxing and iterator/stream/lambda allocation in hot loops.
- Reuse vectors and batches under explicit ownership. Query memory is reserved
  through the governor and spilled or rejected when its budget is exhausted.
- Keep durable/wire/page encoding independent from Java object serialization.
- Pool only objects with clear exclusive ownership and measurable reuse.
  General-purpose object pools often retain memory and obscure lifetimes.
- Do not retain a large backing buffer through a small slice beyond the owning
  operation.
- Do not select a garbage collector to compensate for avoidable allocation.
  Phase 0 benchmarks supported collectors after the allocation architecture is
  in place.

## 7. Zero-copy and copy-budget objective

Zero-copy is a scoped optimization, not a slogan. Every path documents buffer
owner, lifetime, mutability, hand-off, reclamation, and the exact copies that
remain.

### 7.1 Target paths

- Decode bounded protocol fields as views over an owned receive buffer where
  the command completes before that buffer is reused.
- Encode WAL directly into a reserved journal-ring region; publish only after
  the record is complete and checksummed.
- Feed disk and replication writers from immutable published journal regions
  using gathering/vector I/O where the Java and OS adapters preserve lifetime.
- Apply journal records from validated views without materializing an object per
  record.
- Execute scans and expressions over vectors with selection vectors rather than
  copying rows.
- Stream result batches through explicit ownership transfer or bounded retained
  references.

### 7.2 Copies that are acceptable

A copy is correct when it creates a required lifetime or consistency boundary:

- stabilizing a dirty page image before asynchronous writeback;
- retaining a request/result beyond receive/vector-buffer reuse;
- TLS, compression, checksum, or platform I/O constraints that require another
  representation;
- detaching a public API value from mutable engine memory;
- compacting long-lived state so it does not retain an oversized arena.

The performance reviewer asks whether a copy can be removed, but the
architecture steward rejects unsafe aliasing, unbounded pinning, or pervasive
reference counting merely to claim zero-copy. One explicit bounded copy is
often cheaper and safer than extending buffer ownership across subsystems.

### 7.3 Initial copy budgets

Phase 0 measures and freezes a budget for each representative flow:

| Flow | Initial design target |
| --- | --- |
| Transaction to WAL ring | Encode directly into one reservation; no intermediate record object or payload copy |
| WAL to disk/network | Reuse published immutable regions; allow adapter/TLS copies only when measured and documented |
| Page flush | At most one stable page-image copy before asynchronous I/O for the in-place engine |
| Heap/index scan | No tuple payload copy until an operator needs a new representation or a result crosses its ownership boundary |
| Operator pipeline | Vector/batch hand-off; no per-row object materialization |
| Result to JDBC | Batch/vector internally; allocate only the public objects JDBC semantics actually require |

## 8. Technical architecture consequences

### 8.1 Data-plane and control-plane split

The data plane consists of bounded, ownership-aware components: journal rings,
buffer frames, lock tables, transaction status, consensus windows, I/O request
rings, vectors, and queues. Its APIs use typed IDs, views, reservations, status
codes, and caller-owned result storage.

The control plane constructs schemas, plans, configuration, membership changes,
backup jobs, admin operations, and rendered diagnostics. It may use ordinary
immutable objects where boundedness is maintained. Control-plane work cannot
block or exhaust the reserved resources required for journal, recovery,
checkpoint, and consensus progress.

### 8.2 Ownership API pattern

An asynchronous or retained API must expose one of these operations explicitly:

- `borrow`: callee may use only during the call;
- `transfer`: ownership moves and the previous owner must stop using it;
- `retain/release`: permitted only at coarse batch/frame granularity with a hard
  retention cap;
- `copy`: creates a new independently owned lifetime.

Names are illustrative, not frozen Java signatures. APIs that accept a buffer
without declaring one of these lifetime models fail architecture review.

### 8.3 Status-aware state machines

Storage, commit, recovery, replication, and protocol code should be explicit
state machines whose transitions return statuses. Waiting is represented by a
bounded pending operation/ticket, not an exception or an unbounded future
chain. Fatal statuses fence the component; retryable statuses carry enough
stable identity for idempotent retry.

### 8.4 Observability architecture

`river-observability-api` owns the dependency-light `DiagnosticSink`, metrics,
and structured event types. Producers publish into a bounded ring or no-op
sink. The backend performs formatting, sampling, rate limiting, and export off
the kernel path. Queue overflow increments a counter and follows a declared
drop/coalesce policy; it never blocks correctness-critical progress. Durable
audit and fatal-state metadata have separate explicit guarantees.

## 9. Performance and correctness evidence

The companion
[River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)
defines the exact toolchain, runner protocol, generated and external datasets,
statistical regression rules, automation cadence, and reviewer evidence packet.

### 9.1 Required measurement

For every designated hot path, CI or the performance suite records:

- allocation bytes and objects per operation after warm-up;
- copied bytes and copy count at River-owned boundaries;
- p50, p99, and p99.9 latency plus throughput;
- queue/ring occupancy and backpressure behavior;
- CPU cycles/profile, cache misses where tooling permits, and lock contention;
- GC pause/time/allocation rate during representative sustained load.

Microbenchmarks establish mechanism cost; end-to-end durable SQL and failover
workloads decide suitability. A zero-allocation microbenchmark does not excuse
extra disk, network, synchronization, or tail-latency cost.

### 9.2 Required tests

- Tests inject every returned non-OK status and prove it is propagated, mapped,
  and logged no more than intended.
- Boundary fuzzing covers malformed frames, SQL, sizes, encodings, migration
  values, durable corruption, and replica protocol state.
- Trusted internal-contract tests construct valid typed inputs; they do not
  reward redundant defensive checks.
- Ownership tests detect use-after-release, double release, mutation after
  publish, ring overwrite, leaked pin, and slow-consumer retention.
- Allocation tests fail designated kernel benchmarks on a steady-state
  allocation regression beyond the accepted budget.
- Copy-count instrumentation tests the copy budgets in Section 7.3.
- Crash, race, isolation-history, and deterministic simulation gates remain
  authoritative even when an optimization is faster.

## 10. PR checklist

Every PR answers only the applicable questions, but none may be silently
ignored:

- What is the authoritative state and invariant?
- Which inputs are external/untrusted, where are they validated, and which
  internal values are deliberately trusted afterward?
- What status codes can be returned, who acts on them, and where is the one
  operationally meaningful diagnostic emitted?
- Can any expected path throw or allocate an exception?
- What owns each buffer/batch/view, and when can it be reused?
- What allocates or copies per row, record, message, transaction, batch, or
  statement?
- What is bounded, and what happens at the bound?
- Which failure, race, recovery, compatibility, and performance evidence was
  run?
- Which reviewer personas are required by Section 2.3?

## 11. Adoption plan

### Phase E0: apply the accepted working agreement

- Amend this accepted charter only through an explicit project decision.
- Select and pin the two-space style and static-analysis configuration.
- Add dependency-direction and tab/format checks to CI.
- Write the status-code registry rules, SQLSTATE mapping, diagnostic event
  registry, redaction policy, and fatal-state transition ADR.
- Define the ownership annotations/test hooks used in diagnostic builds.
- Establish benchmark hardware, workloads, and allocation/copy measurement.

### Phase E1: build the primitives before kernel expansion

- Implement `StatusCode`, bounded status detail, and boundary mappers.
- Implement allocation-free `DiagnosticSink` and no-op sink.
- Implement caller-owned buffers/views/reservations and debug ownership checks.
- Add bounded ring/queue primitives and test overflow behavior.
- Prove the patterns in a WAL reservation/encode/publish prototype and one
  vector scan prototype.

### Phase E2: enforce per vertical slice

- Apply the persona/review matrix to each delivery-plan phase.
- Mark designated zero-allocation paths in benchmark metadata.
- Record copy budgets alongside ownership documentation.
- Reject optimizations that weaken crash, isolation, SQL, or durability
  semantics.
- Revisit budgets from production-like profiles rather than loosening them in
  response to unexplained regressions.

## 12. Decisions to carry into the main plan

1. Two-space indentation and no tabs are mandatory and tool-enforced.
2. Validate external, persisted, and replica input once at its boundary; trust
   valid typed in-process inputs without redundant null/range checks.
3. Use stable returned statuses for expected failure and a separate structured
   diagnostic severity abstraction.
4. Do not use exceptions on engine hot paths; adapt to JDBC and throwing Java
   APIs only at their boundaries.
5. Target zero steady-state allocation in bounded kernel paths and no per-row
   allocation in SQL execution.
6. Treat zero-copy as an ownership-and-copy-budget discipline, accepting a
   bounded copy when it establishes a necessary lifetime boundary.
7. Give architecture, correctness, performance, relational, boundary/security,
   and operations reviewers explicit blocking scopes.
8. Require measurements and fault evidence rather than accepting performance or
   resilience claims by inspection.
9. Use targeted daemon-backed Gradle builds for iteration; reserve cold clean
   and reproducibility gates for integration.
10. Keep coupling minimal: `implementation` by default, explicit rare `api`
    edges, and permitted dependencies treated as a maximum allowlist.
11. Prefer low ceremony and direct pre-V1 evolution over speculative
    abstractions or compatibility layers.
