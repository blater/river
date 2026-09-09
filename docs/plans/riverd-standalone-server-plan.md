# River standalone server (`riverd`) plan

Status: Accepted contract; ratified by
[`ADR 0014`](../adr/0014-riverd-instance-security.md) after independent
acceptance of exact `tic-11a5` candidate
`a7ca80184fb91141c2d4266e9a28dd3d53cc1c2e`

The [CLI contract](../riverd-cli.md) owns user-facing commands and behavior.
ADR 0014 owns status mappings, formats, security and recovery mechanisms.
Both prevail over summaries in this implementation plan. The earlier
`tic-a221` audit design is retained as superseded historical evidence; it is not
an active prerequisite or state machine. Acceptance ratifies documentation only: production code,
build, filesystem qualification, recovery, security, and operational evidence
remain required at the named downstream tickets.

## 1. Objective

Provide one supported, command-line River server distribution that owns the
embedded database and loopback listener lifecycle. External tools must be able
to start River without invoking Gradle, constructing a Java classpath, naming a
Java main class, or importing River implementation packages.

The installed command is `riverd`. `riverd start` runs in the foreground and
prints its resolved paths and endpoint when ready. `riverd stop` addresses the
same data directory and publishes an owner-nonce-bound cooperative request to
the lock-owning server; the CLI never signals by PID. `riverd ps` lists the live instances recorded by
the current user's `riverd` processes.

The first immediate consumer is `river-harness`. After `riverd` is accepted,
the harness will start the installed executable, record the child PID, wait for
the documented readiness output, and stop only that child. River-specific
build and classpath assembly will then be deleted from the harness.

## 2. Module decision

Create a production application module named `river-server-app`.
`tic-615d` creates it only when real identity, credential, filesystem, and
client-configuration production code and tests land. `tic-ec50` then configures
its Gradle application distribution to install the executable `riverd` and
wires the complete composition. The module is never empty scaffolding.

Do not place the launcher in `river-server`.

`river-server` is a reusable transport adapter. It accepts a public
`RiverDatabase`, owns listener and connection behaviour, and should remain
independent of selection and construction of the concrete embedded engine. A
command-line launcher is the composition root: it parses operator input,
selects `EmbeddedRiver`, creates or opens persistent state, starts
`LoopbackRiverServer`, formats process output, and coordinates shutdown. Adding
those responsibilities and a concrete `river-engine` dependency to
`river-server` would weaken the existing boundary.

`river-server-app` therefore has only the production dependencies it uses:

- `river-base` for stable status and database identity values;
- `river-engine` for the embedded implementation and resource-plan input;
- `river-engine-api` for the database lifecycle contract;
- `river-server` for the loopback listener;
- `river-protocol` for native protocol version/constants; and
- the aligned dependency-verified Bouncy Castle PKIX set for certificate
  construction.

The module is not speculative: its first change contains the immediate
identity/security consumer. Add it to River's production-module and
dependency-policy lists then; add application/distribution policy in
`tic-ec50` with the real command.

## 3. Component and process diagrams

### 3.1 Component boundary

```mermaid
flowchart TD
  caller["Operator or river-harness"]
  client["SQL client<br/>River v4 initially<br/>PostgreSQL driver later"]

  subgraph distribution["Installed riverd distribution"]
    app["river-server-app<br/>CLI and lifecycle composition"]
    engine["river-engine<br/>EmbeddedRiver"]
    server["river-server<br/>LoopbackRiverServer"]
    api["river-engine-api<br/>RiverDatabase"]
    protocol["river-protocol<br/>versioned wire contract"]

    app -->|"create / open / close"| engine
    app -->|"start / close"| server
    app -->|"read protocol version"| protocol
    engine -->|"implements"| api
    server -->|"uses"| api
    server -->|"frames"| protocol
  end

  home["Instance data directory (-D)<br/>identity + lock + database/"]
  runtime["Per-user runtime directory<br/>one bounded record per ready instance"]

  caller -->|"exec riverd start, stop, or ps"| app
  app -->|"exclusive ownership"| home
  app -->|"publish / list / remove"| runtime
  app -->|"ready key=value records"| caller
  client <-->|"loopback TCP"| server
```

The executable distribution is the public process boundary. Neither the
operator nor `river-harness` sees module JARs, Java class names, Gradle source
sets, or concrete engine objects. `river-server-app` is the only component that
knows both the concrete embedded engine and the reusable server library.

### 3.2 Start, serve, and stop sequence

```mermaid
sequenceDiagram
  participant C as Operator / harness
  participant D as riverd
  participant T as riverd stop
  participant H as Instance data directory
  participant R as Per-user runtime directory
  participant E as EmbeddedRiver
  participant S as LoopbackRiverServer
  participant Q as SQL client

  C->>D: exec riverd start with optional overrides
  D->>D: parse and validate all arguments
  D->>H: resolve paths and acquire exclusive lock
  alt first start
    D->>H: force bootstrap record
    D->>E: create(data, in-memory identity, generation)
    D->>H: force initial security
    D->>H: atomically publish instance identity last
  else existing instance
    D->>H: validate stored identity
    D->>E: openExisting(data, identity, generation)
  end
  E-->>D: RiverDatabase or native failure status
  D->>S: start(database, requested port)
  S-->>D: selected loopback port
  D->>H: atomically publish selected client configuration
  D->>H: atomically publish current runtime record
  D->>R: atomically publish runtime record
  D-->>C: commit ready file or final stdout ready record
  Q->>S: connect and execute requests
  S->>E: execute through RiverDatabase
  E-->>S: results and stable status
  S-->>Q: protocol responses
  C->>T: exec riverd stop -D same-instance
  T->>H: validate lock/runtime identity and publish stop.request
  H-->>D: consume matching owner-nonce request
  D->>S: close listener and active connections
  S-->>D: server close status
  D->>E: close database
  E-->>D: database close status
  D->>R: remove this instance's runtime record
  D->>H: release instance lock
  D-->>T: lock/owned records disappear with verified outcome
  T-->>C: report stopped or clear failure
```

Arguments and path-tree collisions are fully validated before the first
filesystem mutation. Shutdown always proceeds listener first, database second.
The stop command performs the ADR's bounded scan through the platform adapter of the fixed request,
nonce-stage, and accepted-receipt control names, then validates the bounded
runtime/lock identity before a new publication. It never searches arbitrary
processes and never terminates or otherwise signals a PID.

## 4. Command contract

### 4.1 Commands

Use the [riverd CLI contract](../riverd-cli.md) for command grammar, help,
instance selection, defaults and user-facing operation. The following sections
explain implementation and integration; they do not define another CLI.

### 4.2 Start options and defaults

```text
-D PATH, --datadir=PATH     default: $HOME/.river/default
--port=PORT                default: 9191; zero selects an available port
--ip=ADDRESS               optional; default: 127.0.0.1
--maximum-connections=N     default: 16
--ready-file=PATH           optional; no default file
```

`N` is canonical decimal `1..2147483647`. Out-of-width input is
`INVALID_EXTERNAL_INPUT`; a valid count that the one resource profile cannot
admit is `RESOURCE_EXHAUSTED` before mutation.
`DURATION` is a positive canonical decimal plus `ms`, `s`, or `m`; checked
conversion must fit a positive signed-long millisecond value. Fractional,
signed, whitespace, unitless, zero, and overflow values are invalid.

`riverd stop` accepts the same `-D PATH` or `--datadir=PATH` option and:

```text
--timeout=DURATION          default: 30s
```

Both offline recovery commands accept only the same `-D`/`--datadir` option.

The resolved instance layout is:

```text
DATADIR/
  instance.properties      launcher-owned database identity and format
  instance.lock            exclusive live-process lock
  stop.request             nonce-bound cooperative stop; normally absent
  bootstrap.properties     first-create recovery record; normally absent
  .instance-<nonce>.stage  same-parent first-create authority stage; normally absent
  database/                River-owned embedded database directory
  security/                owner-only bundles/manifest; .security-<nonce>.stage is transient
```

`-D` identifies the complete River instance. The embedded engine owns the
`database/` child while `riverd` owns the small lifecycle files at the instance
root. Relative paths are resolved against the launcher's initial working
directory and printed as normalized absolute paths before readiness.

The first server supports authenticated TLS loopback only. `--port` accepts
decimal `0..65535`. Optional `--ip` accepts `127.0.0.1` or `::1`, without IPv6
brackets. Reject wildcard, non-loopback, hostname, malformed and out-of-range
inputs before creating or opening anything. Port zero selects an available
port, which is reported after binding. Ordinary use needs only `--port`.

The server never requests elevated permissions, invokes a service manager, or
writes outside the resolved data directory except the fixed per-user runtime
directory and an explicitly supplied `--ready-file`.

`riverd` has no insecure, trust, no-authentication, or TLS-disable option and no
fallback to a plain listener. The owning OS account controls lifecycle and
credential-file access; the credential authenticates a TCP client as River's
single configured service principal. Other processes running as that account
are inside the trusted boundary. Other host accounts may reach the port but
cannot authenticate.

Every launcher persistent property record listed in ADR 0014 ends with
`record-sha256`: SHA-256 covers the exact canonical UTF-8 bytes through the LF
immediately before that final field and excludes the checksum line. Bounded
canonical read and checksum verification precede every field use. This applies
to bootstrap, instance, security, client, runtime, lock, stop request, ready
file, renewal intent, and public-archive manifests; stdout is not such a
record.

The required platforms and filesystem guarantees are defined in
[ADR 0014](../adr/0014-riverd-instance-security.md#required-platforms-and-filesystem-guarantees):
macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS. Each must support the installed
server and authenticated JDBC lifecycle. These are requirements, not completed
platform qualification.

River owns one portable contract for exclusive instance ownership, effective
owner-only permissions, stable object identity, atomic publication, durable
namespace changes, and recovery. Platform adapters implement those operations;
Java APIs may be supplemented with native calls where required. POSIX modes,
SecureDirectoryStream, hard links, and Java directory-force calls are not
universal requirements. No platform adapter may weaken durability or credentials.
The same adapter contract covers the instance, runtime, and ready-file trees.
Bootstrap binds `.instance-<nonce>.stage` in `DATADIR`, and renewal binds
`.security-<nonce>.stage` in `security/`; platform operations preserve those
state machines and their safe recovery rules.

Path handling includes Windows drive paths, separators, case aliases, and
reparse points. The per-user home is resolved on each platform. Normal Windows
console shutdown and the cooperative stop command share the lifecycle owner
used by Unix signals; forced termination follows crash recovery.

`tic-95e8` tests the installed distribution's filesystem/security and process-
crash behavior on each platform; `tic-9640` qualifies operational and power-loss
recovery, including the actual database durable-I/O provider. Runtime checks
verify available capabilities and effective permissions; they cannot certify
power-loss durability. Evidence follows the tested adapter operations and
relevant platform dependencies. An unrelated launcher/JAR change does not
require a new full power-loss campaign or a machine-specific runtime manifest.

Reject missing capabilities before dependent mutation, preserve ambiguous or
corrupt state, and keep the ADR's collision and properties-format rules. A
required platform cannot be delivered by permanently returning unsupported.

### 4.3 Security bootstrap and client discovery

On first creation, `riverd` generates the exact ADR 0014 credential generation:
a raw 32-byte token, principal 1 with `SessionPermissions.ALL`, and a 365-day
self-signed X.509 v3 P-256/SHA-256 leaf with the required constraints, usages,
and three loopback SANs. It creates one local `BouncyCastleProvider` object and
passes that object to EC `KeyPairGenerator`, every content signer/converter,
`CertificateFactory`, and certificate-signature verification operation;
`tic-615d` pins and dependency-verifies one aligned Bouncy Castle set. No
global install, provider-name lookup, JDK-internal certificate class, external
`keytool`, or second DER encoder is permitted.

Generation directories hold an exact 32-byte token, bounded PKCS#8 key, and DER
certificate. A forced
`security.properties` is the credential authority and binds the instance,
generation, principal, permissions, algorithms, validity, fixed names, and
SHA-256 digests. Restart applies the ADR's no-follow type/owner/mode/size,
canonical-field, final-record SHA-256, incarnation, digest, key/certificate, signature, algorithm,
constraint, SAN, validity, principal, and permission checks before admission.
Missing or mismatched accepted material is preserved as `CORRUPTION`; a
well-formed generation outside its validity interval is `ACCESS_DENIED` on
start.
The token digest is the `TokenProof` HMAC key and is credential-equivalent;
raw token, private-key, verifier, complete security-manifest, caller scratch,
and authenticator buffers have the ADR's exact River-owned zeroing lifecycle;
JSSE state has only the specified public-session cleanup and reference clearing. The
idempotent `TokenAuthenticator` is destroyed only after workers stop and the
listener closes, public sessions are enumerated/invalidated, and River clears
its JSSE references. Only River-owned buffers and a successfully destroyed
Bouncy Castle key are guaranteed zeroed; key destroy false/throw and public
session cleanup failure are `IO_FAILURE`, while provider-owned opaque/session/
ticket erasure is not claimed.

After the listener selects its concrete port, `river-server-app` atomically
replaces and directory-forces `security/client.properties`, the exact
`riverd-client-v1` file defined by ADR 0014. It is discovery state, not restart
authority, and may be stale while stopped. `river-client` owns its only parser,
validation, pinned TLS 1.3 connector, hostname check, and credential erasure.
JDBC and CLI delegate to that owner.
The file carries incarnation, credential generation, principal, transport,
protocol, selected host/port, certificate path/digest, and token path. It never
carries a token or private key value. Advanced clients accept file paths, never
secret argv, environment, URL, readiness, runtime, or log values.

`riverd credentials renew -D PATH` is the only credential-validity recovery operation. It
validates the old generation except for current-time validity, archives and forces only the
prior public certificate plus redacted public manifest under the ADR's exact
content-identified name, exclusively publishes and forces `renewal.intent`
before creating its exact nonce namespace, creates and forces generation plus
one, and atomically publishes the new security authority.
It durably unlinks old secrets afterward and recovers deterministically on
either side of that authority switch. Generation overflow is
`RESOURCE_EXHAUSTED`. It has no overlap generation or implicit repair. A client
with already loaded old material is `ACCESS_DENIED`; a reload whose old secret
path is gone is `IO_FAILURE`.

On every authentication and statement admission, a wall clock earlier than
`notBefore` or at/after `notAfter` permanently closes the shared validity fence
and the listener rejects new connections. A resumed
transport still performs application authentication, and every later request
on a loaded session returns `ACCESS_DENIED`. Only work admitted before the
fence may complete or follow normal cancel/rollback. Ordered shutdown
releases listener, workers, and JSSE before destroying authentication material,
then closes the database and exits 1 `ACCESS_DENIED`; restart cannot become
ready outside the validity interval. `tic-b901` proves exactly one
`System.currentTimeMillis` and fence read per authentication/statement,
zero warmed River allocation, and matched 1/4/16-client cost evidence.

### 4.4 Multiple instances

One resolved `-D` directory identifies exactly one `riverd` instance. Multiple
servers run independently by using different data directories and either
distinct ports or port zero:

```sh
riverd start -D "$HOME/.river/benchmark-a" --port=0
riverd start -D "$HOME/.river/benchmark-b" --port=0

riverd stop -D "$HOME/.river/benchmark-a"
riverd stop -D "$HOME/.river/benchmark-b"
```

Each directory has its own database, identity, lock, and PID record. A second
start against an already locked directory fails clearly without affecting the
existing server. A bind collision between different directories also fails
without stopping either instance. The selected endpoint printed by each
successful start is the source of truth when port zero is used.

`riverd stop` always identifies one data directory. There is no process scan,
implicit "most recent" instance, `stop --all`, or fallback to the default when
an explicit `-D` is invalid. With no `-D` or `--datadir`, start and stop refer
only to the default instance at `$HOME/.river/default`.

### 4.5 Process listing

Ready instances publish one bounded runtime record beneath the fixed per-user
runtime directory `$HOME/.river/run`. The record filename is the SHA-256 digest
of the normalized absolute `-D` path followed by `.properties`, so a
data-directory value never becomes an unchecked path component. Each record
contains the data directory, incarnation, PID, process start instant, listen
endpoint, client configuration, credential generation, ready target, and owner
nonce.

The record is published atomically after the listener is ready and before
`riverd_status=ready` is printed. If it cannot be published, startup fails and
closes the listener and database. Orderly shutdown removes only the record
whose full contents match the running instance.

`riverd ps`:

1. read only small regular files directly beneath the fixed runtime directory;
2. validate every bounded record;
3. validate the corresponding datadir lock and owner identity;
4. print live instances sorted by normalized data directory.

Example:

```text
PID    LISTEN           DATADIR
24102  127.0.0.1:9191   /Users/example/.river/default
24157  127.0.0.1:52144  /Users/example/.river/benchmark-a
```

The listing never scans the operating-system process table for unrecorded River
processes and never signals a process. Inactive records are skipped; malformed
or unreadable records produce a concise warning and remain available for
diagnosis. The `ps` command is otherwise side-effect free. Only a later
`start`, under the matching instance lock and after proving the exact recorded
process absent, may replace a canonical stale same-datadir record; malformed or
mismatched collisions are preserved and fail.

When there are no verified live records, exit successfully and print:

```text
No River instances are running.
Start one with:
  riverd start [-D PATH] [--port=PORT] [--ip=ADDRESS]
```

### 4.6 Startup output

Successful startup prepares stable UTF-8 `key=value` records for its one
selected readiness sink:

```text
riverd_datadir=/absolute/path
riverd_data=/absolute/path/database
riverd_identity=/absolute/path/instance.properties
riverd_runtime_file=/absolute/path/.river/run/<sha256-datadir>.properties
riverd_listen_address=127.0.0.1
riverd_listen_port=9191
riverd_pid=12345
riverd_protocol=river-v4
riverd_transport=tls-v1.3
riverd_client_config=/absolute/path/security/client.properties
riverd_server_certificate_sha256=...
riverd_status=ready
```

The exact protocol value must come from the protocol-owning module rather than
being copied into launcher code. Human diagnostics and failures go to standard
error. Paths or diagnostics that can contain line breaks or `=` must be
rejected before mutation; version 1 has no encoding alternative.

The output and ready file never contain token bytes, private-key material, or a
credential supplied by value.

When `--ready-file` is supplied, force its checksummed canonical ready-file
record and atomically publish it without overwrite only after the listener,
client configuration and runtime record are ready. That publication is the
sole readiness commit; stdout afterward is a best-effort mirror, and a broken
pipe or flush failure never retracts or stops the ready service. Without a ready
file, all stdout prefix records are flushed first and the consumer's observation
of the complete final LF-terminated `riverd_status=ready` record is the sole
external commit. A fully observed record is never retracted when a later flush
fails. If target/parent/source force fails after ready-file visibility, or a
stdout-only final transfer/flush fails ambiguously after visibility, the
terminal outcome is `IO_FAILURE`: ordered cleanup runs and the process
eventually exits 1. Provably pre-commit prefix/partial-record failure performs
the ADR's exact reverse cleanup and emits no later ready record.

`riverd version` prints the exact ADR 0014 keys for River release version,
`riverd-v1`, `river-v4`, and `riverd_status=OK`. The distribution manifest is
the source of the release version; it must not inspect Git or the source
checkout at runtime. Successful stop prints only its normalized datadir,
verified PID, and `riverd_status=OK` after process exit and matching-record
removal.

### 4.7 Stop contract

After acquiring the instance lock, `riverd start` atomically writes
the hashed runtime record. The bounded record contains the PID, process start
instant, resolved data directory, endpoint,
client configuration, credential generation, explicit ready target or `none`,
and a random nonzero 128-bit owner nonce. The forced lock record contains the same process identity,
datadir, incarnation, and nonce. Unavailable process start evidence is
`FEATURE_NOT_SUPPORTED`.

`riverd stop` never signals a PID, calls `ProcessHandle.destroy`, or acquires or
steals the live lock. Before creating anything it scans pending requests,
request stages, and accepted receipts. A single matching pending or accepted
receipt is an idempotent join, including after runtime removal; only when none
exists does it match the checksummed runtime/lock identity and contended lock,
then force and atomically publish the ADR's incarnation-, runtime-checksum-, and
owner-nonce-bound `stop.request`. The lock-owning
server validates and atomically renames the request to its exact accepted
receipt before invoking the same lifecycle owner as a directly delivered
platform shutdown (Unix SIGINT/SIGTERM or Windows console shutdown). PID/start are evidence and display fields only.

Exact retries and concurrent CLIs join the one valid pending/accepted request
for the same owner/runtime; contenders remove only their own unpublished
stages. A mismatched request is `NOT_OWNER`/`CORRUPTION`. Timeout atomically
removes only its unchanged unaccepted request or
loses to server acceptance; it never cancels accepted shutdown and never
escalates. Exit or PID reuse between validation and publication fails the
post-publication scan/revalidation; a matching accepted receipt joins even if
runtime is gone, while a still-pending request for a changed/absent owner is
removed by identity and returns `NOT_OWNER`. The server retains the accepted
receipt through listener/worker/JSSE/authenticator/database shutdown and
matching ready/runtime removal, then removes/forces the receipt
immediately before lock release. New startup under the lock removes only
validated stale requests or receipts bound to an absent prior owner. Stop
succeeds only after the lock and all matching runtime/readiness/
request/receipt records are gone.

The running process removes only its own runtime/control records during orderly shutdown.
On startup, a stale record may be replaced only after the instance lock has
been acquired and the recorded process has been proved absent. The data and
identity files are never removed by either lifecycle command.

### 4.8 Deferred SQL/security audit

SQL/security audit collection and an audit archive command are outside the
current `riverd` contract and immediate roadmap. The former `tic-a221`,
`tic-72ea`, and archive designs remain historical evidence only; no placeholder
state, archive format, or near-term audit study is planned. Reconsider this
area only after a concrete design supports neutral TPS, latency, and resource
impact and matched evidence can demonstrate that performance property.

## 5. Persistent identity, security, and open/create behaviour

The launcher owns a small, versioned `instance.properties` file containing:

```text
format=riverd-instance-v1
database-incarnation-high=...
database-incarnation-low=...
initial-wal-generation=1
record-sha256=...
```

On the first start:

1. Parse and validate all arguments without mutation.
2. Resolve the secure parent, create or validate owner-only `DATADIR`, and
   acquire its exclusive `instance.lock` through no-follow handles.
3. Classify authority under the exact file lock. For a genuinely new tree,
   generate the incarnation/attempt nonce and replace a torn/stale lock record
   only when no authority, stage, fixed child, or other entry exists; force the
   new lock record and `DATADIR`. With an existing bootstrap/instance, use only
   its identity and require absent old-process proof. Preserve every ambiguous
   case.
4. Require a new directory or the ADR's one canonical stale
   `riverd-bootstrap-v1` record and exact
   `.riverd-bootstrap-<attempt-nonce>` namespace with no extra entries;
   otherwise preserve and return `CONFLICT`.
5. Exclusively publish and force the bootstrap record with the selected
   non-zero 128-bit incarnation and `SecureRandom` attempt nonce, then force
   `DATADIR`.
6. Build only the two staged directories `database` and `security` under that
   exact namespace; force every file and directory required to
   reopen them. The instance stage is not inside the namespace.
7. Atomically publish `database`, then `security` without overwrite,
   forcing `DATADIR` after each. Write the bootstrap-bound
   `.instance-<nonce>.stage` directly in `DATADIR`, publish
   `instance.properties` last, and force `DATADIR`; that force is the single
   instance-authority transition. Remove only checksum/file-key-matching
   bootstrap residue after authoritative validation succeeds. Retry resumes the
   first incomplete ordered rename; source/destination duplication, extra
   state, or identity mismatch is preserved as `CONFLICT`/`CORRUPTION`.

On later starts, validate the identity file strictly and call
`EmbeddedRiver.openExisting` with its recorded values. Do not infer identity
by decoding River storage formats, rewrite damaged metadata, delete partial
state, or fall back from open to create. An inconsistent data directory fails
with a clear error that names the expected state and observed path.

The exclusive lock prevents two launchers from owning one instance data
directory. Lock contention is an ordinary startup failure; `riverd` does not
inspect or kill the competing process.

An existing ready file is removed only under the acquired instance lock when a
canonical stale runtime/lock record, the ready record, incarnation,
owner nonce, paths, checksums, and file keys all match and the recorded process
is proved absent. Force its external parent and the runtime parent
after removal. An unrelated or unverifiable ready target remains untouched and
returns `CONFLICT`/`CORRUPTION`/`NOT_OWNER`.

## 6. Runtime and shutdown

`riverd start` remains in the foreground and owns these resources in order:

1. secure directory handle and instance lock;
2. validated identity and credential authority;
3. opened `RiverDatabase`;
4. authenticated `LoopbackRiverServer`;
5. current client configuration;
6. matching runtime record;
7. one optional ready-file or stdout readiness commitment.

Register the JVM shutdown hook only after database ownership exists. On Unix SIGINT/SIGTERM or Windows console shutdown, close the listener first and the database second, reporting both
native `StatusCode` outcomes. The normal close path and shutdown hook must
share one idempotent lifecycle owner so each resource is closed at most once.

Startup failure closes all resources already acquired in reverse order. A
failed server close, database close, or incomplete shutdown is printed clearly
and must not be presented as a clean stop. The instance data directory and
database are never deleted automatically.

The harness records the PID returned by starting `riverd`, invokes `riverd
stop -D` against the same instance data directory, and waits for that child. It
does not need class inspection, Gradle state, or a PID search.

## 7. Resource configuration

The launcher must provide one explicit, documented development resource
profile compatible with `DatabaseResourcePlanRequest`. Keep compilation of
that request in one launcher-owned class; do not copy the TPC-C tool's numerous
resource flags into the public command.

The first CLI exposes only `--maximum-connections`. Derive the corresponding
maximum active transactions and the fixed resource profile once. Additional
memory tuning options wait for a concrete operational need and should be added
as a coherent resource profile rather than independent low-level engine knobs.

## 8. Production structure

Owned files (class names may change locally without changing their owner):

```text
river-server-app/
  build.gradle.kts
  src/main/java/io/riverdb/server/app/
    RiverDaemonMain.java        process entry and exit mapping
    RiverDaemonArguments.java   side-effect-free command decoding/help
    RiverDaemonInstance.java    create/open/start/close ownership
    RiverDaemonIdentity.java    bounded metadata read/write
    RiverDaemonCredentials.java exact credential generation/validation
    RiverDaemonFileSystem.java  portable permission/identity/durability contract
    RiverDaemonRuntimeRecords.java owned live-instance records and listing
    RiverDaemonOutput.java      stable startup/error records
    RiverDaemonResources.java   one resource-plan policy
  src/test/java/io/riverdb/server/app/
    ...focused tests...
```

Keep parsing, persistence, lifecycle, and rendering separate because they have
different failure boundaries. Do not introduce interfaces unless a genuine
provider boundary is needed for deterministic tests; package-private concrete
classes and injected narrow Java facilities are sufficient.

`tic-485d` owns the shared filesystem operations and APFS implementation;
`tic-867d` and `tic-b75d` add Linux and Windows adapters against that contract.
`tic-615d` owns identity, credentials, client configuration, and the non-empty
app module, consuming those platform operations. `tic-ec50` owns the command,
lifecycle, resources, output, and final runtime publication format,
application distribution, and complete caller
migration. It secures `TpccServerMain` and deletes every plain server/client
API in that same delivery. `tic-3f57` later preserves or replaces the already
authenticated benchmark launcher only after every accepted diagnostic remains
available. TPC-C diagnostics and performance capture remain in `river-bench`
and must not enter `riverd`.

`tic-b901` owns the running-validity fence and shutdown behavior together with
offline credential renewal, so neither validity boundary can diverge.

### Delivery scope controls

Each platform ticket implements the same operations for one OS; none owns
credential or lifecycle policy. `tic-615d` stops at validated instance creation
and restart. `tic-ec50` composes those results into the installed start/JDBC path
and migrates the existing callers without changing workload semantics. Stop/ps
and offline maintenance remain with their existing tickets. Validation tickets
consume existing tests and retain failures; they do not grow new tooling or
repair production code. The named ticket stop boundaries are delivery limits.
A newly discovered independent mechanism or defect needs a separate ticket,
and blocks only its actual consumer. No additional platform, service manager,
remote binding, or PostgreSQL protocol work is included in this delivery.

## 9. Build and distribution

Apply Gradle's `application` plugin in `river-server-app`:

```kotlin
application {
  applicationName = "riverd"
  mainClass.set("io.riverdb.server.app.RiverDaemonMain")
}
```

The supported developer build is:

```sh
./gradlew --no-daemon :river-server-app:installDist
river-server-app/build/install/riverd/bin/riverd --help
river-server-app/build/install/riverd/bin/riverd start
```

`assemble` must include the distribution through the normal root build. The
start scripts and distribution include one coherent dependency graph; external
consumers execute the installed script and never assemble their own classpath.

`tic-615d` adds the used identity/security module edges, settings entry,
production-module list, and dependency-policy fixtures. `tic-ec50` adds the
application/distribution/archive/reproducibility entries and removes the unused
`river-server -> river-engine` allowance while adding only used app edges. Do
not exempt the new module from existing build policy.

## 10. Test and acceptance plan

### 10.1 Focused argument tests

- brief and comprehensive help are distinct, deterministic, and side-effect
  free for every explicitly accepted global, command, group, nested-command,
  and `help ...` form; every unlisted placement/extra token is rejected;
- no arguments produce useful brief usage without runtime-directory access;
- `riverd help` and `riverd --help` produce the same full help;
- `ps` produces the deterministic instance listing;
- defaults resolve exactly as documented;
- `-D`/`--datadir`, `--port`/`--ip`, loopback IPv4/IPv6, explicit ports, and
  port zero parse;
- stop resolves the same default and overridden data directories as start;
- two different data directories can run concurrently on automatically
  selected ports;
- a second start against the same data directory fails on the instance lock
  without disturbing the first process;
- no live records prints the documented `riverd start` suggestion and exits
  successfully;
- non-loopback addresses, malformed ports, duplicate/conflicting options,
  control characters, `=` paths, unknown commands, and unknown options exit 2
  before filesystem mutation;
- equality, ancestor/descendant, symlink, file-key, and hard-link alias
  collisions among datadir fixed children, runtime directory, and ready target are
  rejected before mutation.

### 10.2 Identity and ownership tests

- first start creates a bounded versioned identity atomically;
- restart reads the same incarnation and opens existing data;
- an existing ready file is never overwritten;
- missing, oversized, duplicate, malformed, or unknown-required identity
  fields fail closed;
- a non-empty unowned data directory is rejected;
- every forced write/rename/directory-force interruption in the exact
  nonce-derived bootstrap namespace resumes only a checksum/file-key-bound
  next step; missing/invalid bootstrap, source/destination duplication, or any
  extra entry is preserved;
- instance authority publishes only from the bootstrap-bound
  `.instance-<nonce>.stage` beside its target, and recovery removes only that
  validated same-parent alias/residue;
- under-lock zero/torn/stale pre-bootstrap lock recovery is allowed only for an
  otherwise empty authority-free tree; bootstrap/instance identity and absent-
  process proofs govern every later stale lock;
- stale ready-file cleanup requires matching incarnation, owner, absent process,
  runtime/path/checksum/file-key identity and parent force; unrelated
  files are preserved;
- lock contention starts no listener and kills no process;
- a ready instance publishes one bounded runtime record and orderly shutdown
  removes only that matching record;
- listing ignores and warns about malformed, stale, or mismatched records
  without deleting them or signalling a process;
- cooperative stop accepts only a checksummed incarnation/runtime/owner-nonce
  request consumed by the lock-owning server and never signals a PID;
- stop covers concurrent/retry requests, timeout before/after acceptance,
  server exit and PID reuse between validation and request publication,
  prepublication pending/accepted join, late revalidation, receipt retention,
  request/accept crash residue, and never escalates or steals the lock;
- failures preserve the instance data directory for diagnosis and never delete
  data.

### 10.3 Security tests

- first creation publishes a complete incarnation-bound 256-bit-token and
  pinned-certificate bundle; restart reuses the exact accepted identity;
- partial first publication before `instance.properties` authority is safely
  recoverable, while missing, oversized, mismatched, expired, symlinked, special,
  wrong-owner, or group/world-readable accepted material fails closed;
- wrong token, wrong certificate, wrong hostname, replayed proof, and omitted
  authentication admit no session or statement;
- Java CLI/JDBC and the Go harness trust only the configured instance
  certificate and erase token/proof buffers;
- all launcher properties reject checksum-invalid bytes before using any field;
- local Bouncy Castle provider-object selection covers EC key generation,
  signer, converter, certificate parse, and signature verification; global
  provider state/name lookup is absent;
- token digest/security manifest are credential-equivalent; raw token,
  private-key, verifier, caller scratch, and authenticator follow ordered
  River-owned zero/destroy on success and failure; public JSSE session
  enumeration/invalidation and reference clearing are exercised without an
  opaque/session-ticket erasure claim, and provider-key destroy false/throw is
  `IO_FAILURE`;
- readiness, runtime, diagnostics, and process arguments contain no secret
  values;
- credential renewal covers both validity bounds, active and resumed TLS
  sessions, generation overflow, external intent before namespace creation,
  exact public-only archive and staging names,
  every pre/post-authority force/crash boundary, durable old-secret unlink,
  `ACCESS_DENIED` loaded-old versus `IO_FAILURE` missing reload, and no
  old-token overlap;
- macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS adapters pass shared
  permission, identity, locking, exclusive/replacement publication, cleanup,
  and recovery tests. Cover ACL inheritance, symlinks/reparse points, case and
  hard-link aliases, path races, failed flushes, Windows sharing/deletion
  behavior, and format bounds using the actual platform operations. Unsupported
  capabilities fail before dependent mutation. Runtime probes are not accepted
  as power-loss evidence; qualification reuse requires a scoped impact review;
- a source and compiled-code check proves that production contains no plain
  listener/client fallback.

### 10.4 Real lifecycle tests

- install the real distribution, start `riverd` on port zero, parse readiness,
  connect through authenticated TLS using the published client configuration,
  execute create/insert/select, run `riverd stop`
  for the same `-D` directory, and require verified listener-then-database
  shutdown;
- restart the same data directory and read the committed row;
- start a second data directory independently to prove instance isolation;
- `riverd ps` lists both live instances with their distinct data directories
  and selected endpoints, then lists only the survivor after either is stopped;
- occupied-port and engine-open failures exit nonzero with concise errors and
  no false `riverd_status=ready` record;
- ready-file publication races with broken stdout prove file publication is
  the sole commit and a broken mirror never stops service; without a ready file,
  prefix/partial failures clean up while an externally observed complete final
  ready record remains irrevocable across a later flush failure; post-commit
  target/parent/source or ambiguous stdout-only failure terminates with
  `IO_FAILURE` after ordered cleanup;
- interrupt during startup and after readiness, verifying acquired resources
  close and the database remains restartable.

### 10.5 Build checks

Use the narrow loop while implementing:

```sh
./gradlew :river-server-app:test
./gradlew --no-daemon :river-server-app:installDist
./gradlew moduleDependencyPolicy
```

Then run the affected server/client tests and normal project verification
appropriate to a new distribution. Do not run builds concurrently in the
shared checkout and do not use `clean` during the edit loop.

Capture a compact slopmark baseline for `river-server`, `river-server-app`, and
the touched build files. A rising score or duplicated lifecycle/resource policy
is a signal to move ownership back to one class rather than adding wrappers.

### 10.6 Deferred audit evidence

No SQL/security audit implementation, placeholder, archive study, or audit
performance campaign is part of this server delivery. Reconsideration requires
a concrete architecture that supports neutral TPS, latency, and resource use,
followed by matched evidence of that property.

## 11. Harness migration and deletion gate

After the installed command passes its real lifecycle test:

1. Add a harness option for the `riverd` executable, defaulting to the adjacent
   River installation path when present and accepting an explicit override.
2. Start `riverd start -D <harness-owned-run-directory> --port=0` as a
   foreground child and parse the documented readiness contract.
3. Load the pinned certificate and token paths from that contract, establish
   TLS 1.3, export `EXPORTER-River-Authentication`, and complete protocol-v4
   `AUTHENTICATE` before opening a session. There is no plain fallback.
4. Record the version label and child PID in evidence.
5. Run `riverd stop -D` against the same data directory, wait for the recorded
   child, and require verified graceful shutdown.
6. Delete the harness Gradle invocation, init script, classpath parsing and
   fingerprinting, Java-main-class knowledge, and process-class inspection.
7. Migrate the standalone Go harness in this slice. `tools/tps-test.sh` and
   `tools/trace-update.sh` currently consume server-side JFR gates, resource
   controls, performance capture, deadlock/commit diagnostics, and terminal
   metrics that `riverd` deliberately does not own. Do not delete
   `TpccServerMain` before those gates move. `tic-ec50` has already secured its
   listener and removed every plain API; `tic-3f57` replaces or retains that
   authenticated diagnostic owner only after every accepted producer is
   preserved. It is not a public lifecycle or compatibility path.

The native River v4 Go transport is a separate migration boundary. It may
remain only while River explicitly owns protocol v4 as the supported client
contract. Replace it with a standard PostgreSQL-compatible Go driver when
River's PostgreSQL wire compatibility is delivered; do not mix that future
work into the launcher slice.

## 12. Completion criteria

The launcher slice is complete when:

- `riverd` runs directly from its installed distribution with no source-tree
  classpath construction;
- default and overridden paths/address are printed exactly once at startup;
- first start, clean stop, restart, connection, and persistence work through
  the real distribution;
- platform shutdown (Unix SIGINT/SIGTERM or Windows console shutdown) close the listener before the database;
- `riverd stop` stops only the server that consumes the verified owner-bound
  request, never signals by PID, and returns a clear nonzero result for stale
  or mismatched state;
- `riverd ps` lists all verified instances recorded by the
  current user, and the empty listing suggests `riverd start`;
- a failure known before readiness commit exits nonzero without successful
  readiness; a prior irrevocable readiness observation may be followed only by
  the specified terminal `IO_FAILURE`, ordered cleanup, and exit 1;
- the instance lock prevents concurrent ownership without inspecting or
  terminating another PID;
- help/version are useful and side-effect free;
- build dependency and reproducibility policies cover the new module;
- the harness consumes only the executable/readiness contract and all old
  River build/classpath launcher code is deleted.

## 13. Explicitly deferred

- PostgreSQL wire compatibility and a PostgreSQL Go driver;
- non-loopback listening and multi-principal SQL authorization; TLS 1.3 and
  the single incarnation-bound token are mandatory and are not deferred;
- background daemonisation, OS service installation, and privilege changes;
- remote stop/restart administration;
- automated database deletion, repair, migration, backup, or restore;
- TPC-C metrics, JFR orchestration, or benchmark-specific flags;
- a broad low-level memory-tuning CLI.
