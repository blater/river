# River Large-Class Architecture and Refactoring Plan

<!-- markdownlint-disable MD013 -->

Status: Implemented through the bounded 2026-08-13 integration checkpoint

Audience: River architecture, storage/recovery, relational execution,
boundary, testkit, and performance reviewers

Related plans:

- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [River Indexed-Table Store Ownership Refactoring Plan](river-indexed-table-store-refactoring-plan.md)
- [River Relational-Database Ownership Refactoring Plan](river-relational-database-refactoring-plan.md)
- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)

## 1. Purpose

`tools/godclasses.txt` identifies the twenty largest non-test Java sources by
file size. Size is only the investigation trigger. It does not establish that
a class has confused responsibilities or that extracting classes will improve
the architecture.

This plan evaluates every listed source using six questions:

1. Does the module hide substantial implementation behind a smaller stable
   interface, or is its interface almost a mirror of its implementation?
2. Does understanding one invariant require bouncing through several owners?
3. Do two real callers or adapters exercise a seam, or does an interface exist
   only for a hypothetical future implementation?
4. If the module were deleted, what current product behavior, test leverage,
   or operational evidence would disappear?
5. Are tests exercising the owning interface and its call ordering, or only
   extracted helpers while lifecycle bugs remain untested?
6. Will a proposed extraction move behavior together with its state and
   lifetime, or merely spread a shallow call chain across more files?

The objective is not uniformly smaller files. It is deeper modules, fewer
leaked invariants, stronger locality, and less infrastructure without a named
consumer.

## 2. Authorization and deliverables

Project-owner approval of this plan authorizes every production, refactor,
audit, conditional merge, and deletion slice described here, including Q09.
The characterization, evidence, review, and decision checkpoints remain
completion conditions; they are not additional permission gates. A conditional
outcome proceeds when its recorded criteria select it, without returning for
separate project-owner approval.

| Deliverable | Status | Integrator |
| --- | --- | --- |
| GCR01 protocol header admission | Completed 2026-08-13; literal wire fixtures, transports, corruption, and allocation gates pass | Boundary/operations engineer as single integrator |
| GCR02 K16 directionality checkpoint | Completed 2026-08-13; exact compiled graph and focused recovery/allocation gates pass | Storage/recovery engineer as single integrator |
| Q09 relational database ownership | Schema gate and create-table admission slices completed 2026-08-13; later Q09 service slices remain independently consumer-triggered | Relational execution engineer as single integrator |
| GCR03 binder/runtime repair | Nested and root binding slices completed 2026-08-13; compiled invocation and allocation gates pass | Relational execution engineer as single integrator |
| GCR04 speculative-module deletion audit and evidence-selected deletion | Atomic-install and unused journal-provider loops deleted 2026-08-13; concrete owner evidence retained | Lead integrator |
| GCR05 consumer-triggered deepening | Documents and race-free atomic publisher completed 2026-08-13; depth checkpoint stops further splitting | Benchmark harness owner as single integrator |
| GCR06 `SqlCommand` lifecycle baseline | Completed 2026-08-13; deeper text-handle authority deferred after cost/benefit review | Relational execution engineer as single integrator |

K16 remains completed as previously recorded; GCR02 is a new corrective
directionality deliverable and does not silently change K16's completion
record. Deliverable identifiers coordinate ownership, sequencing, and evidence;
they do not create per-slice approval ceremony.

Related Accepted contracts and ADRs remain binding. Related Proposed or Planned
delivery documents are coordination baselines, not immutable architectural
constraints. When characterization shows that a cleaner authoritative
ownership graph requires a different slice boundary, order, or internal API,
the lead integrator updates the affected plans together. Do not add an adapter,
forwarding layer, duplicate owner, or unnatural sequence solely to preserve an
unimplemented plan boundary. Durable/public compatibility promises,
transaction-visibility rules, and correctness invariants may be reopened only
through their normal explicit change process.

## 3. Decisions that apply to the whole inventory

### 3.1 The public or owning interface remains the primary test surface

Component tests may help diagnose a durable codec or state holder, but they do
not replace tests through the operation that owns validation, mutation,
cleanup, publication, and status precedence. In particular:

- storage tests observe insert, commit, force, reopen, recovery, vacuum, and
  checkpoint behavior through `IndexedTable` or the owning aggregate;
- SQL tests parse, bind, execute, iterate, and close through `SqlParser` and
  `SqlSession`, including failure cleanup;
- protocol tests exercise exact frames through `ProtocolFrameCodec` and both
  transports; and
- artifact and owner fault-path tests retain their public operation as the test
  surface rather than testing only document or transition helpers.

### 3.2 New collaborators are concrete and package-private by default

An extraction is not automatically an interface. Codecs, workspaces, bounded
state holders, and lifecycle coordinators in this plan begin as concrete local
modules. An interface is justified only by an existing ownership boundary or
two implementations with materially different mechanics.

Current interfaces pass that test only when both sides are real. For example:

- `DurableDirectory` has one production NIO implementation and an owner-local
  test model exercised only through real integration paths; and
- JDBC's interfaces are imposed public boundaries with several concrete River
  result-set adapters.

GCR04a and GCR04b applied this rule by deleting unused atomic-install and
journal-provider abstractions whose implementations and contracts tested only
their own fakes. Concrete control stores, DurableDirectory, and LocalWal remain
the evidence owners; shared seams wait for two production implementations.

### 3.3 Move vertical behavior, not pure helpers

Do not extract comparison, validation, encoding, or transition functions only
to unit-test them. Move state, behavior, bounds, ownership, and cleanup as one
unit. A useful extraction should let the old owner delete fields and an entire
operation family. If deleting the new class would merely paste one-line
forwarders back into its caller, the proposed module is too shallow.

### 3.4 Preserve bounded and allocation-conscious ownership

Long-lived collaborators own retained reusable buffers, arrays, cursors, and
result carriers. Typed synchronous borrowing is permitted when the owner,
mutability, call-bounded lifetime, and reuse point are explicit and the callee
retains no reference after return. Untyped shared context/workspace objects and
cross-call borrowing remain prohibited. SQL execution and storage retain their
warmed allocation and copy budgets throughout the moves.

### 3.5 Do not use this plan to reopen completed work without new evidence

K16 and U00 completed on 2026-08-12. This audit found concrete residual seams,
but their deliberate aggregates remain the baseline. Follow-up work first
characterizes the residual coupling and proves a deletion or locality payoff;
it does not immediately replace K16 with another storage rewrite or U00 with a
generic operator framework.

## 4. Inventory disposition

| Class | Disposition | Value | Principal reason |
| --- | --- | --- | --- |
| `RelationalSession` | Reassess after Q09g | Very high, conditional | Q09 must first remove facade callbacks and database-owned concerns; only the residual session shape can justify new owners. |
| `RelationalDatabase` | Execute accepted Q09 flexibly | Very high | Q09's initial ownership baseline addresses schema admission, sequences, DDL, jobs, dependencies, and cleanup. |
| `SqlParser` | Deepen with `SqlCommand`/bound-contract work | High | Simple statement grammar and nested-query source discovery share mutable side channels and two parsing passes, but splitting first would spread the mutable carrier leak. |
| `CatalogRecord` | Split by durable record family | High | Unrelated durable formats and semantic construction share one codec and sparse corruption coverage. |
| `ProtocolFrameCodec` | Deepen and seal leaked header layout | High | Two real transports duplicate knowledge of frame-header offsets. |
| `BenchmarkArtifactWriter` | Separate document preparation from atomic publication | High | Format creation and destructive filesystem ownership/recovery obscure each other. |
| `SqlQueryExecution` | Repair binding first, then deepen its coarse runtime protocol | High | Setter injection is initialization debt, not reciprocal ownership; moving shared point-query state or adding operators would undo U00 locality. |
| `IndexedTableStore` / `IndexedTableKernel` | Characterize and narrow their reciprocal seam | High, conditional | K16 established intentional durable/kernel owners, but vertical operations still cross the boundary in both directions. |
| `SqlNestedQueryExecution` | Make it runtime-only; retain one execution owner | Medium-high | Binding/name/scope work violates U00's authoritative binder contract; state-holder extraction is conditional on a smaller API. |
| `IndexedTransactionSession` | Deepen the pending-mutation buffer | Medium-high | Parallel write-set arrays, savepoints, overlays, and commit preparation form one missing state module. |
| `LocalWal` | Keep deep; make the forced-batch lifetime an explicit lease | Medium-high | The store must apply immutable zero-copy records before WAL slots can be released. |
| `HotPathBytecodePolicy` | Keep facade; move tests and split private pipeline | Medium | The API is deep, but behavioral fixtures live far away in root Gradle wiring. |
| `SqlCommand` | Keep one bounded carrier and deepen structured access | Medium | A class hierarchy would allocate and fragment one real parser/engine boundary; the current getter surface is shallow. |
| `FaultingDurableDirectory` | Localized to engine tests | Realized deletion value | The bounded model remains beside the three owner integration tests; no production or shared testkit abstraction remains. |
| `PrototypeSmoke` | Evidence-equivalence audit before any deletion | Conditional deletion value | P09 names its prototype evidence as a live gate input; every evidence set needs a surviving producer and schema. |
| Removed speculative atomic-install fake | Deleted in GCR04a | Realized deletion value | Owner-local control protocols use the retained durable-directory seam and fault model. |
| Removed speculative journal-provider fake | Deleted in GCR04b | Realized deletion value | Concrete LocalWal evidence remains authoritative; a shared seam waits for the first real replicated consumer. |
| `AbstractResultSet` | Keep; exclude from god-class signal | None | It is generated-style negative-capability boilerplate over an imposed JDBC interface with several real subclasses. |
| `AbstractDatabaseMetaData` | Keep; exclude from god-class signal | None | It hides 176 unsupported JDBC methods from the real metadata adapter. |

The table contains twenty inventory entries; the store and kernel share one
decision because their architectural issue is their relationship, not either
file in isolation.

## 5. Per-class plans

### 5.1 `HotPathBytecodePolicy`

**Present shape.** The two `violations` overloads hide class-file parsing,
hierarchy resolution from directories/JARs/platform classes, method selection,
bytecode traversal, forbidden-instruction classification, allowance matching,
and stable diagnostics. This is a deep public module. The locality problem is
that much of its behavioral fixture logic lives in the root Gradle task rather
than beside the policy and compiled fixtures.

**Target.** Keep the public facade and value types. Move the behavioral suite
into JUnit Jupiter 5.13.4 tests under `buildSrc/src/test`. Add the JUnit Jupiter
test dependency and launcher to `buildSrc/build.gradle.kts` and configure its
`Test` tasks with `useJUnitPlatform`. Add a named root verification task that
runs the `buildSrc` build's `test` task, and make root `check` depend on that
task; root `check` does not execute `buildSrc:test` automatically. Retain the
existing root fixture task until a normal root verification run proves that
the replacement test executed. If the production source still resists change after the test
move, use concrete package-private `ClassFileRepository` and
`HotMethodAuditor` collaborators; do not introduce resolver or auditor
interfaces solely for testability.

**Slices and proof.**

1. Move existing fixture assertions unchanged and retain one root task wiring
   test. Add directory/JAR equivalence, duplicate/unreadable class, unresolved
   or cyclic hierarchy, and stale/duplicate allowance cases.
2. Move loading, parsing, verification, and hierarchy lookup together.
3. Move instruction traversal, classification, allowance accounting, and
   diagnostic assembly together.
4. Add an explicit root verification dependency on `buildSrc:test`, prove the
   ordinary repository gate runs it, and only then delete the old fixture task.

### 5.2 `AbstractDatabaseMetaData`

**Decision: keep.** Its 176 methods are uniform unsupported defaults required
by `java.sql.DatabaseMetaData`. `RiverDatabaseMetaData` is the real adapter and
overrides the truthful supported subset. Splitting the base by JDBC category
cannot improve ownership under Java single inheritance and would make the
boundary shallower.

Mark the source as generated-style compatibility boilerplate and exclude it
from the large-class architecture report. Add a compatibility test covering a
representative inherited unsupported method and the declared supported
capability matrix. Continue testing catalog metadata through real JDBC
connections. Consider generation only to track future JDK interface changes,
not as an architectural decomposition.

### 5.3 `ProtocolFrameCodec`

**Present shape.** The codec owns header validation, request text/binary
encoding, response fixed fields, metadata and typed row values, UTF-8 rules,
and stable status decoding. That is an appropriate deep wire-boundary module.
The leak is outside it: both `RiverClientConnection` and the server transport
read payload length from the same header offset and share codec maxima. Two
transport adapters make header inspection a real seam.

**Target.** Retain `ProtocolFrameCodec` as the sole public frame facade. Add a
reusable public or nested-public, primitive-only `ProtocolFrameHeader` carrier
because it crosses the protocol/client/server module boundary. Provide
role-specific request and response inspection operations. Before any payload
read, inspection validates magic, version, message type and direction, flags,
request ID, reserved fields, declared payload length, and the role-specific
maximum. Full decode consumes the inspected metadata or invokes exactly the
same validation core; it never implements a second header policy. Package-
private concrete header and response-payload codecs may organize the
implementation. Keep engine
`CommandResult` and `RowResult` out of a new public protocol interface; use two
private retained value adapters or two concrete encode loops only if this
removes the current nullable tagged union without allocation.

**Slices and proof.**

1. Add golden bytes for every request/response kind and a cross-field/type/
   flag corruption matrix, including malformed, truncated, oversized, wrong-
   direction, and incompatible-version early header reads. Preserve distinct
   outcomes for incompatible version, exhausted bound, malformed client
   request, and client-side corrupt peer response.
2. Add malformed-header socket tests through both transports, migrate both to
   role-specific inspection, and remove their local `readInt` helpers and all
   raw offset-24 access.
3. Regroup response encoding while preserving exact bytes and the warmed
   allocation test.
4. Obtain boundary/security review because this is an untrusted durable wire
   format.

### 5.4 `LocalWal`

**Present shape.** Create/open/tail recovery, reservations, append, force,
read, frontiers, rotation, quorum behavior, and copy/accounting all contribute
to one WAL state machine. Splitting codec, recovery, and I/O into independently
acting services would make durability shallower. The weak point is the
borrowed forced-batch protocol: callers can force, inspect records, and release
slots in the correct order only by understanding `LocalWal` internals.

**Target: explicit forced-batch lease.** Keep one concrete `LocalWal`.
`LocalWal` owns local force, quorum replication, and the forced-batch storage
lifetime. A successful force populates one reusable caller-owned
`LocalWalForcedBatch` lease containing an owner token, WAL generation token,
and record count. Indexed reads require that lease and return borrowed
immutable views valid only until its release. `IndexedTableStore` remains the
owner of the cross-resource force -> apply/publish -> release ordering and
releases the lease exactly once from its terminal cleanup path.

Stale reads, post-release reads, double release, a lease from another owner or
generation, and close with an outstanding lease return explicit non-throwing
statuses. Fencing invalidates future operations but does not silently recycle
leased slots. No callback, lambda, iterator, per-record wrapper, batch copy, or
WAL dependency on engine types is introduced. `DurableWalQuorum` remains the
concrete replication policy. Cross-resource checkpoint ordering remains in
`EmbeddedCheckpoint`.

**Slices and proof.** Characterize reserve/cancel/append/force/lease/read/
release/close and rotation/adoption precedence before changing the API. The
required outcome matrix covers local force failure, partial quorum failure,
apply failure after successful force, release failure and original-versus-
cleanup status precedence, stale/double/post-release access, and close or
fencing with an outstanding lease. Migrate group publication and vacuum to one
terminal release path. Preserve zero-copy record views, record bytes,
torn-tail recovery, quorum history, and `LocalWalAllocationTest`.

### 5.5 `FaultingDurableDirectory`

**Decision: localize to the consuming engine tests.** The bounded model remains
one cohesive test helper for database-control, checkpoint-control, and indexed
group-commit fault evidence. Its fault identities and controller live in the
same test source set. The production platform has no injector, registry, hook,
or fake-provider contract, and the unused shared testkit module is deleted.

### 5.6 `AbstractResultSet`

**Decision: keep.** The file is uniform unsupported JDBC boilerplate, not a
confused implementation. Six concrete River result sets make the imposed
`ResultSet` seam real. Deleting the base would copy roughly 190 methods into
each adapter, while splitting it cannot help single inheritance.

Mark and exclude it from architecture size reports. Add a small compatibility
test that supported methods are overridden and representative inherited
methods throw `SQLFeatureNotSupportedException`. Generate it from the active
JDK interface if maintenance warrants it; do not decompose it.

### 5.7 `IndexedTransactionSession`

**Present shape.** Transaction and statement snapshots, locks, pending
mutations, read-your-writes, scan ownership, savepoints, direct/group commit,
and automatic vacuum admission coexist. The facade is useful, but its parallel
pending arrays and every operation over them are one missing deep module.

**Target.** Keep the session facade. Introduce a concrete owned
`PendingMutationBuffer` containing operation/key/previous-row/row-length/row
storage, capacity, latest-key lookup, overlay merge, savepoint truncation,
compaction, preflight, and append access. Shape direct and group commit around
this owner rather than exposing arrays. Initially preserve its primitive
structure-of-arrays layout and fixed-stride direct row arena. Expose one coarse
bounded batch operation downward; expose no mutation objects, slices,
iterators, callbacks, or raw arrays. Neither `PendingMutationBuffer` nor
`IndexedTransactionSession` acquires a store back-reference. Move the threshold/pressure decision
into `IndexedVacuum.maintainBeforeBegin`; keep lock policy local until a second
consumer or a demonstrable state reduction justifies a concrete `LockSet`.

**Slices and proof.** The first slice moves algorithms unchanged. Characterize
every capacity boundary and savepoint/scan interaction, then benchmark latest-
key lookup, overlay, savepoint truncation, and compaction at one, typical, and
maximum capacity. Only a separate measured optimization may replace bounded
scans or quadratic compaction, and it uses bounded primitive storage rather
than a boxed map. Migrate group commit, then vacuum admission. Cover partial
multi-mutation rollback, failed commit/abort cleanup, grouped fallback, and
isolation/deadlock/phantom behavior through `IndexedTransactionSessionTest`.
Preserve warmed transaction allocation and exact copy evidence through
`IndexedTableAllocationTest`.

### 5.8 `BenchmarkArtifactWriter`

**Completed shape.** Workload preparation has two real adapters: buffered and
streaming. `BenchmarkArtifactDocuments` constructs and validates JSON/TSV
documents and owns bounded two-pass content preparation.
`AtomicArtifactPublisher` owns path confinement, the run claim, staging,
persisted verification, cleanup, and atomic installation. The public writer is
the thin operation coordinator between these two concrete owners.

The completed design keeps `BenchmarkArtifactWriter` as the public operation
and test surface with these package-private owners:

- `BenchmarkArtifactDocuments` prepares an immutable publication plan with
  metadata, digests, paths, and a bounded synchronous content producer. It
  validates the manifest/result/sample schema without materializing the
  existing streaming workload bytes;
- `AtomicArtifactPublisher` exclusively owns path confinement, the no-clobber
  run claim, markers, attempt-local cleanup, persisted verification, and atomic
  move. Its fixed `.pending/` staging directory is a direct child of the claim;
  it never automatically reaps an existing run path; and
- the two content adapters remain in `BenchmarkArtifactDocuments`; the depth
  checkpoint found no substantial residual owner that would justify a
  `BenchmarkWorkloadPreparer`.

**Slices and proof.** GCR05a moved document construction and bounded content
preparation without changing published bytes. GCR05b moved the filesystem
operation as one indivisible unit: buffered input remains fully prepared before
claiming, while streaming input performs only path/target advice before the
atomic claim and invokes both emitter passes synchronously under that claim.
Prove competing streaming writers (including zero loser emissions), caught
claim/stage cleanup and retry, atomic-move unavailability, cleanup-failure
precedence, unchanged foreign/symbolic-link claims, and preserved incomplete
crash claims through the writer. Retain failure injection at the owning
boundary and never scatter cleanup across helper modules.

### 5.9 `PrototypeSmoke`

**Decision: complete an evidence-equivalence audit before refactoring or
deletion.** The class combines
seven unrelated developer measurements with warmup/timing/allocation,
percentiles, JSON, environment/git/JMX/JOL capture, secret redaction, and
process execution. Its output declares itself developer-only, no end-to-end
test calls `run`, component tests cover the mechanisms, and JMH overlaps the
measurement role.

P09 explicitly names the page-size/page-I/O, FPI-versus-double-write,
WAL-reservation, persistent-version-store, and vector-scan evidence sets as
inputs to G0 and their owning kernel deliverables. Before any deletion, perform
an evidence-equivalence audit naming the surviving producer, schema, numeric
fields, and consuming milestone for every still-live set. Delete
`PrototypeSmoke`, its Gradle task, and README instructions only when those
consumers are amended or an evidence-equivalent producer is verified. If it
remains, retain local scenario methods rather than seven strategy interfaces,
extract only a concrete evidence/environment writer, and add an end-to-end
temporary-directory schema/content/failure test. Characterize the MPSC sample
mapping before any move; its current orchestration copies one sample slot
twice.

### 5.10 `SqlCommand`

**Present shape.** This is a caller-owned, fixed-capacity mutable union across
DDL, DML, query, and transaction syntax. It is the real allocation-free
`river-sql` to `river-engine` boundary, but more than one hundred getters make
the interface resemble its parallel-array storage.

**Bounded decision.** Keep one command carrier; do not create a command class
hierarchy. GCR06a-1 centralized availability publication in the O(1)
`finish()` transition and made every non-null parser/query destination reset
before failure. This closes stale-command dispatch without changing steady-
state allocation.

The proposed globally unique text-handle authority was rejected at the depth
checkpoint: its atomic coordination and cross-command capability machinery
were not proportional to a trusted-internal lifetime misuse. The command keeps
its existing 64 KiB text storage and primitive handle format. Any later move
to an owned text arena or reusable structured views must be selected by a real
consumer and use command-local lifetime fencing without adding another buffer,
global token, or compatibility adapter. The longer-term candidate remains:
an owned concrete text arena and reusable structured views for
columns, predicates, insert cells, updates, and schema clauses. Parser mutation
methods stay package-private. Views are retained/preallocated concrete
accessors over the existing arrays and one text arena. Acquiring or traversing
them allocates nothing, creates no slices, iterators, or element objects, and
duplicates no text. Every view checks a command generation and is invalid
after reset or reuse. `finish` returns `StatusCode`, publishes availability
only after all cross-field invariants pass, and leaves the command unavailable
but resettable on failure. Migrate binder and dispatcher to cohesive access,
then delete legacy getters and cached flags that become derivable.

**Slices and proof.** The completed lifecycle slice covers reset after success
and failure across incompatible command kinds, compiler-boundary failures,
alias-safe view compilation, and exact capacity edges. Full parser, nested SQL,
session, allocation, and hot-bytecode checks pass. Stale text handles and
structured family views remain a separate, unselected slice; if selected,
move one complete consumer family at a time and preserve the warmed parser
reuse budget without per-command or per-field allocation.

### 5.11 `CatalogRecord`

**Present shape.** Sequence, user-sequence, identity, view, table/dropping
table, and index records share one class despite independent magic, version,
layout, and lifecycle. Table decode also performs semantic construction and
binds a `TableDefinition` to `RelationalDatabase`. Scan decoding repeats
prefix/copy work. Direct corruption and golden-byte coverage is sparse.

**Target.** Use one concrete deep codec per durable record family:

- ID/user/identity sequence codecs grouped only where their lifecycle and
  caller are shared;
- `CatalogViewCodec`;
- `CatalogTableCodec`, retaining dropping-table as the same format's state
  variant; and
- `CatalogIndexCodec`.

Each codec owns magic, version, offsets, length, encode, scan discrimination,
decode, structural validation, and result reset. It populates caller- or
service-owned reusable carriers and never attaches a `RelationalDatabase`,
session, or schema gate. After Q09b, the owning relational catalog access path
attaches the `RelationalSchemaGate` identity/version token; this does not
require the conditional post-Q09 `RelationalCatalogAccess` extraction. Add a
tiny kind decoder only if
mixed catalog scans need it. A mixed scan treats "not this family" as a
non-corruption mismatch. A direct keyed lookup treats the wrong family, name,
or reference as `CORRUPTION` unless an already-characterized public collision
deliberately maps to `CONFLICT`. Do not add codec interfaces.

**Slices and proof.** Before moving bytes, add round-trip and golden-byte
fixtures plus a corruption matrix for every record family's magic, version,
length, ranges, UTF-8, masks, references, defaults, and duplicate indexes,
including exact `CONFLICT` versus `CORRUPTION`. Move one family and its callers
at a time. "Decode once" means one bounded copy into exclusively owned
scratch, kind inspection, and family decode over the same image. Mutable
scratch belongs to one serialized service or session and is never shared
concurrently. Eliminate double copy/classification and delete `CatalogRecord`
only when references reach zero. Durable-format and relational-semantics
reviews are mandatory.

The first family slice completed on 2026-08-13: `CatalogViewCodec` now owns the
view magic, version, header, encode, keyed decode, scan discrimination, and
structural validation. Production lookup and scan callers use it directly;
the scan decoder reuses its single bounded scratch image rather than copying
the row again. A fixed version-one byte image and corruption/status matrix pin
magic, version, length, base-table range, keyed-name, and scanned-name
semantics through the codec, while real relational and SQL view tests cover
the consuming path. Table, index, and sequence families remain deliberately
unchanged for later independent slices.

The sequence family followed in the same checkpoint. `CatalogSequenceCodec`
owns allocation, user-sequence, and identity-sequence magic/version/layouts
plus their two reusable primitive result carriers. Production create,
allocate, reopen, and identity callers now use the family directly. Fixed
version-one images, range/name/type corruption cases, and real sequence and
identity tests pin the move. Table and index families remain unchanged.

`CatalogIndexCodec` completes the independent-family pass: it owns the
version-three index record, scan/keyed decode status boundary, and reusable
result carrier. Golden bytes, corruption/status tests, and real create,
rename, and drop-index paths passed. `CatalogRecord` now contains only the
table/dropping-table format and can be simplified and renamed as that one
durable family in a later table-focused slice.

The table-only remainder was then simplified in place. Complete per-check and
per-index encoding helpers removed its residual complex methods; PMD now
reports no cognitive-complexity or NCSS violation. The remaining class is one
durable table/dropping-table format owner, so another source split would divide
one byte layout without creating an independent consumer or lifecycle.

### 5.12 Removed speculative atomic-install slice

**Decision: completed by GCR04a on 2026-08-13.** The audit confirmed zero
production Java consumers. The unused interface, lifecycle carriers and state
machine, duplicate filesystem fake, contract helpers, and their self-referential
tests were deleted together. No replacement helper or interface was added.

`DatabaseControlStore` and `CheckpointControlStore` remain the concrete owners
of their synchronous create or replace protocols over `DurableDirectory`.
Owner-specific tests now inject short writes, file-force failures,
rename/replace crashes, directory-force crashes, and stale-temporary recovery,
then assert exact status precedence, result-carrier reset, durable old-versus-new
authority, and temporary-file survival. The retained
`FaultingDurableDirectory` continues to own namespace/content crash modeling
and its common contract suite; NIO remains the production adapter and
qualification path. This is the evidence-equivalent replacement for the
deleted speculative tests, without recreating a generic publication lifecycle.

### 5.13 Removed speculative journal-provider slice

**GCR04b completed.** The unused provider module, its broad fake, reusable
contract harness, and provider-only tests were deleted after a repository-wide
consumer audit found no production import or adapter. Root module/dependency
policy and the god-class inventory were narrowed in the same slice.

Concrete LocalWal append, force, scan, reopen, corruption, and recovery tests
remain the current authority. Frontiers, capability waits, idempotent outcomes,
retention leases, node fencing, and position mappings are not preserved as an
unused framework. R24 must derive only the portions required by both concrete
LocalWal and the first production replicated journal, then prove that seam with
provider-specific crash, concurrency, fencing, retention, and allocation
evidence. No replacement abstraction is introduced before that consumer.

### 5.14 `IndexedTableKernel` and `IndexedTableStore`

**Present shape.** K16 intentionally made `IndexedTableStore` the durable
aggregate and `IndexedTableKernel` the sole heap/B+tree/MVCC transition owner.
That removed duplicate algorithms and raw buffer leakage. The remaining seam
is reciprocal: a logical operation can travel
`IndexedTable -> Kernel -> Store -> Kernel`; the kernel calls store lifecycle
operations and the store calls roughly eighty kernel methods during commit,
recovery, checkpoint, and vacuum. Tests can still reach `store.kernel()`.

**Required GCR02 target graph.** The first checkpoint has one success state:

```text
IndexedTable
  `- IndexedTableStore
       |- IndexedTableKernel
       |- IndexedPageSet
       `- LocalWal
```

- `IndexedTable` retains and delegates to the store, not the kernel.
- `store.kernel()` disappears from production and tests.
- `IndexedTableKernel` has no `IndexedTableStore` field, constructor argument,
  method parameter, or callback.
- Kernel transitions receive phase-local primitive values and the page owner
  they need.
- The store exclusively owns WAL, operation phase, force, publication,
  recovery, and checkpoint ordering.
- `IndexedTable` remains the transaction-facing synchronized operation-
  admission gate; preserve the current synchronization of force, flush, close,
  metrics, and structural access rather than adding monitors mechanically. No
  nested monitors, callback re-entry, futures, or additional volatile reads
  enter row or page loops.
- `PendingMutationBuffer` and `IndexedTransactionSession` acquire no store
  back-reference.

This graph is required before the recorded depth checkpoint assesses the
remaining kernel API. A later merge is neither predicted nor performed in the
directionality slice. It is selected when evidence shows that the directional
kernel still mirrors the implementation. A selected merge regroups complete
vertical operation families and reduces cross-region
calls; it never mechanically concatenates sources. No new interface or generic
storage engine is introduced.

**Slices and proof.**

1. Record compile-visible dependency rules that forbid kernel references to
   store types and forbid direct table-to-kernel access. Prefer package or
   build-policy enforcement over source-text matching.
2. Characterize compact/page-image equivalence, forced-not-published
   visibility, split/reopen, interrupted vacuum, and every reserve/append/
   force/apply/publish failure phase through `IndexedTable`.
3. Remove production and test `kernel()` access, then make insert, mutation
   batch, group commit, recovery, and vacuum directional one family at a time.
4. Prove the exact graph and dependency rules compile, then record the kernel
   API depth/deletion checkpoint without merging it in the same slice.

This is durable-format, recovery, and allocation-sensitive work. Preserve all
K16 byte fixtures, allocation/copy accounting, and independent correctness and
performance reviews.

The post-directionality simplification is complete. `PendingMutationBuffer`
owns prepared arrays and row storage, the kernel keeps primitive allocation-
free heap/B+tree transitions, and the store keeps WAL/checkpoint/recovery
ordering. Complete entry, validation, apply, scan, split, vacuum, checkpoint,
and publication phases replaced the residual decision trees. PMD reports no
cognitive-complexity or NCSS violation in either class. Their aggregate
GodClass heuristic remains an expected signal for two cohesive state owners;
no forwarding interface was introduced merely to suppress it.

### 5.15 `RelationalSession`

**Present shape.** The session owns transaction registration, statement and
savepoint lifecycle, catalog resolution and scans, schema-change bookkeeping,
logical row CRUD, foreign keys, base-row encoding, secondary-index maintenance,
index lookup/scan, and physical linked-list details. It also calls back into
`RelationalDatabase` for admission and DDL, so ownership is bidirectional.

**Decision: post-Q09g reassessment, not a mandatory decomposition.** Keep
`RelationalSession` as the deep public transaction facade. Q09 first removes
facade callbacks and database-owned concerns. Only the residual session shape
may justify these concrete, package-private candidates:

| Concern | Authoritative owner |
| --- | --- |
| DML atomic ordering and terminal status precedence | `RelationalRowMutation` |
| Foreign-key semantic decision and lookup lifecycle | `RelationalReferentialIntegrity` |
| Physical secondary-entry encoding, chains, lookup, and cursors | `RelationalSecondaryIndexStore` |
| Index build/drop/resume/publication policy | `RelationalIndexLifecycle` |
| Session catalog resolution | `RelationalCatalogAccess` |
| DDL/lifecycle catalog scratch | The owning Q09 service; never shared |

If justified after Q09g, the required direction is:

```text
RelationalSession
  |- RelationalCatalogAccess
  `- RelationalRowMutation
       |- RelationalReferentialIntegrity
       `- RelationalSecondaryIndexStore
```

`RelationalRowMutation` sequences the complete atomic operation but does not
reimplement foreign-key or physical-index semantics.
`RelationalSecondaryIndexStore` neither resolves catalogs nor calls back
through the session or facade. Raw physical row methods become package-private
implementation calls. If post-Q09 row mutation is only a few thin forwarders,
keep it in `RelationalSession`. Keep pending schema operation, mutation
watermark, and terminal status precedence local unless Q09 leaves a materially
smaller cohesive owner.

**Slices and proof.** Complete Q09g, record the residual field/method/call
graph, then make a separate depth decision for each candidate. If approved,
characterize the base row plus every secondary-index mutation as one atomic
operation before moving a complete owner. Add missing chain corruption,
missing head/cycle bounds, text-hash collision, partial multi-index savepoint
rollback, and cursor-close failure tests through public relational/SQL paths.
Preserve nullable/text/non-unique semantics and warmed allocation budgets.

The reassessment selected only boundaries supported by current consumers:
`RelationalReferentialIntegrity`, `RelationalSecondaryIndexStore`, and
`RelationalSequenceService`, beside the schema gate and catalog-dependency
owner. Session deletion no longer calls through the database facade, physical
secondary-index buffers and chains no longer live in the session, and sequence
reservation/cache arrays no longer live in the database. Remaining DML and
DDL sequencing stays with the public session/facade because moving it would be
forwarding ceremony. PMD reports no cognitive-complexity or NCSS violation in
the session, database, or extracted services.

### 5.16 `SqlNestedQueryExecution`

**Present shape.** One U00-owned runtime holds nested binding and scope
validation, scalar/existence/membership semantics, correlated outer-row
lifetime, recursive frames and cursors, membership values/text arenas, and
cleanup. This is appropriately centralized, but parallel arrays and
package-visible self-accessors make the internal state shallow.

**Target.** Retain one concrete nested execution owner contained by
`SqlQueryExecution`. GCR03 uses one authoritative binder-to-runtime pipeline:

1. Capture all query-block syntax.
2. Resolve every block's table identity and lexical scope.
3. Bind projection columns and types, predicate columns, outer-scope depth,
   and correlation topology into bounded statement-owned block state.
4. Bind the root command using that completed topology.
5. Let execution reset, open, evaluate, close, and retry cleanup using resolved
   indices and types only.

`SqlBinder` and `BoundSqlQuery` own steps 2-4. After successful binding,
`SqlNestedQueryExecution` performs no `resolveTable`, `findColumn`, qualifier-
name comparison, or syntax-shape/type resolution. Per-block state is bounded
by `BoundSqlQuery.MAXIMUM_BLOCKS`. Failed binding publishes no executable
block. Reset clears every bound block and its generation. The coordinator no
longer asks execution for correlation flags before root binding.

Execution preserves lazy recursive-state construction. Before implementation,
record eager base, one-level nested, maximum-recursion, and many-concurrent-
session resident/direct-memory budgets, including membership arenas and lazy
recursive text state. Bounded capacity returns `RESOURCE_EXHAUSTED`; an
expected allocation exception is not control flow. After binding moves,
consider concrete membership or frame modules only if they hide bounds,
null/type state, text lifetime, reset, and cleanup behind a materially smaller
API. Do not expose array indexes nearly one for one or introduce strategy
interfaces.

**Slices and proof.** Expand the nested matrix first: correlated scalar,
`EXISTS`, `IN`/`NOT IN`, every admitted depth, cardinality violations, NULL
three-valued logic, VARCHAR lifetime, exhaustion, early failure, and cleanup
retry. Capture all blocks, bind topology and root, remove runtime resolution,
then simplify the execution protocol. Preserve before/after predicate phases,
lazy memory behavior, and the SQL session allocation gate.

GCR03 plus the final simplification completed this target. Binding and name
resolution are absent from runtime, executable generations fence block state,
and scalar/existence/membership/recursive evaluation delegates complete
candidate and result phases. PMD reports no cognitive-complexity or NCSS
violation; lazy recursive allocation and per-row allocation gates remain
green. The runtime remains one owner because its cursors, recursive frames,
membership banks, and cleanup share one statement lifetime.

### 5.17 `RelationalDatabase`

**Decision: execute Q09 as an accepted initial ownership baseline, adapting it
when implementation evidence supports a cleaner slice.** The
[Relational-Database Ownership Refactoring Plan](river-relational-database-refactoring-plan.md)
already maps the facade's embedded lifecycle, admission/schema publication,
sequence cache, catalog DDL, dependency checks, referential integrity, index
and table jobs, physical cleanup, and reusable scratch to concrete owners.

The large-class audit reinforces four Q09 constraints:

1. `RelationalSchemaGate` must eliminate session-to-facade callbacks rather
   than add another layer of forwarding.
2. Explicit-session and autocommit DDL are two real adapters over one catalog
   mutation core; the facade adapter owns transaction framing.
3. Resumable build/drop components own phase state and buffers as whole jobs,
   not pure row helpers.
4. Synchronization moves with authoritative state; the completed facade must
   not remain a global monitor over extracted services.

Q09's `RelationalSessionFactory` and `RelationalDatabaseCommands` are
conditional, not line-count gates. Keep construction and transaction framing
beside their owners if extraction would only produce forwarding methods or
move begin/body/commit-abort failure precedence away from the command. Apply
the same depth test as the stateful services before creating either class.

Use Q09's authority map, behavioral matrix, allocation contract, and exit
criteria as the initial per-class baseline. Before and during implementation,
reconcile its slices with the constraints and conditional depth decisions in
this plan. The relational integrator may re-slice Q09 and residual-session work
together when that produces a smaller vertical change, provided authoritative
ownership, one-way dependencies, behavior, evidence, and WIP limits remain
intact. Proposed class names and slice boundaries are not compatibility
contracts.

The accepted baseline now includes concrete sequence, catalog-dependency, and
referential-integrity owners. The database retains autocommit framing and
resumable index/table lifecycle ordering; extracting those command ladders
would separate begin/body/commit-abort precedence from its owner. PMD reports
no method cognitive-complexity or NCSS violation.

### 5.18 `SqlParser`

**Present shape.** Three public parse operations hide lexical rules, literals,
predicates, all command grammars, nested/derived/scalar/existence/membership
block discovery, source rewriting, and query compilation. The public module is
deep. The confused responsibility is internal: `parseQuery` scans raw text
with mutable finder side channels, creates source views, repeatedly invokes
statement parsing, and then compiles blocks.

**Target.** Keep `SqlParser` as the reusable public facade. Use a concrete
package-private statement parser and query-block parser only after the same
slice gives `SqlCommand` or the bound syntax owner a smaller captured contract
and explicit text lifetime. Otherwise the mutable carrier and shared token/
error context would merely force readers to bounce between files. The
statement owner would contain cursor, lexical rules, command grammar,
predicates, and completion; the query-block owner would contain `EXPLAIN`,
nested topology, source views, and compilation orchestration. Keep the lexer
local to statement parsing; extracting it solely for unit tests would reduce
locality. Organize the large command dispatch into private cohesive
transaction/control, DDL, insert, select, update, and delete regions before
deciding which source boundary is deep. Document parser ownership and
non-reentrancy.

**Slices and proof.** Split the monolithic test source by public behavior while
continuing to call only the public parser. Add reset-after-failure,
parenthesis/depth/complexity, and literals containing keywords/parentheses.
First turn `parseText` into a shallow dispatcher over complete transaction/
control, DDL, insert, select, update, and delete parsing regions, even if they
remain private methods in the same source. Deepen captured command/text
ownership, then extract the query-block pass only if token/error context does
not leak.
Ensure hot-path bytecode policy follows the concrete implementation after the
facade delegates, and run query/view integration plus allocation tests.

The bounded parser pass is complete without a second parser object. Shared
top-level SQL scanning removed the four duplicated nested-source decision
trees; statement dispatch is shallow across transaction and data-command
regions; literal, row, column-constraint, update, membership, comparison, and
sequence-option phases are cohesive helpers. The original `parseText`
cyclomatic 248/NCSS 521 hotspot is now below PMD's cognitive and NCSS
thresholds; the whole parser has no method above cognitive 15 or cyclomatic
14. Parser, lifecycle, integration, and allocation tests pass. Extracting the
shared token cursor would spread mutable token/error context without a second
consumer, so the reusable non-reentrant facade remains one owner.

### 5.19 `SqlQueryExecution`

**Present shape.** U00 deliberately made this the owner of one physical
query's plan, open/next/close state, joins, groups, distinct, sort, projection,
nested filtering, result shape, and cleanup. That aggregate is deep and should
not be replaced by a speculative Volcano/operator hierarchy.
`SqlPointCommandExecutor.attachQueries` is one-way setter injection, not a
reciprocal ownership back-edge. It is mandatory-initialization debt, but it
does not justify moving point-query state.

**Target.** Binding repair in GCR03 precedes query-execution rearrangement.
The coordinator owns parsing, view expansion, authoritative binding, and
transaction framing. `SqlQueryExecution` consumes fully bound state through
coarse `open`, `next`, `close`, and `retryCleanup` operations. Correlation
getters, `refreshPreparedCommand`, and prepared-order setters disappear. Point
execution remains with query execution while it shares projection, predicate,
aggregate, and cleanup lifetimes. Replace setter injection with constructor-
complete or coordinator-owned mandatory initialization without moving this
state.

After that, reassess scan change cost.
If join, grouping/distinct, and sort later change independently, extract one
as a concrete end-to-end state owner only when it hides its plan/resources and
cleanup behind a smaller API. Introduce a common operator interface only after
two concrete operators genuinely substitute at the same runtime point, calls
remain monomorphic or profiling proves effective inlining, and before/after
evidence shows no material CPU or latency regression. Dispatch and row transfer
remain allocation-free. Keep predicate, nested, projection, and terminal
transaction ordering in the physical query owner.

**Slices and proof.** Complete the binder/runtime pipeline, characterize the
wide coordinator/runtime phase calls and point/aggregate sharing, replace
setter injection, and pass U00 ownership/allocation gates. Add early-close and cleanup
retry for join, sort, nested, catalog, and explain/analyze paths, NULL-extended
join predicate ordering, and plan/runtime parity. Extract one vertical
operator only when it deletes its state and lifecycle from query execution;
stop when future changes have become local.

The coarse runtime rearrangement is complete. Setter injection is gone and
constructor-complete ownership is used. Group/distinct/join/order binding is
published before execution, while parsed-scan, materialization, join,
predicate, projection, aggregate, and cleanup paths delegate complete phases.
PMD reports no GodClass warning and no cognitive/NCSS violations.
`beginParsedScan` fell from cyclomatic 163 to 13; the former
`materializeSortedScan` and `nextJoin` NCSS hotspots are below PMD's reporting
thresholds. No generic operator hierarchy was added.

The first bounded query-execution complexity slice completed on 2026-08-13.
`beginParsedScan` is now a command-family dispatcher over scalar, grouped or
distinct, join, and ordinary row-opening protocols; grouped and distinct scans
share one row-source opener, and sort materialization and join advancement use
named allocation-free phases. PMD cyclomatic complexity for
`beginParsedScan` fell from 140 to 13, `nextJoin` is no longer an NCSS hotspot,
and `materializeSortedScan` no longer exceeds the configured cognitive, NPath,
or NCSS thresholds. The change stayed within the existing query owner because
predicate, projection, nested evaluation, resource cleanup, and physical scan
state remain one lifetime; no operator interface or forwarding-only class was
introduced. Focused group, spill, join, EXPLAIN, and allocation tests, the full
engine suite, hot-bytecode checks, and compiled runtime invocation policies
passed.

## 6. Complexity and performance evidence

### 6.1 Method and path complexity

PMD 7.26.0 found the following principal warning signals. NPath is
combinatorial and is not a precise effort estimate or an arbitrary pass/fail
threshold.

| Method | Cyclomatic complexity | PMD NPath |
| --- | ---: | ---: |
| `SqlParser.parseText` | 248 | About 5.1 trillion |
| `SqlQueryExecution.beginParsedScan` | 163 | About 19.0 billion |
| `CatalogRecord.decodeTable` | 85 | About 2.9 billion |
| `ProtocolFrameCodec.decodeResponse` | 44 | 251 |
| `RelationalSession.updateRow` | Class maximum 32; method cognitive 45 | 925 |

Before a slice, record the touched operation-family call graph and PMD
hotspots. A slice does not increase the worst touched hotspot without an
invariant-based explanation. It reduces duplicated status/cleanup ladders and
the named extreme methods, but does not improve metrics by distributing the
same decision tree through forwarding calls.

An explicit state machine names its phases, legal transitions, terminal and
fenced behavior, and material transition tests. Complexity evidence supports
the ownership decision; it does not replace tests through the owning surface.

The completed eight-class pass was remeasured with PMD 7.26.0. Every named
production class now has zero cognitive-complexity and zero NCSS violations at
PMD's standard thresholds. Highest per-method cyclomatic complexity is 22
(`CatalogRecord` structural decode), 17 (`SqlNestedQueryExecution`), 17
(`IndexedTableKernel`), 19 (`RelationalSession`), 14 (`RelationalDatabase`),
14 (`SqlParser`), 16 (`SqlQueryExecution`), and 23 (`IndexedTableStore`). The
remaining aggregate GodClass warnings are retained only for cohesive durable,
transaction, parser, or execution state owners; `SqlQueryExecution` no longer
triggers that heuristic. PMD is a regression signal alongside real-path tests,
not a reason to distribute state through shallow helper facades.

### 6.2 Selective hot-path performance packet

Performance evidence is proportional to what a slice can change:

- a slice that changes dispatch, synchronization, memory layout, copying, I/O,
  or an algorithm on a designated kernel, WAL, transaction, or SQL batch/row
  path records the full packet below;
- a pure ownership move on such a path preserves the existing allocation,
  copy, bytecode, and semantic gates and adds one focused timing or profile
  comparison for the changed call path; and
- a cold control-plane rearrangement records only the relevant compile, test,
  policy, bounded-memory, and ownership evidence.

Reuse a current valid baseline rather than regenerating unrelated measurements
for every slice. The full packet defines one named before/after workload and
reports:

- allocated bytes and objects;
- River-owned copy count and bytes;
- throughput and p50, p99, and p99.9 latency;
- CPU/profile evidence for the changed call path;
- monitor or lock contention; and
- resident/direct memory, retained arena size, or queue occupancy when
  ownership changes.

New per-row or per-record collaborator calls remain statically bound and
monomorphic or carry measured evidence justifying the alternative.

## 7. Delivery structure

The work does not run as twenty simultaneous refactors. Use this structure
under the project WIP limit, with one integrator per overlapping lane.

### 7.1 Immediate correctness

GCR01 seals header admission behind `ProtocolFrameCodec` for client and server.
It is the first accepted production slice.

### 7.2 Authorization and inventory/evidence decisions

Record integrators, correct the inventory to exclude generated-style JDBC
bases, complete P09 evidence equivalence, and inventory the
speculative atomic/journal modules before mutation or deletion.

### 7.3 Named milestone prerequisites

Select work under the WIP limit rather than starting all three lanes:

- GCR02 establishes K16 directionality before the first downstream slice that
  changes indexed durable row, index, page, or WAL behavior;
- Q09 establishes each relevant relational authority before a downstream
  catalog, type, maintenance, or access-method slice would deepen its current
  shared ownership. The same integrator may begin a disjoint residual-session
  extraction once its prerequisite authority and scratch have moved; it need
  not wait mechanically for Q09g; and
- GCR03 establishes authoritative binder/runtime ownership before downstream
  nested-query expansion or bound nested-type changes. Unrelated semantic
  fixtures and independent type-contract work need not wait.

`CatalogRecord` family work is coordinated with Q09/U02. Sequence, view, and
index families may move when their callers and scratch owner are clear; only
table-definition admission waits for the Q09b schema-token attachment.

### 7.4 Independent cleanup lane

An evidence-selected GCR04 deletion may proceed independently when its exact
inventory, evidence replacement or amendments, and affected gates are
complete. It does not create replacement interfaces.

### 7.5 Consumer-triggered work

Parser/carrier deepening, the WAL forced-batch lease, artifact publication,
the pending-mutation buffer, and additional query-state deepening are scheduled
when their current consumer and locality/correctness payoff make them the
smallest valuable slice. Group publication and vacuum already consume the WAL
lease; direct/group commit and scan overlays consume the pending buffer; and the
benchmark writer consumes artifact publication. Run the WAL lease with the
GCR02 caller migration when that avoids duplicate transition work.

### 7.6 Retained modules

`AbstractDatabaseMetaData`, `AbstractResultSet`, `LocalWal` outside its lease
contract, and `FaultingDurableDirectory` are no-action modules. This is not a
delivery gate. Revisit them only for a named invariant or consumer, not size.

## 8. Exit criteria

### 8.1 Refactor criteria

A refactor slice is complete only when:

- the old owner deletes the moved state and operation family;
- dependencies are directional and no new context bag, callback cycle, or
  forwarding-only interface replaces the original class;
- the owning public/package operation remains the principal behavioral test
  surface, including material failure and cleanup boundaries;
- caller-visible APIs, status precedence, SQL semantics, protocol/catalog/WAL
  bytes, and recovery behavior remain unchanged unless a separately approved
  pre-V1 format change says otherwise;
- mutable buffers and result carriers have one stated owner and lifetime;
- every array, queue, history, cursor set, arena, and retained view remains
  bounded with an explicit failure/backpressure status;
- relevant warmed allocation and River-owned copy budgets do not regress;
- the section 6 complexity evidence and selective performance packet are
  recorded; and
- the appropriate independent architecture, correctness, relational,
  boundary/security, or performance reviewer approves correctness-critical
  moves.

### 8.2 Deletion criteria

A deletion is complete only when it has:

- an exact source, type, module, build wiring, dependency-edge, test, document,
  and accepted-evidence inventory;
- zero production source consumers and zero production dependency-edge
  consumers;
- an evidence-equivalent surviving producer and schema or an explicit
  amendment to every consuming milestone;
- affected compile, test, dependency, and policy checks; and
- the project-owner approval recorded by this accepted plan for the described
  pre-V1 public API/module removal.

Line count is not an exit criterion. The useful result is that a future change
to one concept has one authoritative place to go and can be proved through a
smaller, deeper test surface.
