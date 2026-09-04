# River external observability and diagnostic utility outline

Status: proposed for senior pre-implementation review  
Date: 2026-09-03  
Scope: externalising River's current transaction, lock, protocol, durability,
and JVM diagnostics without placing an exporter on a correctness-critical path

## Decision

This is practical. River should expose one authenticated, bounded diagnostic
capture service and a separately distributed `river-observe` client utility.
The server and client libraries retain fixed-width instrumentation and generic
correlation, but the disabled state is the normal state and performs no
formatting, exporting, blocking, or per-event allocation. The utility starts a
time-bounded capture, retrieves a versioned snapshot or event stream, renders a
bounded human summary, and writes complete artifacts. OpenTelemetry is an
optional adapter over those cold artifacts or the utility's off-engine event
consumer. It is not a dependency or callback in `river-tx`, storage, WAL, SQL,
the protocol codec, or `river-observability-api`.

The first delivery should externalise the diagnostic capability already needed
to explain the TPS retry storm. It should not attempt to build a general APM
product. The existing specialised deadlock signature store remains the source
of complete deadlock evidence; the generic event ring is suitable for bounded
notifications and ordinary low-cardinality events, not for flattening an
arbitrary cycle into four scalar fields.

## Evidence in the current tree

The design is an extraction and hardening exercise, not a greenfield invention:

- `river-observability-api` already owns dependency-light event, metric,
  redaction, and no-op contracts. `NoOpObservability` has an allocation test over
  one million calls, while `BoundedEventRing` is a preallocated non-blocking
  MPSC ring with explicit drop, backpressure, publication-hole, and consumer
  misuse counters
  (`river-observability-api/src/main/java/io/riverdb/observability/api/NoOpObservability.java`,
  `river-observability-api/src/main/java/io/riverdb/observability/api/event/BoundedEventRing.java:6`).
  Repository search finds no production producer wired to these contracts yet;
  their presence proves the mechanism boundary, not end-to-end observability.
- The architecture already assigns concrete exporters, JFR integration, and
  snapshots to a future `river-observability` boundary rather than kernel
  modules (`docs/plans/river-high-level-plan.md`, section
  `river-observability`).
- The attempt tag, step tag, and metrics epoch are generic fixed-width values in
  transaction state (`river-tx/src/main/java/io/riverdb/tx/Transaction.java:18`).
  `RiverSession.configureTransactionDiagnostics` explicitly changes local
  session state without a protocol exchange
  (`river-engine-api/src/main/java/io/riverdb/engine/api/RiverSession.java:9`).
- Remote execution piggybacks the three values on SQL, prepared execution, and
  transaction-program request frames. The server applies them before execution
  (`river-server/src/main/java/io/riverdb/server/SessionEndpoint.java:604`). No
  separate diagnostic request is made for each statement.
- Deadlock diagnostics already use fixed arrays, versioned fingerprints,
  collision guards, per-epoch aggregate coverage, bounded victim events,
  bounded exemplars, precise scheduler grant preconditions, and cleanup/outcome
  reconciliation (`river-tx/src/main/java/io/riverdb/tx/LockDeadlockDiagnostics.java`,
  `LockDeadlockDiagnosticsSnapshot.java`).
- The current database always constructs a large bounded diagnostic store
  (`river-engine/src/main/java/io/riverdb/engine/EmbeddedDatabase.java:30`) and
  formats it through a benchmark-oriented `StringBuilder` method
  (`EmbeddedDatabase.java:346`). Those are not yet suitable public lifecycle or
  wire contracts.
- `tools/tps-test.sh` manages both processes, client and server JFR, immutable
  output destinations, hashes, run metadata, phase/status reporting, and
  deadlock/client-retry reconciliation. `tools/trace-update.sh` adds
  transaction-scoped wall, CPU, non-CPU, allocation, protocol-request, and byte
  evidence plus client/server JFR analysis. These workflows are valuable
  prototypes for the external utility.

## Immediate preservation requirements

The following are diagnostic data contracts, not temporary benchmark details.
They must survive refactoring. Internal Java class names and the current text
format are not contracts and may be replaced completely.

### Correlation and phase

- Preserve an opaque positive attempt tag, non-negative operation/step tag, and
  positive generic metrics epoch, with all-zero meaning absent. Keep these
  fields generic below the session boundary; `river-tx` must never contain
  TPC-C family, terminal, phase, or statement-name types.
- Preserve allocation-stable application control through the JDBC extension
  currently represented by `RiverTransactionDiagnostics`, including access to
  the current step for error accounting.
- Preserve piggybacking on existing SQL, prepared, and program execution frames.
  Diagnostic context may use an optional v5 request extension when absent, but
  enabling correlation must never add a protocol exchange per statement,
  transaction attempt, commit, or program action.
- Preserve distinct warmup, measured, drain, and unclassified epochs. The
  kernel carries only the numeric epoch; the client/session boundary owns
  labels.
- Preserve a server-monotonic event sequence. Wall time is supplementary and
  must not replace sequence ordering.

### Lock and deadlock explanation

- Preserve the exact edge taxonomy: incompatible active owner,
  conversion-priority dependency, and FIFO fairness predecessor.
- Preserve the precise scheduler-enforced grant precondition and its evaluated
  result for every edge. Scheduler admission, blocker graph construction, and
  diagnostic self-validation must continue to share the same predicate.
- Preserve request mode, held mode when applicable, blocker request mode,
  waiter and blocker queue kind/order, resource scope, and stable resource
  identity. Internal privileged snapshots may retain canonical numeric key
  material needed for self-validation; external output uses a capture-scoped
  keyed digest unless the caller holds the explicit sensitive-diagnostics
  privilege.
- Preserve versioned normalized cycle fingerprints and independent collision
  guards. Aggregate counters by epoch and fingerprint must cover every victim
  selection, including victim outcomes, queued requests cancelled, holdings
  released, and first/last event sequence.
- Preserve bounded exemplars per admitted fingerprint and bounded epoch/event/
  edge capacities. Retaining only the first global events is insufficient.
- Preserve victim-selection, cleanup, and terminal transaction-outcome records
  as separate facts. One victim is not equivalent to the number of cancelled
  queued requests.
- Preserve self-validation failures, fingerprint collisions, fingerprint-table
  overflow, epoch overflow, event overflow, exemplar overflow, cycle-edge
  overflow, and event-sequence overflow. Any such condition marks a
  completeness-required capture invalid; it must never be silently merged or
  omitted.

### Client and performance evidence

- Preserve the bounded client map from attempt tag to logical transaction
  sequence, attempt number, family/operation labels, terminal or session,
  retryable server status, client retry decision, and phase. This resolution is
  a client/tool concern, not a lock-manager concern.
- Preserve exact reconciliation rules: one server terminal outcome per attempt
  except an explicit indeterminate outcome; one retry decision for the
  preceding retryable outcome; retry versus retry exhaustion is exclusive; and
  measured deadlock outcomes reconcile with measured server victim outcomes.
- Preserve correlation overflow, missing/reused tag, duplicate outcome,
  unmatched retry, and unclassified retry counters as evidence-invalidating
  conditions.
- Preserve protocol request and byte counts by attempt, commit, transaction
  family, and phase, including the one-decimal request-per-attempt and
  request-per-commit views. Distinguish logical JDBC exchanges from physical
  frames when physical counters become available.
- Preserve lock-wait entered, actually-blocked, granted, timeout, deadlock,
  cancellation, and blocked-nanosecond counters. Preserve explicit
  `lock_escalation_supported` and escalation count rather than implying that
  escalation exists.
- Preserve WAL/group-commit attribution by shared-group, direct commit,
  checkpoint, and other cause, plus groupability/fallback reason, successful
  cohort size, bytes, force count/time, and queue/preflight/append/force/
  publication time as those P1 counters land.
- Preserve client and server JFR as optional companion artifacts. Preserve the
  simple-update trace's per-step wall, CPU, non-CPU, allocation, protocol
  request, bytes sent, and bytes received evidence. JFR is complementary
  sampled JVM evidence, not the source of transaction reconciliation.
- Preserve immutable artifact destinations, complete raw files, hashes,
  command/configuration/environment metadata, phase-specific failures, and
  readable bounded console summaries from `tools/tps-test.sh`.

## Proposed ownership and module boundaries

```text
application/JDBC tags                 administrative capture client
          |                                      |
          | existing execution frame             | bounded control frames
          v                                      v
river-client / river-jdbc  <------ river-protocol v5 ------> river-server
          |                                                  |
          |                                            authorization,
          |                                            capture lifecycle
          v                                                  v
client correlation recorder                         engine capture coordinator
                                                             |
                              +------------------------------+----------------+
                              |                                               |
                  specialised bounded stores                      generic event/metric sinks
                  (deadlock signatures/events)                     (ordinary notifications)
                              |                                               |
                              +---------------- frozen snapshot --------------+
                                                      |
                                             river-observe process
                                        summary / archive / OTel adapter
```

Responsibilities:

- `river-tx`, WAL, storage, SQL, and execution own only the primitive facts and
  specialised fixed-storage recorders required to explain their behavior. They
  must not know protocol, JDBC, JSON, command-line, TPC-C, OpenTelemetry, or
  exporter types.
- `river-observability-api` continues to own stable dependency-light event and
  metric identities, fixed-field reusable carriers, no-op sinks, bounded-ring
  contracts, and central sensitivity classifications. Do not put an OTel API or
  SDK dependency here.
- The engine owns a `DiagnosticCaptureCoordinator`: capture state, generation,
  category gates, capacity admission, snapshot freeze, and aggregation across
  subsystem recorders. The engine API exposes typed caller-owned snapshot
  carriers, not formatted strings and not `river-tx` implementation classes.
- `river-server` owns authenticated administrative admission, control-message
  handling, snapshot pagination, connection quotas, and response framing. It
  does not format JSON or invoke an exporter.
- `river-client` owns the capture-control client and reusable page decoder.
  JDBC keeps application attempt/step tagging but is not the administrative
  capture API.
- A concrete `river-observability` implementation is added only with the first
  real utility consumer. It owns capture archive codecs, sanitization, analysis,
  JFR coordination, and optional exporter adapters. If a separate module would
  initially contain only forwarding interfaces, keep the concrete code in the
  utility until there is a second consumer.
- `river-observe` is a standalone distributable CLI using `river-client`. The
  OpenTelemetry SDK and OTLP dependencies, if selected, belong in an optional
  CLI/exporter artifact such as `river-observability-otel`, never on the server
  runtime classpath unless an explicit in-process exporter is later approved.

## Runtime switch and disabled-path contract

### Concrete state model

Use one database-scoped controller with the states:

```text
DISABLED --start/admit--> ACTIVE --stop/expiry--> FREEZING --> SEALED
SEALED --release/expiry--> DISABLED
```

Only one capture is active or retained per database in the first delivery. A
successful start returns a nonzero capture ID, capture generation, admitted
categories/capacities, monotonic start point, expiry, schema versions, and
sanitization level. Repeating start while `ACTIVE`, `FREEZING`, or `SEALED`
returns `CONFLICT` and identifies the existing capture in caller-owned status
detail. This avoids ambiguous overlapping evidence and unbounded retained
snapshots.

The control thread validates and allocates all configured arrays before one
release publication of an immutable active configuration. No producer observes
a partially initialized recorder. Stop first atomically changes the generation
to `FREEZING`; producers that already observed the old active generation may
finish one bounded publication. The coordinator waits only on the control
thread for a bounded quiescence interval, seals producer positions and counters,
then publishes `SEALED`. A timeout seals the best available state with
`quiescence_incomplete=true`; it never stalls database workers.

Fetching a snapshot borrows from the immutable sealed bank and copies pages
into caller-owned protocol buffers. Release or TTL expiry reclaims the bank.
The first implementation should reject a new capture while a snapshot remains
sealed rather than introduce double buffering. Double-buffered captures are a
future measured requirement.

### Disabled-path guarantee

- The database starts in `DISABLED`; unlike the current hard-coded
  `EmbeddedDatabase.DEADLOCK_DIAGNOSTICS`, full diagnostic arrays are allocated
  only by the administrative control thread during start.
- Each instrumented subsystem holds a stable no-op recorder or reads one
  generation/category word. The disabled inner path is at most one predictable
  branch and no virtual callback chain. It performs no clock read, atomic
  increment, hashing, context copy, string construction, buffer claim, or
  allocation.
- Existing always-useful operational counters may remain active only where
  their cost is independently measured and accepted. Completeness-required
  diagnostic fingerprints, exemplars, and event records are gated.
- When a diagnostic context is absent, protocol v5 omits its optional 24-byte
  extension. When present, it stays on the existing execution frame. This
  reduces disabled wire cost without changing the no-extra-round-trip rule.
- Enabled kernel publication is allocation-free after capture admission.
  Producer work is bounded by category: ordinary events make one finite ring
  claim; deadlock fingerprint work is performed only after a cycle is found and
  is bounded by maximum cycle edges and configured tables.
- Saturation never blocks or changes transaction correctness. It increments a
  bounded counter and either drops the event or retains aggregate-only evidence
  according to the category contract.

The current `BoundedEventRing.enabled(boolean)` is useful but insufficient as
the database-wide switch: it gates only that ring and can leave subsystem
recorders, specialised stores, and capture generations inconsistent. The
coordinator is the single lifecycle authority; ring enablement is an internal
consequence of its state transition.

## Capture contract

### Start request

Required fields:

- capture schema version and requested categories;
- `BEST_EFFORT` or `RECONCILABLE` completeness mode;
- fixed capacities for epochs, fingerprints, events, exemplars, edges, and
  generic ring events, each bounded by server policy;
- maximum duration and sealed-snapshot retention duration;
- sanitization level requested; the server may only reduce visibility;
- optional category thresholds and client-generated capture label digest.

Admission returns `OK`, `ACCESS_DENIED`, `FEATURE_NOT_SUPPORTED`,
`INVALID_EXTERNAL_INPUT`, `RESOURCE_EXHAUSTED`, or `CONFLICT`. A failed start
does not alter the current capture. `RECONCILABLE` means every selection and
terminal outcome is aggregate-accounted and all invalidating counters are
reported; it does not promise that every event has a detailed exemplar.

### Status request

Status is a small allocation-stable response containing capture ID/generation,
state, admitted categories, start/expiry points, producer sequence watermark,
retained bytes, approximate occupancy, and all overflow/drop/collision/
self-validation/quiescence counters. Polling status is optional; the utility can
wait locally until the requested duration expires. No heartbeats are required
to keep a capture active.

### Stop, expiry, and disconnect

- `stop(captureId, generation)` is idempotent. It returns the same sealed
  identity after a duplicate request.
- Expiry initiates the same stop path. A capture is never unbounded in time.
- Client disconnect does not abandon the capture; the owner can reconnect and
  stop/fetch it. Expiry remains the ultimate cleanup.
- Server/database close seals what it can and marks `server_closed=true`.
- Cancellation of the control request is guaranteed only before the ACTIVE
  configuration is published. After publication, the response may be
  indeterminate and the client must query status by its client nonce.

### Snapshot and stream

The first delivery is stop-and-fetch snapshot, not a live unbounded stream.
The snapshot begins with:

- capture and database incarnation, generation, schema and registry versions;
- monotonic and wall-clock anchor pairs, start/end sequence watermarks, state,
  categories, capacities, and sanitization policy;
- validity and every overflow/collision/drop/hole/misuse/quiescence counter;
- category section lengths and checksums.

Sections contain primitive arrays for summaries, signatures, victim events,
edges, lock waits, protocol/WAL counters, and client correlation where a client
artifact is joined. Protocol responses paginate by opaque section cursor with
a fixed maximum payload. Each page is checksummed and identified by capture ID,
generation, section, offset, and total logical records. Retries fetch the same
page; fetching has no effect on release. A final explicit release reclaims the
capture, with TTL as fallback.

If live streaming is later justified, it consumes the existing single-consumer
ring on a dedicated exporter thread and writes to a bounded local spool before
network export. A slow or failed collector causes drop-and-count or spool
saturation, never backpressure into River kernel threads.

### Validity

`BEST_EFFORT` snapshots remain useful when events are dropped, but visibly
report every loss counter. `RECONCILABLE` snapshots are invalid if any required
category reports overflow, collision, self-validation failure, sequence
overflow, duplicate/missing outcome, quiescence failure, page checksum failure,
or client/server correlation failure. Invalid diagnostics do not fail or roll
back database work; the utility exits nonzero for an evidence command.

## Protocol and capability shape

River's current wire protocol is fixed v4 and has no negotiated capability
surface (`ProtocolFrameCodec.VERSION`, `ProtocolMessageType`). Because River is
unreleased, replace it with one coherent v5 contract rather than retaining a
v4 compatibility adapter.

The v5 HELLO response should include protocol version, server incarnation, and
a fixed 64-bit capability mask. Initial capabilities:

- transaction diagnostic context extension;
- diagnostic capture control;
- diagnostic snapshot sections v1;
- JFR coordination, only if the server runtime supports and authorizes it;
- optional live event stream, initially absent.

Add bounded administrative operations equivalent to
`DIAGNOSTIC_START`, `DIAGNOSTIC_STATUS`, `DIAGNOSTIC_STOP`,
`DIAGNOSTIC_FETCH`, and `DIAGNOSTIC_RELEASE`. They use a dedicated authenticated
control session and cannot execute SQL. Unknown capability bits and section
types are ignored only where the frame explicitly supplies their bounded
length; malformed lengths fail admission and erase payloads under existing
protocol rules.

Starting, stopping, and fetching a capture necessarily use administrative
round trips. They occur once per capture or page and are outside application
transactions. There are no new round trips on JDBC connect, begin, statement,
program action, commit, rollback, or retry. Optional transaction context is
still piggybacked on existing execution frames.

## Security and abuse controls

- Reuse River authentication, but require a separate `OBSERVE` administrative
  privilege for capture status/start/stop/fetch. Require
  `OBSERVE_SENSITIVE` for unredacted internal identities. Remote control must
  use TLS; unauthenticated loopback is permitted only for an explicitly
  configured development server.
- Safe external export is the default and fail-closed. No SQL text, literals,
  parameter values, usernames, credentials, table names, raw row keys, file
  paths, or arbitrary exception messages cross it. Preserve stable event and
  metric IDs and central sensitivity policy from `river-observability-api`.
- Resource identities exported externally are keyed capture-scoped digests
  over namespace, scope, and canonical identity. The key is random per capture
  and is not exported. This permits equality joins inside one capture without
  durable cross-capture tracking or simple key enumeration.
- Opaque application attempt/step tags are sensitive because they can carry a
  covert identifier. Export exact tags only to the owning principal or a caller
  with `OBSERVE_SENSITIVE`; otherwise emit capture-scoped digests. Server-side
  capture never interprets them.
- Enforce one active capture per database, administrator-configured maximum
  retained bytes, capacities, duration, fetch bandwidth, response pages,
  concurrent control sessions, and sealed retention. Count rejected starts,
  unauthorized requests, expiry, fetch throttling, and abandoned captures.
- Export remains best effort and is never a security audit trail. Audit requires
  independent authenticated durability and cannot share this ring or its drop
  behavior.
- JFR may contain paths, class names, SQL-adjacent allocations, and JVM
  environment data. Treat it as privileged, never include it in safe external
  export by default, and apply file ownership/retention limits.

## OpenTelemetry placement and mapping

OpenTelemetry is a cold boundary adapter in the utility process:

- Snapshot counters and fixed histograms map to OTel metrics with bounded
  attributes such as database identity digest, capture ID, phase/epoch,
  status, transaction category supplied by the client, edge kind, and
  fingerprint version. Attempt IDs, transaction IDs, raw fingerprints, and
  resource digests are not metric attributes because their cardinality is
  unbounded.
- Deadlock selections, diagnostic invalidation, cleanup mismatches, WAL stalls,
  and capture lifecycle map to structured OTel logs. Detailed cycle edges are
  attached only to sampled/bounded log records or archived as a linked River
  artifact.
- Do not fabricate server transaction spans from the current deadlock store.
  Complete spans require explicit begin/end/status timestamps and parentage.
  A later client interceptor may create one span per transaction attempt and
  link it to server events through the opaque attempt tag. Lock requests should
  be span events only for retained exemplars, never one span per lock operation.
- Monotonic sequence is authoritative inside a capture. The snapshot records a
  wall-clock/monotonic anchor pair so the utility can derive approximate export
  timestamps while retaining original sequence and clock-domain attributes.
- OTLP retry, batching, TLS, credentials, endpoint configuration, and local
  spool behavior belong entirely to the utility/exporter. Collector failure
  cannot affect capture, SQL status, commit latency, or server liveness.
- Use a versioned `river.*` attribute/event schema. Do not claim standard
  database semantic-convention fields unless River can supply their documented
  meaning exactly.

Immediate implementation ends with a stable River capture archive and JSON
summary. OTLP export is optional stage four work; it must not delay extraction
of the current diagnostics.

## CLI workflow and artifacts

Proposed user-facing commands:

```sh
river-observe capabilities --url=river://host:port

river-observe capture \
  --url=river://host:port \
  --categories=locks,retries,protocol,wal \
  --mode=reconcilable \
  --duration=30s \
  --output-dir=run-2026-09-03

river-observe analyze \
  --input=run-2026-09-03/capture.riverdiag

river-observe export-otlp \
  --input=run-2026-09-03/capture.riverdiag \
  --endpoint=https://collector.example
```

`capture` performs capability check, authenticated start, local duration wait,
idempotent stop, paginated fetch with checksums, atomic artifact publication,
and release. Ctrl-C requests stop/fetch before exiting; expiry protects against
abandonment. It refuses to overwrite a non-empty destination.

The output directory contains:

- `capture.riverdiag`: complete versioned binary snapshot;
- `summary.txt`: bounded readable findings, top signatures, reconciliation,
  saturation, and invalidity reasons;
- `summary.json`: sanitized machine-readable representation;
- `manifest.properties`: command, versions, capabilities, capture configuration,
  server/database identity digest, start/end anchors, statuses, file hashes,
  and sanitization policy;
- optional `client.jfr` and `server.jfr`, visibly marked privileged;
- optional client correlation input/output kept separate from the generic server
  capture.

Console output is intentionally bounded: capture ID/state, validity, retained
bytes, event counts, loss counters, top fingerprints, lock-wait totals, protocol
requests per attempt/commit to one decimal place, artifact paths/hashes, and a
clear nonzero exit reason. Complete event/correlation rows remain in artifacts,
not thousands of console lines.

`tools/tps-test.sh` should eventually invoke `river-observe capture` against its
managed server and join the benchmark-owned retry map. Until that path is
proven, preserve the current Java-emitted server metrics, reconciliation, and
JFR files. Do not delete the working diagnostic path before the external tool
passes paired-output tests.

## Staged implementation

Stages 1 and 2 are implementation checkpoints inside one merge unit. Do not
merge stage 1 as a transitional architecture with both the formatted benchmark
API and typed capture API retained. The mergeable delivery ends at stage 2,
after every River-owned caller uses the new path and the superseded path is
deleted. Stages 3 and 4 are independently useful later capabilities rather than
compatibility phases.

### Stage 0: freeze present evidence semantics

- Add schema-level tests around the preservation list above, especially
  fingerprint normalization, fairness edges, cleanup/outcome reconciliation,
  epoch separation, and every invalidation counter.
- Record a golden sanitized snapshot from a deterministic two-transaction
  deadlock and a retry-correlated TPS fixture.
- Measure current disabled/no-op mechanisms and current always-enabled
  deadlock-store cost before changing lifecycle.

Exit: reviewers can identify every required field and reconciliation invariant
without depending on benchmark text parsing.

### Stage 1: engine-local dynamic capture

- Replace the hard-coded always-enabled deadlock config with the database-scoped
  capture controller and typed caller-owned snapshot sections.
- Allocate stores on the control thread before activation; install no-op gates
  while disabled; implement stop/freeze/fetch/release and TTL.
- Move `EmbeddedDatabase.appendDeadlockDiagnostics(StringBuilder)` formatting
  out of the engine. Change all River-owned benchmark callers together; do not
  leave duplicate formatted and typed paths.
- Keep all current TPS behavior working through an embedded control adapter
  while the network control plane is absent.

Exit: deterministic embedded tests can toggle capture concurrently, disabled
execution is allocation-free, and a sealed typed snapshot exactly reconciles
the current deadlock fixture.

### Stage 2: protocol and standalone utility

- Replace protocol v4 with v5 capabilities, optional transaction context, and
  authenticated diagnostic control messages.
- Implement client capture control, paginated snapshot transfer, safe
  sanitization, archive codec, analyzer, and bounded console output.
- Integrate the utility into `tools/tps-test.sh`; compare old and new artifacts
  on the same deterministic runs, then delete the superseded benchmark-only
  server formatting/API and parsing path in the same delivery.

Exit: a separately launched utility captures a managed server with no
application transaction round trips added, and the TPS retry/deadlock gate uses
the utility artifact.

### Stage 3: broaden immediately consumed categories

- Add physical protocol-frame counters, lock-wait duration/status snapshots,
  and the accepted P1 WAL/group-commit counters.
- Fold the reusable portions of `tools/trace-update.sh` into the utility while
  retaining optional client/server JFR collection.
- Add client correlation archive ingestion without moving benchmark labels into
  server or kernel modules.

Exit: the utility can explain retry, lock, protocol chattiness, and durability
ceilings from one bounded evidence bundle.

### Stage 4: optional OpenTelemetry adapter

- Add an optional utility-side OTLP exporter and versioned mapping tests.
- Prove bounded metric cardinality, redaction, collector-outage isolation,
  batching/spool limits, and no server runtime dependency.
- Add client transaction spans only if explicit begin/end correlation is
  implemented and tested; otherwise export metrics and logs only.

Exit: OTel export reproduces accepted aggregate totals and links retained
exemplars without changing server behavior or evidence validity.

## Test and performance gates

### Contract and correctness

- Disabled, start, duplicate start, stop, duplicate stop, expiry, disconnect,
  fetch retry, release, stale generation, server close, and control-request
  cancellation tests with exact states and statuses.
- Concurrent activation/deactivation tests proving no producer writes into a
  reclaimed generation and that freeze observes every completed publication or
  declares bounded quiescence failure.
- Capacity-bound tests for every configured array and response page, arithmetic
  overflow tests, and allocation-failure admission leaving the prior state
  untouched.
- Saturation tests proving database outcomes are unchanged and counters are
  exact. `RECONCILABLE` must fail diagnostic validity on every required loss or
  collision; `BEST_EFFORT` must remain readable and visibly incomplete.
- Protocol fuzz/bounds tests, capability mismatch, malformed pagination,
  duplicate/out-of-order page, checksum failure, authentication, authorization,
  and redaction tests.
- Golden archive/schema compatibility tests. Because River is unreleased,
  incompatible pre-v1 schema improvements replace old readers and fixtures
  together; no legacy decoder remains.
- End-to-end deadlock test proving exact owner and fairness edge fields, one
  victim, cleanup, terminal outcome, and client retry all reconcile.
- TPS paired test proving the external tool reports the same attempt/deadlock
  totals, epochs, signatures, and validity as the preserved baseline before the
  baseline path is deleted.

### Disabled overhead

Run warmed allocation and throughput/latency A/B tests with identical workload,
JVM, seed, and server process:

- exactly zero River-owned allocated bytes per disabled hook over at least one
  million invocations;
- no clock reads, hash work, atomics, or context copies in the disabled hook,
  verified by exact-method bytecode/source policy and a focused profile;
- no diagnostic payload bytes on v5 execution frames when context is absent;
- single-terminal and contended TPS median regression no worse than 1.0%, and
  p99 latency regression no worse than 2.0%, with enough paired samples for the
  confidence interval to exclude larger regressions;
- no additional application protocol requests or WAL forces.

If noise prevents the end-to-end threshold from resolving, use a calibrated
microbenchmark to bound the hook first, then increase paired sample duration.
Do not waive the allocation or no-extra-request gates.

### Enabled overhead

- Exactly zero steady-state producer allocation after capture admission.
- Ordinary event publication has a finite claim-attempt bound; deadlock capture
  work is bounded by configured cycle edges and stores.
- At 50% ring occupancy with a draining consumer, p99 producer publication is
  initially gated at no more than 2 microseconds on the declared reference host.
  At saturation, return/drop accounting remains bounded and never parks.
- A `BEST_EFFORT` all-category capture must retain at least 90% of baseline TPS
  and no more than double p99 latency on the reference diagnostic workload.
  `RECONCILABLE` capacity is configured to avoid overflow for the acceptance
  run; any overflow fails evidence rather than silently relaxing the gate.
- Snapshot formatting, compression, filesystem writing, JFR parsing, and OTLP
  export are measured on utility threads/processes and must produce no change
  in transaction status. Collector unavailability for the full capture duration
  must not alter server TPS beyond the enabled-capture envelope.

Numeric thresholds are initial review targets and must be calibrated on the
declared host before promotion. The invariants—boundedness, zero allocation,
no correctness backpressure, no per-operation round trip, and explicit loss—are
not negotiable.

## Non-goals

- No synchronous OTel span/event export from kernel, SQL, WAL, or server request
  threads.
- No unbounded event history, dynamic labels, maps, SQL text, literal values, or
  one metric series per transaction/resource/fingerprint.
- No guarantee that ordinary best-effort events are complete, ordered across
  clock domains, durable, or suitable for security audit.
- No lock escalation implementation or lock-policy change as part of tooling.
- No debugger that can mutate transactions, locks, queues, scheduler policy, or
  capture evidence.
- No automatic always-on full tracing. Low-cost accepted counters may remain
  always active; detailed capture requires an explicit authorized flag and TTL.
- No promise of distributed traces until River records complete lifecycle and
  parent/link semantics. Metrics and structured logs are useful independently.
- No immediate live tail, web UI, collector-specific server exporter, or
  multi-capture buffering. Add these only for a named consumer after the
  snapshot utility is working.

## Pre-implementation review questions

1. Is one active/sealed capture per database sufficient for the first client
   utility, or is double buffering an immediate operational requirement?
2. Which existing `StatusDetail` fields can carry capture identity/state without
   introducing a diagnostic-specific exception or formatted error path?
3. Should exact attempt tags be visible to an `OBSERVE` caller who does not own
   the tagged application session, or require `OBSERVE_SENSITIVE` universally?
4. What retained-byte and duration ceilings are safe defaults for the first
   managed server profile?
5. Which lock resource identity components are essential for offline cycle
   validation before safe external digesting?
6. Is server-side JFR control supportable under the same privilege, or should
   the first utility only collect JFR when it launched the managed server?

None of these questions blocks preserving the current diagnostic semantics or
moving OTel to the cold utility boundary.
