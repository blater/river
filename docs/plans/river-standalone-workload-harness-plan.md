# River Standalone Workload Harness Plan

<!-- markdownlint-disable MD013 -->

Status: Proposed implementation plan

Date: 2026-08-21

Audience: River contributors, performance reviewers, DBMS adapter authors,
workload-suite authors, and benchmark operators

Related plans:

- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [River Harness Agent Mix](river-harness-agent-mix.md)
- [River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)
- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)
- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)

## 1. Purpose

Build a standalone database workload harness whose first useful workload is a
TPC-C-derived MariaDB correctness and performance suite. The component must
also be capable of adding TPC-H, TPC-E, TPCx-IoT, YCSB, and River-owned suites
without moving their generators, scheduling, verification, metrics, or report
formats into DBMS-specific connection code.

The harness must run the same declared workload against MariaDB, River when it
is ready, and other DBMS targets on the same physical host. A comparison is
emitted only after the
harness has checked workload, schema, isolation, durability, environment, and
run-method compatibility. A shared machine alone does not make two results
comparable.

The implementation lives in a separate `river-harness` Git repository with its
own history, releases, issue tracking, `go.mod`, CI, commands, tests, and
artifacts. It is not a directory, Git submodule, or Gradle project inside the
River repository. River production and test modules do not depend on it.

## 2. Decisions

1. **TPC-C is the first suite, not the framework architecture.** Core types
   describe phases, actors, operations, results, and target capabilities. They
   do not contain warehouses, districts, terminals, or TPC metrics.
2. **Workload semantics, DBMS bindings, and DBMS adapters are separate.** A
   suite owns deterministic inputs and invariants. A suite/DBMS binding owns
   schema and statement choices. A DBMS adapter owns connection configuration,
   protocol/driver, parameters, rows, transactions, and native statuses.
3. **MariaDB is the bootstrap DBMS.** Its adapter and binding establish the
   first working vertical slice while River's SQL query execution path is in
   progress. MariaDB driver types, SQLSTATE/native codes, connection settings,
   and process details do not enter core, suite, reporter, or comparison code.
4. **A second real adapter proves the boundary.** PostgreSQL is the initial
   reference adapter because it has a maintained Go driver and supports the
   relational behavior needed by the smoke and TPC-C-derived slices. River,
   Ingres, and other DBMS adapters follow the same contract when an approved
   connector and DBMS-specific binding are available.
5. **River is admitted by capability, not schedule.** River is not on the
   bootstrap critical path. Its adapter starts only after a stable public
   connection/protocol path can execute and validate the required smoke and
   TPC-C statements transactionally. Cross-repository integration then uses
   released or pinned contracts. The
   harness never imports River implementation code. Its River adapter speaks a
   named wire-protocol version and runs integration CI against an explicit
   River release or commit. Protocol changes update both repositories directly
   before V1; no mixed-version compatibility shim is implied.
6. **The harness attaches to a DBMS by default.** Starting, stopping, deleting,
   restoring, or reconfiguring a database is outside the default run command.
   A named local MariaDB development profile is the first explicit opt-in
   lifecycle controller: it starts and stops the already-installed Homebrew
   server for a run but never installs it, registers a service, initializes the
   existing data directory, or deletes data. Later lifecycle and fault
   controllers follow the same validated ownership boundary.
7. **The immutable local artifact is authoritative.** Bencher JSON,
   Prometheus/OpenMetrics, OTLP, and dashboards are projections of the run;
   failure of an optional exporter does not rewrite a completed result.
8. **No dynamic plugin system is needed initially.** Suites, bindings, and
   DBMS adapters are compile-time registered Go packages. Add a dynamic
   boundary only for a named consumer that cannot use the compiled registry.
9. **Future suites stay unwired until implemented.** The plan records their
   execution shapes and entry conditions, but no empty TPC-H, TPC-E,
   TPCx-IoT, or YCSB package is created during the TPC-C slice.
10. **Derived results are named honestly.** A run with schema, transaction,
    timing, topology, or metric deviations is not reported as an official TPC
    result and does not use a protected primary metric.
11. **`river-bench` and `river-harness` have different jobs.** The Java
    `river-bench` module retains JMH, mechanism prototypes, allocation evidence,
    and existing developer artifacts. The separate harness repository owns
    full database loading, workload execution, cross-DBMS comparison, and
    external reporting integrations.

## 3. Scope

The initial repository provides:

- one self-contained Go executable;
- deterministic, bounded data and operation generation;
- load, run, verify, inspect, report, and compare commands;
- closed-loop execution followed by open-loop scheduling;
- MariaDB and PostgreSQL DBMS adapters, with River deferred behind its
  capability gate;
- DBMS capability discovery and fail-closed suite admission;
- TPC-C-derived schema profiles and all five transaction families;
- exact status, retry, abort, and unknown-outcome accounting;
- raw latency histograms, bounded time series, immutable run artifacts, and
  environment manifests;
- Bencher Metric Format output for historical regression tracking;
- optional Prometheus/OpenMetrics and OTLP live metrics;
- semantic, load, post-run, and recovery verification; and
- a comparison reader that refuses an unlabeled invalid comparison.

The initial repository does not provide:

- an audited or officially comparable TPC result;
- a public benchmark result for River before independent review;
- a dynamic workload language or remote plugin loader;
- a web UI or a new time-series database;
- automatic installation, service registration, or silent tuning of a target
  DBMS;
- an unbounded per-operation event log;
- distributed load generation in the first TPC-C milestone;
- simultaneous competing DBMS runs on one host;
- a Git submodule or source-level dependency on River; or
- compatibility adapters for unreleased River protocol versions.

## 4. Repository and cross-repository ownership

### 4.1 Repository boundary

The initial repository is operationally independent of River:

```text
Homebrew MariaDB                    river-harness repository
----------------                    ------------------------
server and data directory <-------- MariaDB lifecycle + DBMS adapter
                                    suite/DBMS bindings
                                    core, reports, comparison

river repository                    later River integration
----------------                    -----------------------
engine/server/protocol <----------> River DBMS adapter
wire-contract fixtures              River suite bindings
release/commit identity             pinned integration CI
```

Neither repository vendors or nests the other. Local integration accepts an
explicit River checkout or server executable only after the River gate opens.
CI then checks out both repositories into sibling directories and records both
exact commits. A harness release
states the River protocol versions it implements; a River release may state the
minimum independently tested harness release without adding a code dependency.

The River repository remains authoritative for protocol framing, type tags,
status identities, authentication, and server behavior. The harness repository
owns its independent codec and pins copied golden fixtures by fixture version
and checksum. An integration test against the real server is required because
golden fixtures alone cannot prove lifecycle compatibility.

This plan remains in River because it defines River's boundary and performance
evidence consumer. Detailed implementation issues and milestone evidence live
in the harness repository once it is created.

### 4.2 Harness repository layout

```text
river-harness/
  cmd/river-harness/
  internal/
    core/
    config/
    artifact/
    metrics/
    compare/
    dbms/
      contract/
      mariadb/
      postgres/
    suite/
      smoke/
      tpcc/
    binding/
      smoke/mariadb/
      smoke/postgres/
      tpcc/mariadb/
      tpcc/postgres/
  schemas/
  dashboards/
  testdata/
  go.mod
```

Only directories with production code or an immediate test consumer are
created. The final package split may be narrower when two responsibilities fit
cleanly in one local package.

## 5. Architecture and ownership

### 5.1 Dependency direction

```text
command/config
    |
    v
core runtime ------> reporting/artifacts
    |                       |
    |                       +--> Bencher projection
    |                       +--> Prometheus/OTLP snapshots
    v
suite semantics ---> suite/DBMS binding ---> DBMS session contract
                                                  |
                                                  +--> MariaDB adapter
                                                  +--> PostgreSQL adapter
                                                  +--> River adapter (later)
```

Dependencies point from orchestration toward contracts and concrete selected
providers. A DBMS adapter cannot import a suite. A suite cannot import a DBMS
adapter or report exporter. A binding may import exactly one suite's typed
inputs and the DBMS-neutral session contract.

### 5.2 Core runtime

The core owns:

- phase lifecycle: prepare, load, verify-load, warmup, measure, cooldown, and
  verify-run;
- actor creation and deterministic actor-local random streams;
- worker ownership, cancellation, deadlines, and ordered shutdown;
- closed-loop execution and intended-schedule open-loop admission;
- bounded latency, counter, time-series, and abnormal-event collection;
- periodic snapshots for optional live exporters;
- run state and final artifact assembly; and
- no suite-specific SQL, row layout, connection detail, or expected business
  invariant.

Each worker owns one DBMS session. A session is never used concurrently and has
at most one active result stream. The runtime must bound queued work rather
than creating a goroutine for every scheduled operation. Saturation returns a
declared admission outcome and remains visible in the report.

### 5.3 Suite semantics

A suite owns:

- suite and upstream specification identity;
- scale and profile validation;
- deterministic logical rows and operation inputs;
- operation selection and actor state;
- expected success, rollback, retry, and business-rejection classes;
- load and post-run invariants; and
- required disclosure text.

TPC-C semantic code therefore owns types such as a logical New-Order input,
but it does not know how River encodes a composite key, how PostgreSQL binds a
timestamp, or where either DBMS is listening.

### 5.4 Suite/DBMS binding

A binding owns the deliberate intersection of one suite and one DBMS family:

- DDL, indexes, constraints, and schema profile;
- prepared statement text and parameter order;
- mapping logical rows into DBMS values;
- execution of each logical operation through the session contract;
- result decoding into suite-owned reusable result carriers;
- DBMS-specific verification queries; and
- a machine-readable list of deviations from the suite specification.

This boundary avoids both a lowest-common-denominator SQL dialect and
River-specific conditionals throughout TPC-C. Common SQL helpers may be
extracted only after two bindings use identical behavior.

### 5.5 DBMS adapter contract

The DBMS-neutral contract needs the following behavior, expressed here as
shape rather than a frozen Go API:

```go
type Adapter interface {
  Inspect(context.Context) (DBMSInfo, Outcome)
  OpenSession(context.Context, SessionRole) (Session, Outcome)
  Close() Outcome
}

type Session interface {
  Execute(context.Context, Statement, Parameters, *CommandResult) Outcome
  Query(context.Context, Statement, Parameters, *QueryResult) Outcome
  Begin(context.Context, IsolationLevel) (Transaction, Outcome)
  Close() Outcome
}
```

`Transaction` provides execute, query, commit, and rollback with the same
caller-owned result carriers. `QueryResult` streams into a caller-owned row and
must be closed. `Statement` is binding-owned DBMS-native text plus a stable
statement identity; the core never constructs SQL.

The final contract separates:

- native DBMS status from harness or transport failure;
- retryable conflict from business rejection;
- definite rollback from unknown commit outcome;
- affected rows from returned rows; and
- database commit acknowledgement from client receipt time.

The common outcome does not expose MariaDB driver errors, River status enums,
or PostgreSQL errors.
Each adapter maps native outcomes into a small shared classification while
preserving native code, SQLSTATE when present, and diagnostic identity in
bounded DBMS-detail fields.

Connection configuration is an opaque validated object owned by the selected
adapter. MariaDB sockets/DSNs, River endpoints/TLS tokens, PostgreSQL DSNs, and
driver properties never enter suite or core configuration types.

### 5.6 MariaDB adapter and lifecycle controller

The MariaDB adapter owns:

- `github.com/go-sql-driver/mysql` and its pinned release/checksum;
- socket or TCP connection configuration and sanitized identity;
- session, prepared statement, streaming row, transaction, and close
  lifecycles;
- SQLSTATE and MariaDB native-error classification, including unknown commit
  outcomes where the provider cannot prove the result; and
- capability discovery for server version, engine, isolation, durability, and
  statement features used by a binding.

The local Homebrew lifecycle controller is adjacent to, but separate from, the
session adapter. It owns process launch, readiness, graceful shutdown, and
run-local process evidence. It does not implement SQL and cannot be called by a
suite or binding. Section 6.1 defines its exact safety contract.

### 5.7 Reference and later adapters

The PostgreSQL adapter owns its Go driver, DSN/TLS configuration, SQLSTATE
classification, streaming rows, and transaction lifecycle. It provides the
same result ownership and unknown-outcome distinctions as far as the provider
can establish them. Provider limitations remain explicit in `DBMSInfo`.

The later River adapter owns protocol framing, authentication, sessions, typed
values, streaming results, status/commit classification, reusable buffers, and
protocol-version rejection. It validates frames at the network boundary and
does not leak River types into bindings. Versioned golden frames plus a real
server integration test guard the independent implementation. No empty River
package is created before the capability gate opens.

An Ingres adapter is a named later consumer because same-host Ingres comparison
is already authorized by River's performance plan. Its connector choice,
licensing, process topology, schema binding, isolation, and durability mapping
must be reviewed before implementation. No JDBC bridge, ODBC dependency, or
shell wrapper is assumed by this plan.

### 5.8 Comparison engine

`compare` reads completed, checksum-verified artifacts; it does not rerun a
workload. Before calculating a delta it requires compatible values for:

- suite, suite version, profile, seed, scale, and logical schema;
- operation generator version, mix, actor count, pacing, warmup, and measured
  duration;
- requested isolation and durability acknowledgement;
- verification status and admitted deviations;
- physical host identity, CPU placement, DBMS/client placement, connection
  transport, lifecycle mode, power state, cache state, and storage class;
- harness build and clock source; and
- result completeness and histogram units.

DBMS version and physical schema/index digest are recorded but may differ by
design. The report shows those differences next to the comparison. A mismatch
in a required compatibility field produces `NOT_COMPARABLE` with exact reasons
and no default percentage claim. An explicit override may render a diagnostic
view, but the view retains the `NOT_COMPARABLE` label.

Canonical comparison runs are sequential and interleaved across systems, for
example `A B B A`, using a freshly restored or regenerated database for every
independent write sample. Running both DBMS systems simultaneously is a
separate interference workload.

## 6. Configuration and trust boundaries

Configuration is versioned and validated before any connection, file creation,
or operation generation. Suite, run, and report configuration is common;
adapter connection details remain an adapter-owned document:

```yaml
schema_version: river-harness.config.v1
suite:
  id: tpcc
  profile: development-v1-derived
  seed: 42
  scale:
    warehouses: 1
dbms:
  adapter: mariadb
  binding: tpcc-mariadb-v1
  connection:
    socket: /private/tmp/river-harness-mariadb/run-id/mariadb.sock
  lifecycle:
    profile: homebrew-existing-datadir-v1
run:
  mode: closed-loop
  actors: 10
  warmup: 2m
  duration: 10m
report:
  directory: ./runs
  prometheus_listen: localhost:9464
```

Configuration files may refer to a credential file or environment variable,
but the resolved secret is never copied into a manifest, diagnostic, command
line, or metric label. Secrets are read into adapter-owned memory and erased on
close where Go permits a best-effort overwrite.

External boundaries include configuration, DBMS results, network frames,
persisted artifacts, lifecycle hooks, and imported suite data. They receive
size, type, encoding, count, checksum, path, and state validation before use.
Suite-generated typed values and normalized core configuration are trusted
inside their declared lifetimes.

Any lifecycle process uses an argument array rather than a shell command
string. Destructive reset or restore targets require an explicit owned path and
cannot resolve through an empty variable, glob, home directory, repository
root, workspace root, or filesystem root.

### 6.1 Homebrew MariaDB lifecycle profile

The discovered development-host baseline on 2026-08-21 is MariaDB
`12.3.2-MariaDB` on Apple silicon, linked at `/opt/homebrew/opt/mariadb`, with
an existing `/opt/homebrew/var/mysql` data directory. These are defaults for a
named local profile, not portable constants. Every run resolves the symlink,
records the exact server and driver versions, and rejects unexpected drift
unless the operator accepts a new profile or baseline.

The baseline manual command is:

```sh
/opt/homebrew/opt/mariadb/bin/mariadbd-safe \
  --datadir=/opt/homebrew/var/mysql
```

The controller constructs that invocation without a shell and adds explicit
run-owned `--socket`, `--pid-file`, and absolute `--log-error` paths beneath
`/private/tmp/river-harness-mariadb/<run-id>/`. Its controlled profile passes
`--no-defaults` as the first option so `/opt/homebrew/etc/my.cnf` or a future
`/etc/my.cnf` cannot silently change a run. It uses `--no-auto-restart` so a
crash remains visible rather than being hidden by the wrapper. The exact
effective arguments, excluding secrets, are recorded in the artifact.

The resulting argument shape is:

```text
/opt/homebrew/opt/mariadb/bin/mariadbd-safe
--no-defaults
--datadir=/opt/homebrew/var/mysql
--socket=<absolute-run-socket>
--pid-file=<absolute-run-pid-file>
--log-error=<absolute-run-error-log>
--no-auto-restart
```

The implementation passes these as individual process arguments; angle-bracket
values are placeholders, not shell syntax. Unit tests pin the first-argument
requirement and integration tests prove the effective socket, PID file, log,
and data directory. The controller does not run `mariadbd-safe` merely to
inspect configuration because option-parsing behavior may itself start the
server.

Before launch the controller:

- verifies the configured binaries and data directory resolve to the approved
  absolute targets and that the current user can access them;
- refuses to continue if a Homebrew MariaDB service is active;
- refuses to adopt or stop an existing server, socket, PID file, or process;
- creates a fresh private run directory without altering the existing data
  directory; and
- checks that the requested socket and optional loopback TCP endpoint are not
  already owned.

With `--no-auto-restart`, the safe wrapper exits after launching the server;
the controller therefore owns the run through the explicit server PID file and
socket rather than assuming the wrapper remains resident. Readiness is a
bounded `mariadb-admin --no-defaults --socket=<run-socket> ping` loop plus a
server identity query. Shutdown uses
`mariadb-admin --no-defaults --socket=<run-socket> shutdown`, waits for server
termination, and reports a failure rather than killing an unverified PID. If
admin authentication is required, a private run-owned `--defaults-file` is the
first argument instead of `--no-defaults`; its contents are never logged or
published. A bounded SIGTERM escalation is allowed only after matching the
recorded PID, executable, data directory, and run identity. Forced termination
is never normal cleanup. Start, readiness, and stop intervals are lifecycle
metrics and are excluded from load or workload measurement.

This first profile treats `/opt/homebrew/var/mysql` as pre-existing user state:
it never invokes `mariadb-install-db`, deletes files, rewrites ownership, or
resets the whole instance. Smoke/load commands use a unique harness-owned
database name and an ownership marker; cleanup may drop only that verified
database when explicitly requested. Canonical independent write samples later
use an explicitly initialized harness-owned data directory or a reviewed
backup/restore procedure, never a destructive reset of the Homebrew directory.

Unix-socket transport is the local bootstrap default. A canonical comparison
must use equivalent transport placement or declare the difference and become
`NOT_COMPARABLE`. The generic attach mode remains available for an externally
managed MariaDB, PostgreSQL, or later River process.

## 7. Measurement and resource contract

### 7.1 Timing

Every operation records:

- intended issue time for open-loop runs;
- actual issue time;
- completion time;
- service and intended-schedule latency;
- native and common DBMS outcomes;
- retry count and final transaction outcome; and
- result-consumption completion, not merely query-open time.

The monotonic clock is used for intervals. Wall time is metadata only. Clock
source, resolution, and detected backwards/overflow failures are recorded.

Closed-loop and open-loop results never share one throughput or latency row.
The first implementation is closed-loop; open-loop is added only with a bounded
admission queue and coordinated-omission tests.

### 7.2 Hot-path ownership

The measurement loop aspires to zero steady-state allocation after actor and
statement initialization. Each worker reuses:

- operation input and result structs;
- parameter arrays and encoded text buffers;
- DBMS request/response buffers where the driver permits;
- row carriers and verification scratch; and
- latency recorder state.

No map, formatted string, SQL interpolation, captured closure, or unbounded log
entry is created per operation. Go allocation profiles and benchmark tests
measure the actual result before an allocation claim is accepted. Provider
allocations outside harness ownership are reported separately when measurable.

### 7.3 Bounds and backpressure

The configuration declares maximum actors, DBMS sessions, parameters,
statement bytes, result columns/bytes, queued operations, exporter snapshots,
abnormal events, artifact bytes, and runtime. Every exhausted bound produces a
named status and count. Optional telemetry uses bounded periodic snapshots;
when its queue is full it drops a snapshot, increments an exporter-drop counter,
and does not block database workers.

## 8. Artifact and reporting contract

### 8.1 Canonical artifact

A completed run contains:

```text
run-id/artifacts/
  manifest.json
  result.json
  validation.json
  deviations.json
  histograms/
  time-series.ndjson
  environment.json
  comparison.json          # compare command only
  bencher.json             # optional projection
```

All JSON documents have versioned schemas. `result.json` references every
payload by relative path, byte count, and SHA-256. It is written last in a
staging tree. Publication verifies the persisted tree, exclusively claims the
run ID, and atomically installs the verified `artifacts/` child on the same
filesystem. A claim without verified `artifacts/` is incomplete and is never
silently reaped.

The manifest records:

- harness repository revision and release;
- River repository revision for River integration runs;
- suite, binding, DBMS adapter, generator, and schema versions;
- upstream specification or workload-package version and source;
- seed, scale, actors, mix, timing phases, retry policy, and bounds;
- isolation, durability acknowledgement, commit semantics, and cache state;
- DBMS product/version/configuration and physical schema/index digest;
- lifecycle mode/controller version, executable realpath, sanitized argument
  digest, data-directory identity, transport, PID evidence, readiness/start/
  stop outcome, and whether the process was harness-started;
- host, OS, CPU, memory, storage, network, process placement, and clock;
- exporter enablement and observed exporter drops; and
- disclaimer and deviation identities.

Connection secrets and full credential-bearing DSNs are replaced with a
sanitized connection identity before publication.

### 8.2 Metrics

Summary rows contain count, minimum, p50, p95, p99, p99.9, maximum, mean, and
the referenced raw histogram for each suite operation, phase, mode, and final
outcome. Attempts, commits, expected rollbacks, business rejections, conflicts,
retries, unknown outcomes, admission failures, and verification failures remain
separate.

Live metric labels are limited to bounded values such as suite, operation,
phase, mode, DBMS family, and outcome. Run ID, transaction key, SQL text,
exception text, request ID, and individual status detail are not metric labels.

### 8.3 External compatibility

The harness produces Bencher Metric Format JSON as a lossy scalar projection
of throughput, selected percentiles, error rates, recovery duration, and other
named measures. Bencher is suitable for history, thresholds, and CI review; it
does not replace the raw histogram, validation, environment, or deviation
documents.

An optional HTTP endpoint exposes Prometheus/OpenMetrics counters, gauges, and
histograms during a run. An optional OTLP exporter sends periodic metric
snapshots to an OpenTelemetry Collector. Grafana dashboards are provisioned
against a Prometheus-compatible backend. Exporters are disabled for canonical
timing unless their overhead has been measured or they are part of the declared
candidate and baseline configuration.

## 9. TPC-C-first workload

### 9.1 Profiles

`tpcc-development-v1-derived` preserves the logical entities and transaction
intent while establishing a stable cross-DBMS logical profile. MariaDB's
binding uses native composite primary/foreign keys, indexes, constraints,
transactions, and prepared SQL. The profile pins column meaning, transaction
inputs and selection, required isolation/durability behavior, validation, and
logical results; the binding owns the physical DDL and SQL.

This profile reports ordinary committed transactions per second and operation
latencies. It does not report `tpmC` or claim comparison with published TPC-C
results.

PostgreSQL and later DBMS bindings implement the same logical profile. Physical
keys, indexes, execution plans, and SQL dialect may differ by DBMS and are
recorded in the schema digest and deviation document. A comparison is admitted
only when those physical differences preserve the pinned logical semantics,
isolation, durability acknowledgement, transaction mix, and validation.

River later receives a distinct binding for this profile. The River gate
requires the public path to support every statement and result shape used by
the next vertical slice. Any temporary River schema or query adaptation is a
machine-readable binding deviation and is never imposed on the MariaDB or
PostgreSQL binding. `tpcc-canonical` remains a future independently reviewed
profile; no result is labeled canonical merely because one DBMS can express
the physical schema naturally.

### 9.2 Data generation and loading

The generator is a pure function of suite version, profile, seed, table, and
row sequence. It streams rows through caller-owned scratch and retains no
cardinality-sized collection. A noncanonical tiny scale supports CI and local
development. Specification-scale warehouse configurations remain distinct and
are never inferred from a tiny run.

Loading occurs in deterministic table order with bounded transactions and
parallelism. Each load artifact records logical rows, committed rows, bytes,
per-table SHA-256, duration, failures, retries, and DBMS-specific batching.
Load verification checks table cardinalities, key ranges, foreign-key
relationships, uniqueness, and required initial business invariants before a
run may begin.

### 9.3 Transaction delivery order

Transactions are added in this order:

1. New-Order, including its intentional rollback boundary;
2. Payment, including customer-by-ID and customer-by-name paths;
3. Order-Status;
4. Delivery; and
5. Stock-Level.

Each transaction first passes deterministic single-session success and
material failure tests. It then passes concurrent conflict/retry tests, DBMS
adapter integration, post-transaction invariant checks, and allocation/profile
review before entering the mixed workload.

The mixed driver uses the pinned upstream selection and input rules for its
declared specification version. Actor-local generators make the logical input
stream reproducible across DBMS systems. Actual interleavings and retry timing
are outcomes and are not forced to match.

### 9.4 Verification

Verification has four layers:

- load verification before warmup;
- sampled operation-result verification during warmup and measurement;
- full bounded relational invariants after a run; and
- crash/restart verification for durability runs.

An operation is not successful merely because commit returned success. The
binding validates material result values and affected-row counts. Unknown
outcomes are not retried blindly; the suite uses an idempotency or outcome
lookup only where the declared DBMS/profile supplies one.

## 10. Generalization path

The first additional suite is YCSB because its read, update, insert, scan, and
read-modify-write shapes provide a small independent proof that the core did
not absorb TPC-C concepts. A later River binding remains a storage/API
diagnostic and is not presented as relational coverage.

Later suites enter only when the selected DBMS and harness meet their immediate
requirements:

| Suite | Execution shape | Harness capability exercised | Entry condition |
| --- | --- | --- | --- |
| YCSB | Key/value actors with configurable mixes and distributions | Generic operations, skew, scans, open/closed loop | TPC-C core stable; pinned upstream properties and expected semantics reviewed |
| TPC-H | Generated relational data and analytical query phases | Bulk load, complete-result timing, power/throughput phases, large scans | Required SQL/query set and result validation available |
| TPC-E | Stateful multi-table OLTP with market/customer actors | Multiple actor roles, richer transactions, temporal inputs | TPC-C concurrency and recovery evidence stable; target SQL surface sufficient |
| TPCx-IoT | Sustained ingestion with concurrent query/processing phases | Dual-plane actors, time-series ingestion, long-duration reporting | Required ingestion and query model supported; storage-scale runner available |

Every suite pins its upstream specification/tool version, checksum, source,
license/provenance, generator, profile, and deviations. No result inherits the
comparability or name of another implementation merely because the logical
suite name matches.

## 11. Delivery milestones

### RH0: plan, repository, and terminology acceptance

Outcome: approve this separate-repository boundary, Go implementation choice,
DBMS adapter contract, reporting contract, TPC fair-use policy, and division
from `river-bench`, plus the model/effort policy in the agent-mix plan.

Exit evidence:

- architecture, boundary/security, relational-semantics, and performance
  reviews have no blocker;
- the `river-harness` repository is created only after the plan is accepted;
- the selected MariaDB and PostgreSQL Go driver versions and the Homebrew
  MariaDB lifecycle profile are recorded for RH1/RH2; and
- no empty future-suite package is created.

### RH1: MariaDB lifecycle and end-to-end smoke

Outcome: one standalone command explicitly starts the installed Homebrew
MariaDB against the configured existing data directory, waits for its private
socket, opens a session, creates an owned smoke database/table, inserts typed
rows in a transaction, streams and validates a query, rolls back a second
mutation, closes cleanly, stops only the server it started, and publishes a
verified artifact.

Exit evidence:

- no command invokes `brew services`, and service state remains unregistered;
- start, readiness timeout, failed start, stale socket/PID refusal, graceful
  shutdown, and cleanup-after-test-failure paths pass;
- the existing `/opt/homebrew/var/mysql` is neither initialized nor reset, and
  deletion tests prove cleanup is restricted to a marked harness database;
- MariaDB connection, transaction, cancellation, result, and close bounds are
  tested through the production driver;
- the artifact survives independent schema/checksum validation; and
- warmed request/response allocation and copy measurements are recorded.

### RH2: cross-DBMS adapter proof

Outcome: the same smoke suite runs through a PostgreSQL binding and adapter,
then `compare` reads both artifacts and either produces a valid diagnostic
comparison or exact incompatibility reasons.

Exit evidence:

- DBMS adapter contract tests pass unchanged for MariaDB and PostgreSQL;
- suite and core packages have no imports of either concrete adapter;
- MariaDB-specific driver/status/types occur only under the MariaDB adapter and
  MariaDB bindings;
- connection configuration and credentials occur only under their adapter and
  are absent from published artifacts/logs; and
- comparison rejection tests cover isolation, durability, schema, seed, host,
  cache, and incomplete-artifact mismatches.

### RH3: deterministic TPC-C load

Outcome: `load --suite tpcc` creates and verifies the tiny derived profile
through MariaDB and PostgreSQL using the same logical seed and profile.

Exit evidence:

- deterministic golden fixtures and table digests pass;
- malformed configuration and arithmetic overflow fail before mutation;
- memory remains bounded as scale grows;
- interrupted/incomplete loads cannot be mistaken for verified databases; and
- schema and adaptation deviations appear in both artifacts.

### RH4: New-Order vertical slice

Outcome: closed-loop New-Order executes through the real MariaDB transaction,
storage, log, driver, and result path with success, intentional rollback,
conflict, retry, cancellation, and unknown-outcome accounting.

Exit evidence:

- deterministic single-session and concurrent semantic tests pass;
- material post-transaction invariants pass;
- retry count and transaction latency include every attempt and backoff;
- service histograms and committed throughput are published; and
- an independent transaction/correctness review approves the slice.

### RH5: complete TPC-C-derived mix

Outcome: all five transactions run in their pinned mix with per-operation and
aggregate reporting.

Exit evidence:

- every transaction's success and material failure paths pass on MariaDB;
- the same logical tiny profile passes on the reference DBMS or reports a
  reviewed binding deviation;
- post-run invariants detect seeded corruption and implementation defects;
- sustained conflict, saturation, checkpoint, and vacuum interaction are
  visible; and
- the result carries the required derived-work disclaimer.

### RH6: open-loop and external reporting

Outcome: bounded open-loop admission, Bencher projection,
Prometheus/OpenMetrics, OTLP, and a provisioned Grafana dashboard operate from
the same canonical metrics model.

Exit evidence:

- coordinated-omission fixtures prove intended-schedule timing;
- exporter saturation cannot block workers and reports drops;
- Bencher values reconcile exactly with canonical summary values;
- dashboard queries use bounded label sets; and
- enabled/disabled exporter overhead is measured.

### RH7: recovery, repeated samples, and comparison protocol

Outcome: the lifecycle boundary proven in RH1 runs repeated same-host
baseline/candidate and cross-DBMS samples with an explicitly harness-owned
data directory or reviewed restore/regenerate procedure, restart, and recovery
verification.

Exit evidence:

- lifecycle targets and commands are explicitly owned and validated;
- unknown commit outcomes and restart boundaries are preserved;
- `A B B A` repeated-run artifacts, raw histograms, confidence intervals, and
  outlier analysis are produced; and
- P05 promotion remains a separate independent performance-review decision.

### RH8: second workload proof

Outcome: a pinned YCSB core profile uses the existing DBMS adapter, runtime,
metrics, artifact, and comparison contracts without adding TPC-C conditionals
to core.

Exit evidence:

- upstream operation mix and distributions have deterministic fixtures;
- MariaDB and PostgreSQL pass the same logical smoke;
- point/range diagnostics are labeled as YCSB rather than relational evidence;
  and
- architecture review confirms the suite boundary before TPC-H work begins.

## 12. Test, build, and release strategy

Fast checks in the harness repository are:

```sh
go test ./...
go vet ./...
go test -race ./...
```

The race run may be a separate CI job because it changes timing. Focused Go
package tests run during editing. The local MariaDB integration job starts and
stops the server explicitly and serializes all tests that share the Homebrew
data directory. Later River integration uses the narrowest Gradle task needed
to assemble/start the real server and never overlaps another Gradle build in a
shared River checkout.

Required test families are:

- configuration/schema and boundary fuzz tests;
- deterministic generator and operation-stream golden tests;
- DBMS adapter contract tests shared by all adapters;
- suite/binding semantic tests with a bounded fake session;
- MariaDB lifecycle ownership, adapter, and binding tests;
- real MariaDB and PostgreSQL integration smoke tests;
- later River protocol golden, malformed, truncated, lifecycle, and real-server
  tests after the River gate opens;
- transaction success, rollback, conflict, cancellation, and unknown-outcome
  tests;
- artifact atomicity, no-clobber, checksum, and incomplete-run tests;
- comparison compatibility/rejection tests;
- open-loop coordinated-omission and saturation tests;
- allocation, CPU, and client-bottleneck profiles; and
- crash/restart verification at RH7.

Shared CI gates correctness and bounded smoke behavior, not noisy timing.
Canonical performance evidence runs on the declared physical host with raw
artifacts and independent review.

Harness releases include checksums, source revision, supported suite/profile
versions, report schema versions, and DBMS adapter/protocol compatibility. A
River integration CI workflow pins the harness release or commit; a harness CI
workflow pins the River release or commit. Neither follows an unversioned
default branch for evidence.

## 13. Review ownership

The lead integrator owns the complete RH slice and the contracts between core,
suite, binding, adapter, and reporting.

The current model, effort, rotation, and xhigh-escalation assignments are in the
[River Harness Agent Mix](river-harness-agent-mix.md). They are operational
choices reviewed at RH1/RH2, not durable harness interfaces.

Required lenses are:

| Area | Builder responsibility | Independent review |
| --- | --- | --- |
| Core scheduler and metrics | Runtime/performance | Performance/allocation and correctness adversary |
| MariaDB DBMS adapter and lifecycle | Boundary/operations | Boundary/security, process ownership, and compatibility |
| Later River DBMS adapter | Boundary/operations | Boundary/security and compatibility |
| TPC-C semantics/bindings | Relational execution | Relational semantics and transaction correctness |
| Artifact publication | Boundary/operations | Correctness adversary and operations |
| Recovery controller | Storage/recovery plus boundary | Correctness adversary and operations |
| Cross-DBMS comparison | Lead integrator/performance | Architecture and performance methodology |

Durable, recovery, concurrency, security, and comparison claims do not pass on
author self-review alone.

## 14. Risks and controls

| Risk | Control |
| --- | --- |
| Manual MariaDB lifecycle stops or corrupts unrelated state | Adopt no existing process; private socket/PID/log; exact executable/datadir checks; graceful authenticated shutdown; never initialize/reset the Homebrew data directory |
| Homebrew option files silently change a run | Controlled profile puts `--no-defaults` first and records every effective non-secret argument |
| `mariadbd-safe` hides a crash by restarting | Controlled profile uses `--no-auto-restart`; crash and PID disappearance are run outcomes |
| River protocol churn before V1 | Fail on version mismatch; change fixtures and adapter directly; no compatibility shim |
| Cross-repository versions drift | Pin both revisions in CI and artifacts; publish supported protocol versions |
| Generic adapter contract becomes lowest-common-denominator SQL | Keep SQL and operation execution in explicit suite/DBMS bindings |
| Client becomes the bottleneck | Measure client CPU, allocation, queues, bytes, and offered/actual rate; separate client CPUs on canonical runs |
| Cross-DBMS results appear comparable when semantics differ | Machine-enforced compatibility fields and `NOT_COMPARABLE` default |
| TPC naming or primary metrics are misused | Versioned deviations and required fair-use disclaimer in every derived report |
| Telemetry perturbs the result | Disabled by default for canonical timing; bounded snapshots, drop counters, overhead A/B |
| Per-operation allocation or logging distorts latency | Reusable worker state, bounded abnormal events, allocation profiles |
| Load generation exhausts memory or disk | Streaming generation, checked scale arithmetic, declared byte bounds and preflight |
| Automated reset damages unrelated data | Existing Homebrew data is never reset; later reset is limited to explicit harness-owned paths and non-shell argument arrays |
| A second DBMS adds target conditionals to suites | Suite/DBMS binding boundary and import-policy tests |

## 15. Provenance and references

Pin exact versions and checksums in each implemented suite rather than relying
on the versions current when this plan was written.

- [TPC current specifications](https://www.tpc.org/TPC_Documents_Current_Versions/current_specifications5.asp)
- [TPC fair-use quick reference](https://www.tpc.org/TPC_Documents_Current_Versions/pdf/Fair_Use_Quick_Reference_v1.0.0.pdf)
- [YCSB source and workload documentation](https://github.com/brianfrankcooper/YCSB)
- [MariaDB `mariadbd-safe` manual startup and options](https://mariadb.com/docs/server/server-management/starting-and-stopping-mariadb/mariadbd-safe)
- [MariaDB `mariadb-admin` ping and shutdown commands](https://mariadb.com/docs/server/clients-and-utilities/administrative-tools/mariadb-admin)
- [`go-sql-driver/mysql` MariaDB support and Unix-socket configuration](https://github.com/go-sql-driver/mysql)
- [OpenTelemetry OTLP metrics exporter specification](https://opentelemetry.io/docs/specs/otel/metrics/sdk_exporters/otlp/)
- [Prometheus native histogram specification](https://prometheus.io/docs/specs/native_histograms/)
- [Grafana Prometheus data source](https://grafana.com/docs/grafana/latest/datasources/prometheus/)
- [Bencher Metric Format](https://bencher.dev/docs/reference/bencher-metric-format/)
