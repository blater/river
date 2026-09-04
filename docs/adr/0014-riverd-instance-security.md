# ADR 0014: `riverd` instance security and client discovery

Status: Accepted

## Authority and scope

This ADR is the public lifecycle and security contract for the first installed
`riverd`. It ratifies
[`docs/plans/riverd-standalone-server-plan.md`](../plans/riverd-standalone-server-plan.md)
and replaces every alternative or deferred description of the same behavior.
It closes the ownership and deletion gaps inventoried by `tic-de1d`, merged at
`4827f84e349c0aed7b4c585aede13d505efb1eb9` and recorded closed at
`5b120a179055a9ec1c640152b4e2bf057d23f5ac`.
It is pinned to the accepted audit design merged at
`e592addff67ac6016ae6e9e37e3bf374a6511f0d` and recorded closed on `master` at
`8df484694039e9b53cd7ff6c5cccb44973b86c0e`. Every audit event, state,
durability, recovery, byte-budget, exhaustion, archive, allocation, copy, and
performance rule in that
[`tic-a221` evidence](../delivery/evidence/2026-09-04-tic-a221-audit-durability-design.md)
is part of this decision.

[ADR 0012](0012-embedded-api-and-protocol-boundaries.md) remains authoritative
for the embedded API, protocol, client, server, TLS, authentication,
authorization, and audit-before-admission boundaries. This ADR supplies the
launcher-owned identity, filesystem, command, discovery, and recovery
contract. River is pre-V1: the authenticated lifecycle replaces all unreleased
plain production paths; it does not wrap or preserve them.

## Public command grammar and mutation boundary

The installed executable is `riverd`. Its complete first-version grammar is:

```text
riverd
riverd start [-D PATH|--datadir=PATH] [-L HOST:PORT|--listen=HOST:PORT]
             [--maximum-connections=N] [--ready-file=PATH]
riverd stop [-D PATH|--datadir=PATH] [--timeout=DURATION]
riverd ps
riverd audit archive [-D PATH|--datadir=PATH]
riverd credentials renew [-D PATH|--datadir=PATH]
riverd version
riverd -h
riverd --help
riverd help [start|stop|ps|audit archive|credentials renew]
```

No arguments is exactly `riverd ps`. `start` is a foreground command. The
default data directory is `$HOME/.river/default`, the default listener is
`127.0.0.1:9191`, the default maximum connection count is 16, and the default
stop timeout is 30 seconds. `-L` accepts only `localhost`, `127.0.0.1`, or
bracketed `::1`; port zero requests an ephemeral port. Wildcard,
non-loopback, ambiguous multi-address, malformed, and out-of-range inputs are
invalid. `N` is canonical decimal `1..2147483647`; values outside the addressable
slot width are `INVALID_EXTERNAL_INPUT`, while a valid count that cannot compile
within the declared resource profile is `RESOURCE_EXHAUSTED` before mutation.
The launcher compiles it once; there are no individual engine tuning flags.
`DURATION` is one positive canonical decimal followed by `ms`, `s`, or `m`;
checked conversion must fit a positive signed-long millisecond value. Fractions,
signs, whitespace, missing units, zero, and overflow are invalid.

Parsing, duplicate/conflicting option detection, path normalization, control
character rejection, and range validation complete before any filesystem or
network mutation. Brief help is `-h` or `help`; `--help`, command help, and
subcommand help are comprehensive. Help and version are side-effect free.
There is no daemon, service, delete, repair, migrate, remote administration,
stop-all, trust-all, no-authentication, TLS-disable, credential-by-value, or
secret environment-variable command.

`riverd version` prints exactly:

```text
riverd_version=<distribution-version>
riverd_contract=riverd-v1
riverd_protocol=river-v4
riverd_status=OK
```

After verified process exit and matching owned-record removal, `riverd stop`
prints:

```text
riverd_datadir=<normalized-absolute-path>
riverd_pid=<positive-decimal-long>
riverd_status=OK
```

## Exit codes, native statuses, and diagnostics

The process has three public exit classes:

| Exit | Native outcome | Meaning |
| --- | --- | --- |
| 0 | `OK` | Help, version, listing including an empty list, successful offline operation, or a foreground server that shut down cleanly. |
| 2 | `INVALID_EXTERNAL_INPUT` | Invalid command syntax or option value, detected before mutation. |
| 1 | Named non-`OK` `StatusCode` | Startup, lifecycle, security, audit, filesystem, I/O, or shutdown failure. |

An exit-1/2 command writes a concise diagnostic followed by exactly
`riverd_status_code=<StatusCode.stableCode()>` and final
`riverd_status=<StatusCode-name>` records to standard error. It never prints
successful readiness. Human text is diagnostic only and is not a second status
contract.

The owning boundary uses these exact outcomes:

| Boundary | Status |
| --- | --- |
| Bad syntax, duplicate option, invalid path/listen/duration/count/config field | `INVALID_EXTERNAL_INPUT` |
| Unsupported filesystem security or process-identity capability | `FEATURE_NOT_SUPPORTED` |
| Wrong owner/mode, symlink/special file, denied file access, wrong TLS peer/token/proof, or credential validity | `ACCESS_DENIED` |
| Existing ready file, held instance, bind collision, archive name collision, or incompatible staged retry | `CONFLICT` |
| Missing/stale/reused/mismatched runtime owner or unexpectedly free live lock | `NOT_OWNER` |
| Malformed, missing after authority, mismatched, torn, or checksum-invalid persistent state | `CORRUPTION` |
| Active audit bytes unavailable or terminal audit identity exhausted | `RESOURCE_EXHAUSTED` |
| Pending audit bytes unavailable before a sequence exists | `RETRY` |
| Cancelled/deadline/stop wait | `CANCELLED`, `TIMEOUT`, or `TIMEOUT` respectively |
| Java/NIO or signalling failure | `IO_FAILURE` |
| Impossible River-owned state | `INVARIANT_BROKEN` and the owning fatal fence |

A well-formed but expired/not-yet-valid credential is `ACCESS_DENIED` on
`start`; `credentials renew` may validate and replace an expired generation.
An audit I/O ambiguity returns `IO_FAILURE`, fences the audit owner, and makes
later admissions return `FENCED`, exactly as accepted by `tic-a221`.

## Instance path and filesystem trust proof

`-D` identifies one complete instance:

```text
DATADIR/
  instance.properties
  instance.lock
  bootstrap.properties     # present only during first-create recovery
  runtime.properties
  database/
  security/
    security.properties
    client.properties
    generations/<credential-generation>/
      token.bin
      server-private-key.pkcs8
      server-certificate.der
    archive/
  audit/
```

The launcher resolves an absolute normalized path through the nearest existing
ancestor and uses the real ancestor path as the base. The data directory and
every launcher-owned directory are real directories with POSIX mode `0700`;
launcher-owned regular files use `0600`, including the public certificate.
The owner is the effective launch user's resolved `UserPrincipal`. No
launcher-owned path component may be a symbolic link or special file.

The first implementation supports only the qualified default NIO filesystem
when it provides POSIX attributes, `SecureDirectoryStream`, exclusive file
locking, same-filesystem atomic exclusive and replacement moves, and
synchronous file and directory force. All
lookup/create/open/rename/remove operations below the
resolved parent use no-follow, directory-relative handles; owner, type, mode,
and identity are checked before and after opening. Absence of a required proof
is `FEATURE_NOT_SUPPORTED`; a failed proof is `ACCESS_DENIED`. There is no
best-effort permission mode and no non-POSIX fallback.

`--ready-file` uses the same secure-parent/no-follow rules, is created `0600`,
and is never overwritten. The fixed per-user registry is
`$HOME/.river/run/instances`, mode `0700`, with `0600` records. Apart from the
instance tree, that registry, and an explicit ready file, `riverd` writes
nothing. Paths containing NUL, CR, LF, `=`, or another Unicode control
character are rejected rather than escaped in a version-1 record.

Versioned launcher properties are canonical UTF-8, LF-terminated `key=value`
records in the declared order, with no blank lines, comments, duplicate or
unknown keys, leading/trailing whitespace, or alternate numeric spelling.
`instance.properties` and `bootstrap.properties` are at most 4096 bytes;
security, client, runtime,
registry, ready, and audit-control records are each at most 8192 bytes. These
are format framing bounds, not workload or audit-event caps. Oversize external
input is `INVALID_EXTERNAL_INPUT`; oversize or noncanonical accepted state is
`CORRUPTION`.

## Persistent identity and strict create/open selection

`instance.properties` is the single instance authority:

```text
format=riverd-instance-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
initial-wal-generation=1
```

The combined 128-bit incarnation is generated with the platform
`SecureRandom` and must be nonzero. WAL generation one is the only initial
value. River does not infer either value from database bytes.

The temporary first-create record has this exact ordered schema:

```text
format=riverd-bootstrap-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
attempt-nonce=<32-lowercase-hex>
database-name=database
security-name=security
audit-name=audit
```

For a first creation, while holding `instance.lock`, the launcher requires a
new data directory or a directory containing only its newly created lock and
recognized staging names. Before creating child state it exclusively publishes
and forces a `riverd-bootstrap-v1` record containing the generated incarnation,
PID/start/command, a random nonzero attempt nonce, and the fixed
`database`/`security`/`audit` names, then forces `DATADIR`. It creates the
database with that identity, creates and forces the initial security and audit
state, forces their directories, then exclusively publishes and forces
`instance.properties` last and forces `DATADIR`.

When instance authority is absent, a retry may remove and recreate only the
fixed children named by a canonical owner-only bootstrap record whose
PID/start is absent and whose directory contains no entry beyond the lock,
bootstrap record, and those fixed children. Invalid or
extra pre-authority state returns `CONFLICT` and is preserved. After instance
authority exists, a matching bootstrap residue is removed only after all
authoritative state validates; any mismatch is `CORRUPTION`. No arbitrary
non-empty directory is adopted or cleaned.

After `instance.properties` exists, the instance is authoritative. Every
required database, security, and audit artifact must validate against it;
missing or mismatched state is `CORRUPTION` and is preserved. Restart calls
`EmbeddedRiver.openExisting` with the exact stored identity and never repairs,
regenerates, deletes, infers, or falls back to create.

## TLS, token, credential generation, and renewal

Credential generation one contains:

- a 32-byte token produced directly by `SecureRandom`;
- one `secp256r1` key pair from JCA `KeyPairGenerator`;
- a DER X.509 v3 self-signed leaf using `SHA256withECDSA`, a positive nonzero
  random 128-bit serial, critical `CA=false`, critical digital-signature key
  usage, noncritical extended-key-usage `serverAuth`, and noncritical SANs
  `localhost`, `127.0.0.1`, and `::1`; issuer and subject are the same single
  `CN=riverd-<32-lowercase-incarnation-hex>` name and no other extensions are
  present;
- `notBefore` equal to creation time minus five minutes and `notAfter` equal
  to creation time plus 365 days, both truncated to epoch seconds;
- principal ID 1 and exactly `SessionPermissions.ALL`.

Production certificate construction uses the public Bouncy Castle
`bcpkix-jdk18on` `X509v3CertificateBuilder`, `JcaContentSignerBuilder`, and
`JcaX509CertificateConverter` APIs with the JCA-generated key pair. The direct
dependency and its transitive `bcprov`/`bcutil` versions are one aligned,
centrally pinned, dependency-verified set selected in `tic-615d`. River does
not install a global provider, call JDK-internal certificate classes, invoke
`keytool`, or implement a second DER/X.509 encoder. The generated DER is parsed
with `CertificateFactory`, signature-verified with its public key, and checked
against every field above before publication.

Private keys are PKCS#8 DER and at most 2048 bytes; certificates are DER and at
most 4096 bytes; tokens are exactly 32 bytes. `security.properties` is
published last within the credential transaction. Its exact ordered schema is:

```text
format=riverd-security-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
credential-generation=<positive-decimal-long>
principal-id=1
permission-mask=15
token-algorithm=raw-256
key-algorithm=ec-secp256r1
signature-algorithm=sha256-with-ecdsa
certificate-not-before-epoch-second=<nonnegative-decimal-long>
certificate-not-after-epoch-second=<positive-decimal-long>
token-file=generations/<generation>/token.bin
private-key-file=generations/<generation>/server-private-key.pkcs8
server-certificate-file=generations/<generation>/server-certificate.der
token-sha256=<64-lowercase-hex>
private-key-sha256=<64-lowercase-hex>
server-certificate-sha256=<64-lowercase-hex>
```

The relative names must be exactly those derived from the canonical generation,
not arbitrary manifest paths. The complete generation directory and manifest
are forced before they become eligible for instance authority.

Restart validates file type, no-follow identity, owner, mode, size, canonical
manifest, incarnation, generation, digests, key/certificate match, signature,
algorithm, constraints, usages, SANs, validity, principal, and permission mask
before database/listener admission. Token, proof, exporter, private-key, SQL,
parameter, result, and arbitrary diagnostic bytes never enter argv,
environment, URLs, readiness, registry, logs, or audit.

## Client configuration and authenticated-only migration

After listener bind selects the concrete port, `river-server-app` atomically
replaces its own `security/client.properties` under the instance lock and
forces the security directory before runtime/registry/readiness publication.
The file is derived discovery state, not credential or instance authority; it
may be absent or stale while stopped and is never accepted as restart input.
`river-client` owns its only parser/validator and the authenticated connector
built from it. Its exact ordered schema is:

```text
format=riverd-client-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
credential-generation=<positive-decimal-long>
principal-id=1
transport=tls-v1.3
protocol=river-v4
host=<localhost|127.0.0.1|::1>
port=<1..65535>
server-certificate-file=<normalized-absolute-generation-path>
server-certificate-sha256=<64-lowercase-hex>
token-file=<normalized-absolute-generation-path>
```

The loader applies the same bound/canonical/path/type/owner/mode checks, reads
the exact certificate and token, verifies the digest, pins that certificate
exactly, permits TLS 1.3 only, performs hostname verification, and erases token
and proof scratch. JDBC and CLI delegate to this owner; they do not duplicate
file parsing or select a JVM trust store. Public programmatic clients accept
only this validated configuration or certificate-file and token-file paths;
raw token/`SSLContext` provider seams are package-private and test-only.

`tic-ec50` changes every River-owned server, client, JDBC, CLI, benchmark,
script, and test caller together, then deletes plain
`LoopbackRiverServer.start`, plain `RiverClientConnection.connectLoopback`, the
nullable authentication branch in `RiverClientConnector`, JDBC clear/unset
authentication, CLI plain selection, and their tests. `TpccServerMain` remains
only as an authenticated diagnostic composition until `tic-3f57` preserves or
replaces all of its evidence producers. There is no public compatibility
wrapper, inactive plain flag, or unaudited remote mode after `tic-ec50`.

## Audit admission, durability, capacity, and exhaustion

The complete accepted `tic-a221` event and state-machine contract is normative:

- every evaluable authentication decision and every actual canonical
  statement-admission decision is durable before its allowed effect or denied
  result;
- the provider owns fixed records, byte reservations, slots, queue, checksum,
  file, force coordinator, completion state, and recovery;
- `activeAuditMaximumBytes` and `pendingAuditMaximumBytes` are positive `long`
  resource budgets with checked arithmetic; there is no event-count cap,
  allocation fallback, unaudited fallback, or disabled riverd audit;
- only a successful force of the consecutive sealed prefix advances the
  durable frontier and releases admission; cancellations, close, I/O ambiguity,
  corruption, and restart follow the accepted state/failure matrices; and
- global sequence/generation/control terminal exhaustion durably enters
  `EXHAUSTED`, never wraps or resets, survives restart, and cannot be cleared by
  ordinary archive. Recovery then requires a separately reviewed wider format
  or a new instance incarnation.

Ordinary active-file byte exhaustion returns `RESOURCE_EXHAUSTED` before work
and is recoverable with stopped-instance archive when a legal next generation
and sequence remain. Pending-byte pressure returns `RETRY` before sequence
assignment. Startup must be able to reserve the header plus one authentication
and one statement-decision record before readiness.

Audit is mandatory for remote riverd. It is non-applicable to an embedded
session with no remote `SessionAuthorizer`; that path creates no audit file,
coordinator, queue, staging arena, thread, force, or per-row work.

## Readiness, runtime record, and registry formats

After identity/security/audit/database validation, listener bind, current
client-configuration publication, runtime publication, and registry publication
all succeed, start writes and flushes these ordered UTF-8 records to standard
output:

```text
riverd_datadir=<normalized-absolute-path>
riverd_data=<normalized-absolute-path>/database
riverd_identity=<normalized-absolute-path>/instance.properties
riverd_runtime_file=<normalized-absolute-path>/runtime.properties
riverd_registry_record=<normalized-absolute-path>
riverd_listen_address=<localhost|127.0.0.1|::1>
riverd_listen_port=<1..65535>
riverd_pid=<positive-decimal-long>
riverd_protocol=river-v4
riverd_transport=tls-v1.3
riverd_client_config=<normalized-absolute-path>
riverd_server_certificate_sha256=<64-lowercase-hex>
riverd_status=ready
```

The protocol value comes from `river-protocol`, not launcher duplication. The
same bytes are atomically published to a requested ready file before stdout;
both contain no secret. Any failure before the final flushed line closes
acquired resources, publishes no `riverd_status=ready`, and exits nonzero.

`runtime.properties` is published atomically while the start process owns the
lock. Its exact ordered schema is:

```text
format=riverd-runtime-v1
datadir=<normalized-absolute-path>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
listen-address=<localhost|127.0.0.1|::1>
listen-port=<1..65535>
client-config=<normalized-absolute-path>
credential-generation=<positive-decimal-long>
owner-nonce=<32-lowercase-hex>
```

The forced `instance.lock` record uses:

```text
format=riverd-lock-v1
datadir=<normalized-absolute-path>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
owner-nonce=<32-lowercase-hex>
```

Process start instant and command must be available; otherwise start returns
`FEATURE_NOT_SUPPORTED`.

The registry filename is lowercase SHA-256 of the UTF-8 normalized datadir.
Its exact ordered schema is:

```text
format=riverd-registry-v1
datadir=<normalized-absolute-path>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
listen-address=<localhost|127.0.0.1|::1>
listen-port=<1..65535>
river-version=<distribution-version>
launcher-contract=riverd-v1
protocol=river-v4
runtime-file=<normalized-absolute-path>
owner-nonce=<32-lowercase-hex>
```

It is published after listener readiness and removed only when its full
identity matches. `ps` validates every direct regular child, the runtime/lock
identity, and the live process, prints verified instances sorted by datadir,
warns without deleting invalid/stale records, never scans arbitrary processes,
and never signals. Its empty output remains the exact guidance in the plan.
Under the instance lock, a later `start` may replace only a canonical record
whose filename/content identifies that same datadir and whose exact process is
proved absent. Malformed or mismatched collisions are preserved and return
`CORRUPTION`/`CONFLICT`; `ps` itself never performs this recovery.

## Foreground lifecycle and exact stop fencing

Start owns resources in this order: secure directory handle, instance lock,
validated identity/security/audit, `RiverDatabase`, authenticated
`LoopbackRiverServer`, current client configuration, runtime record, registry
record, ready file/stdout. The
shutdown hook is installed after database ownership. Startup failure and
shutdown release in reverse order except that the listener always closes
before the database. One idempotent lifecycle owner serves normal close,
SIGINT, and SIGTERM; it reports both close statuses, preserves the first fatal
outcome, removes only matching runtime/registry records, and never deletes
database/identity/security/audit data.

`riverd stop` never acquires or steals the live process's exclusive lock. It
securely reads the bounded runtime and lock records, requires their datadir,
PID, start instant, command, and owner nonce to match, requires an exclusive
nonblocking lock attempt to fail, and obtains the exact `ProcessHandle`. It
checks PID/start/command immediately before `destroy()` (SIGTERM semantics).
Missing, malformed, stale, reused, mismatched, unexpectedly unlocked, or
unverifiable state returns `NOT_OWNER` and sends no signal. Same-account
processes are inside the trust boundary established by the owner-only tree.

Stop waits for exact-process exit and matching runtime-record removal for the
configured timeout; once registry support is present it also requires matching
registry-record removal. Timeout returns `TIMEOUT` and never sends SIGKILL.
Simultaneous stop may yield one `OK` and one `NOT_OWNER`; neither signals an
unrelated process. A later start may replace a stale runtime/registry record
only after it acquires the instance lock and proves the recorded process absent.

## Offline audit archive

`riverd audit archive -D PATH` requires the stopped instance lock and no live
owner or pending slot. It performs the accepted `tic-a221` five-step protocol:
validate/force old active; create/force and directory-force the linked new
generation; publish/force/directory-force `ARCHIVING` control; rename the old
file without overwrite to
`audit-<generation>-sha256-<lowercase-digest>.log` and force the directory; then
publish/force/directory-force the next `ACTIVE` control. Its success output is:

```text
riverd_audit_archive=<normalized-absolute-path>
riverd_audit_sha256=<64-lowercase-hex>
riverd_status=OK
```

Retry and recovery follow the accepted control-generation rules. Corrupt audit
is preserved as `CORRUPTION`; an unexpected archive/stage collision is
`CONFLICT`; there is no truncation, overwrite, rollover, repair, delete, or
preserve-and-reinitialize command. Terminal `EXHAUSTED` authority returns
`RESOURCE_EXHAUSTED` and archive changes nothing.

## Certificate expiry and credential renewal

`riverd credentials renew -D PATH` requires the stopped instance lock. It
validates the complete current credential generation except that expiry is
allowed; corrupt, missing, or mismatched accepted material is `CORRUPTION`, not
repair. It content-identifies and forces the old public certificate and
security manifest beneath
`security/archive/credential-<generation>-sha256-<manifest-digest>/` without
overwrite, then creates, validates, and forces a complete new generation using
the same construction policy and generation plus one.

The new generation directory is directory-forced before an atomically replaced,
forced `security.properties` selects it; the security directory is then forced.
Before that selection the old generation is
authoritative. After it, only the new token/certificate authenticate. Exact
unreferenced stage files are preserved for diagnosis and an idempotent retry;
old private-key/token files are removed only after the new authority and public
archive are durable. Their interrupted owner-only residue is never loaded.
Generation arithmetic is checked and never wraps. Renewal leaves any prior
client configuration stale; the next successful start replaces it only after
binding its selected endpoint.

Success prints only:

```text
riverd_credential_archive=<normalized-absolute-path>
riverd_credential_archive_sha256=<64-lowercase-manifest-digest>
riverd_credential_generation=<positive-decimal-long>
riverd_server_certificate_sha256=<64-lowercase-hex>
riverd_status=OK
```

There is no overlap generation or automatic renewal. A client that already
loaded the old certificate/token fails authentication with `ACCESS_DENIED`;
reloading a stale copied configuration after its referenced old secret was
removed returns `IO_FAILURE`. Neither can authenticate. The operator uses the
new `security/client.properties` published by the next successful start. Start
returns `ACCESS_DENIED` for a well-formed expired generation and names this
stopped-instance command.

## Module owners and deletion gates

| Owner | Responsibility and delivery |
| --- | --- |
| `river-server-app` | Identity, credentials, filesystem proof, command/lifecycle composition, resource plan, readiness, runtime/registry, archive/renew operations. `tic-615d` creates this non-empty module with identity/security/config production code; `tic-ec50` adds the installed application and complete composition. |
| `river-server` | Authenticated TLS listener, canonical authentication/authorization/audit admission, connection and shutdown behavior; never concrete engine composition. `tic-72ea` replaces audit persistence. |
| `river-client` | One bounded client-configuration parser and pinned authenticated connector. |
| `river-jdbc` / `river-cli` | Public adapters over `river-client`; no duplicate trust/config parser and no optional plain path. |
| `river-bench` | Audit benchmark and authenticated River diagnostics only; no launcher defaults or public process contract. |
| `river-engine` / `river-engine-api` | Embedded implementation and public database lifecycle respectively; launcher composes without leaking engine types. |

`tic-ec50` also removes the unused root `river-server -> river-engine`
dependency allowance while adding only used app edges. `tic-95e8` proves the
installed lifecycle and source/compiled absence of plain paths. `tic-0803`,
`tic-d2e9`, and `tic-b901` add stop, listing, and recovery commands against the
already accepted formats. `tic-9640` proves their composed recovery matrix;
`tic-4cb6` publishes the stable external consumer subset only after that gate.

## Audit performance and acceptance contract

`tic-72ea` must run the exact accepted `tic-a221` matched evidence plan. Its
fixed-count correctness runs use client counts 1, 2, 4, and 16. Timed control
and candidate samples use `C,A,A,C,C,A,A,C,C,A`, five 30-second measured
samples per source/count, and 10,000 fixed-seed whole-sample bootstrap
resamples. Correctness, gap-free sequences, restart validation, resource
cleanup, zero warmed allocation per event, zero River-owned byte-array copies,
and one force per deterministic cohort are absolute.

At 4 and 16 clients, the upper 95% confidence bound for forces/decision is at
most 0.75. At every client count, the candidate/control throughput lower 95%
bound is at least 0.95; the p99.9 latency, CPU nanoseconds/decision,
monitor-blocked nanoseconds/decision, GC pause nanoseconds/decision, and GC
collections/million-decisions upper 95% ratio bounds are at most 1.10. A zero
control metric requires zero candidate metric. A failed interval is not waived
by profile shape, and failed artifacts are preserved.

## Explicit deferrals

PostgreSQL wire compatibility, non-loopback listening, multi-principal SQL
roles/grants, daemon/service/privilege integration, remote administration,
automated database deletion/repair/migration/backup/restore, benchmark metrics
inside riverd, and a broad tuning CLI are deferred. TLS 1.3, the token,
single-principal authorization, audit-before-admission, credentials, exact
lifecycle, and authenticated-only River callers are mandatory and are not
included in those deferrals.

## Consequences

The first riverd is intentionally strict: unsupported host filesystems do not
receive weaker security, accepted corrupt state is preserved rather than
repaired, credential expiry requires an explicit offline rotation, and audit
exhaustion stops admission before effects. In return, a process/file consumer
has one non-secret discovery contract and River owns one authenticated remote
path without unreleased compatibility debt.
