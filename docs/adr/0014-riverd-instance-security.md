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
riverd help
riverd start -h
riverd start --help
riverd stop -h
riverd stop --help
riverd ps -h
riverd ps --help
riverd version -h
riverd version --help
riverd audit -h
riverd audit --help
riverd audit archive -h
riverd audit archive --help
riverd credentials -h
riverd credentials --help
riverd credentials renew -h
riverd credentials renew --help
riverd help start
riverd help stop
riverd help ps
riverd help version
riverd help audit
riverd help audit archive
riverd help credentials
riverd help credentials renew
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
character rejection, range validation, and the path-collision proof below
complete before any filesystem or network mutation. Global or command/group
`-h` and global `help` are brief; global or command/group `--help` and every
listed `help ...` form are comprehensive for that exact scope. Any unlisted
help placement, extra token, bare `audit`/`credentials`, abbreviation, or
combined short option is invalid before mutation. Help and version are
side-effect free.
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
| Java/NIO, control-publication, or lifecycle I/O failure | `IO_FAILURE` |
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
  stop.request            # present only for a cooperative stop request
  .stop-request-<nonce>.stage
  .stop-accepted-<nonce>
  bootstrap.properties     # present only during first-create recovery
  .bootstrap-<nonce>.stage
  .riverd-bootstrap-<nonce>/
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
    .renew-<old>-to-<new>-<nonce>/
  audit/
```

The launcher resolves an absolute normalized path through the nearest existing
ancestor and uses the real ancestor path as the base. Before mutation it proves
that the prospective data directory, each fixed child, the registry tree, and
an explicit ready target have only the declared containment relation of each
fixed child directly beneath `DATADIR`. Every other equality,
ancestor/descendant overlap, symbolic-link alias, and existing-file-key alias
(including a hard link) is rejected as `INVALID_EXTERNAL_INPUT`. The ready
target must be outside the data and registry trees. A missing leaf is compared as its verified
real parent plus one validated component; the comparison is repeated after
creation and before publication.

The data directory and every launcher-owned directory are real directories
with POSIX mode `0700`; launcher-owned regular files use `0600`, including the
public certificate. The owner is the effective launch user's resolved
`UserPrincipal`. Same-account actors are trusted only after owner and mode are
proved and the provider proves that no ACL or other access mechanism overrides
those POSIX restrictions. An unenumerable access mechanism is
`FEATURE_NOT_SUPPORTED`; any additional allow/grant is `ACCESS_DENIED`. No
launcher-owned component may be a symbolic link or special file.

The first implementation has one public-NIO adapter and supports only the
default Linux NIO provider on a local `ext4` or `xfs` `FileStore`. It requires
POSIX access semantics in which the group permission bits are the POSIX ACL
mask; exact `0700`/`0600` therefore proves that no named-user/group ACL grant is
effective. If an `AclFileAttributeView` or provider-specific access view is
also exposed, every entry is enumerated and no non-owner allow entry may be
effective. macOS/APFS, NFS, SMB, FUSE, overlay, an unrecognized provider/store,
or any access view whose effect cannot be enumerated or masked is
`FEATURE_NOT_SUPPORTED`. Privileged host administrators remain outside the
filesystem threat boundary. The provider must also supply
`SecureDirectoryStream`, exclusive file locking, same-filesystem atomic moves,
hard links, and synchronous file and directory force. `SecureDirectoryStream` owns every
relative lookup, open, move, and removal of an existing child; it is not used to
create a directory because public NIO exposes no such operation. The only
path-based operation below a verified tree is
`Files.createDirectory(verifiedParent.resolve(singleFixedComponent), 0700)`.
Immediately afterward the adapter no-follow reads the child's type, owner,
mode, ACL, and file key; reopens and compares the parent's file key; acquires
the child through the parent's secure stream; and compares the child's file
key again. On any mismatch it removes only that exact newly created file key
and fails. A prospective data-directory leaf follows this rule; missing parent
chains and caller-selected intermediate components are unsupported. The
prospective data-directory leaf and a ready-file leaf require an already
existing verified `0700` parent. The fixed `$HOME/.river/run/instances` chain
may be created one fixed component at a time, but only when `$HOME` or the prior
component is already verified `0700` and every new parent immediately passes
the same proof; otherwise startup is `ACCESS_DENIED` without mutation.

At startup the adapter runs an owner-only scratch capability probe on the same
filesystem. Exclusive regular-file publication uses
`Files.createLink(target, forcedStage)`, whose existing-target failure is
atomic, followed by parent force and exact stage unlink. Replacement uses
`Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING)`. Staged fixed
directories use `Files.move(stage,target,ATOMIC_MOVE)` only after an
SDS-relative absence check while the instance lock excludes River competitors;
same-account non-River actors are already trusted. The probe verifies
existing-target refusal, competing creators, replacement visibility, expected
bytes and file keys, then forces source and destination directories with
readable directory `FileChannel.force(true)`. A provider whose semantics differ
is `FEATURE_NOT_SUPPORTED`. Production publication uses only these qualified
adapter operations; deterministic concurrency/fault tests prove exclusive,
replacement, and force behavior. There is no best-effort permission or
non-POSIX fallback.

`--ready-file` uses the same secure-parent/no-follow rules, is created `0600`,
and is never overwritten. The fixed per-user registry is
`$HOME/.river/run/instances`, mode `0700`, with `0600` records. Apart from the
instance tree, that registry, and an explicit ready file, `riverd` writes
nothing. Paths containing NUL, CR, LF, `=`, or another Unicode control
character are rejected; version 1 has no encoding alternative.

Every launcher-owned persistent properties schema in this ADR is canonical UTF-8 in the
declared order, with no BOM, CR, blank line, comment, duplicate or unknown key,
leading/trailing whitespace, or alternate numeric spelling. Its final field is
exactly `record-sha256=<64-lowercase-hex>` followed by LF. The SHA-256 input is
the exact byte sequence from file offset zero through the LF immediately before
that final field; the entire checksum line, including its LF, is excluded.
Every reader performs a bounded full read, canonical parse, and checksum
validation before using any field, opening any referenced path, checking a
process, or taking a lifecycle action. The checksum detects corruption; it is
not authentication. `instance.properties` and `bootstrap.properties` are at
most 4096 bytes; security, client, runtime, lock, stop-request, registry,
ready-file, and credential-public records are each at most 8192 bytes. Audit
event/control checksums and bounds remain the binary contract accepted by
`tic-a221`; they are not launcher properties. Stdout is not a persistent-
properties record and has no checksum. These are format framing
bounds, not workload or audit-event caps. Oversize external input is
`INVALID_EXTERNAL_INPUT`; oversize, noncanonical, or checksum-invalid accepted
state is `CORRUPTION`.

## Persistent identity and strict create/open selection

`instance.properties` is the single instance authority:

```text
format=riverd-instance-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
initial-wal-generation=1
record-sha256=<64-lowercase-hex>
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
staging-name=.riverd-bootstrap-<attempt-nonce>
record-sha256=<64-lowercase-hex>
```

The nonzero attempt nonce is 16 random bytes encoded as 32 lowercase hex
characters. Its only staging namespace is
`.riverd-bootstrap-<attempt-nonce>`; inside it the only names are `database`,
`security`, `audit`, and `instance.properties.stage`. While holding the forced
lock record, first creation performs these exact durable steps:

1. It validates an empty/new `DATADIR`, fixes the incarnation and nonce, writes
   and forces `.bootstrap-<nonce>.stage`, atomically publishes it without
   overwrite as `bootstrap.properties`, and forces `DATADIR`.
2. It creates the nonce staging directory with the fixed-component directory
   operation, validates its identity, and forces `DATADIR`.
3. It creates and forces the bound database, initial credential generation and
   manifest, and initial audit state beneath the staging namespace; it forces
   each file and containing directory.
4. It atomically publishes staged `database`, then `security`, then `audit`
   without overwrite to their final fixed names, forcing `DATADIR` after each
   rename. A staged child's bootstrap/incarnation header and file key are
   checked immediately before its rename.
5. It writes and forces `instance.properties.stage`, atomically publishes it
   without overwrite as `instance.properties`, and forces `DATADIR`. This
   directory force is the sole instance-authority commit.
6. It removes only the matching bootstrap record and now-empty matching nonce
   namespace, then forces `DATADIR`.

A crash before the bootstrap rename leaves only a checked stage that a retry
may delete by matching file key and nonce. After bootstrap publication and
before instance authority, a retry holding the lock first proves the recorded
process absent, then validates every final/staged child against the bootstrap.
It resumes at the first incomplete ordered step; a valid partial child still
inside the nonce namespace may instead be removed and recreated only when its
header and file key bind that bootstrap. If both source and destination exist,
either differs, an entry is extra, or ordering is impossible, it returns
`CONFLICT`/`CORRUPTION` and preserves everything. A crash after any child rename
therefore leaves that final child authoritative only to the bootstrap and the
next step resumes without adopting arbitrary state. A crash after the
instance-authority force leaves a fully authoritative instance; retry validates
all final state before removing only the matching bootstrap/stage residue. No
broad deletion, non-empty-directory adoption, or generation of a new identity
is permitted during recovery.

| Observed crash boundary under the recovered lock | Only permitted action |
| --- | --- |
| Bootstrap stage exists, final bootstrap absent | Match nonce/file key, remove the stage, force `DATADIR`, and restart step 1 with a new nonce. |
| Bootstrap authoritative, namespace absent or empty | Recreate the one recorded namespace and resume step 3 with the recorded incarnation/nonce. |
| A staged child is partial, no corresponding final child | Validate its bootstrap identity; remove/recreate only that child, force its parent, and resume. |
| Exactly the ordered prefix of `database`, `security`, and `audit` is final | Validate the prefix and remaining staged identities, then perform only the next rename and force. |
| Instance stage is complete, instance target absent | Revalidate all three final children, then publish/force the recorded instance authority. |
| Instance target is complete but the last directory force may have crashed | Revalidate all final bytes and identities and repeat the idempotent `DATADIR` force before treating authority as committed. |
| Instance authority is committed and bootstrap/namespace remains | Validate authority and remove only matching bootstrap/stage residue, then force `DATADIR`. |

Any state outside exactly one row, including a gap in the published-child
order, is preserved and fails closed.

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

Production construction instantiates one local
`org.bouncycastle.jce.provider.BouncyCastleProvider` object; it is never added
to `Security` and is never selected by name. The exact EC operation is
`KeyPairGenerator.getInstance("EC", provider)` initialized with
`ECGenParameterSpec("secp256r1")` and the launcher `SecureRandom`. The same
provider object is passed to every provider-selectable operation:
`JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider)`,
`JcaX509CertificateConverter.setProvider(provider)`,
`CertificateFactory.getInstance("X.509", provider)`, and
`certificate.verify(publicKey, provider)`. The public `bcpkix-jdk18on`
`X509v3CertificateBuilder` supplies the structure. The direct dependency and
its transitive `bcprov`/`bcutil` versions are one aligned, centrally pinned,
dependency-verified set selected in `tic-615d`. River does not globally
install a provider, perform provider-name lookup, call JDK-internal certificate
classes, invoke `keytool`, or implement a second DER/X.509 encoder. The
generated DER is parsed, signature-verified, and checked against every field
above before publication.

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
record-sha256=<64-lowercase-hex>
```

The relative names must be exactly those derived from the canonical generation,
not arbitrary manifest paths. The complete generation directory and manifest
are forced before they become eligible for instance authority.

Restart validates file type, no-follow identity, owner, mode, size, canonical
manifest, incarnation, generation, digests, key/certificate match, signature,
algorithm, constraints, usages, SANs, validity, principal, and permission mask
before database/listener admission. The raw token, private key,
`token-sha256`, complete `security.properties` bytes, and its parsed verifier
are credential-equivalent; the digest is the HMAC key used by the current
`TokenProof`, not a public password hash. All are owner-only, never copied to a
public archive, and never enter argv, environment, URLs, readiness, registry,
logs, or audit.

The launcher reads token and private-key bytes into fixed owned buffers and
zeros every caller scratch buffer in `finally` on success and every failure.
Ownership of the verifier buffer transfers exactly once to `TokenAuthenticator`;
that object owns and zeros the stored HMAC key, offered-proof scratch, and
expected-proof scratch. Its destruction is idempotent and occurs only after the
listener rejects admission, all connection workers have stopped, and the JSSE
listener, engines, managers, context, and session caches have been released.
Private-key DER buffers are zeroed after key-manager construction and a
provider key implementing `Destroyable` is destroyed when JSSE releases it.
TLS session resumption and session tickets are disabled; inability to prove
that state is `FEATURE_NOT_SUPPORTED`. JSSE-owned opaque private/session
material remains owned until that ordered shutdown, after which all River
references are cleared. Durable unlink never claims physical media erasure.

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
record-sha256=<64-lowercase-hex>
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
all succeed, start prepares these ordered UTF-8 records for standard output:

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
two readiness modes have one commit point each and never require both sinks:

- With `--ready-file`, the launcher writes and forces this canonical bounded
  record to a `.<target-name>.riverd-ready-<owner-nonce>.stage` file in the
  verified target parent, then atomically publishes it without overwrite to the
  target and synchronously forces that parent. Publication of the fully forced
  stage is the sole, irrevocable readiness commit. A crash
  before the move leaves no ready target; a matching stage may be removed by
  file key on restart. A crash after the move may leave the complete ready
  record and never a partial one. A directory-force failure after a successful
  move cannot safely retract an externally visible commitment: the service
  remains ready, reports the force failure diagnostically, and orderly shutdown
  removes only the matching ready target. Standard output is written and
  flushed afterward as a best-effort mirror. Broken/closed stdout, partial
  mirror output, or mirror flush failure neither retracts the ready file nor
  stops the ready service.
- Without `--ready-file`, the launcher writes and flushes all lines before
  `riverd_status=ready`, then writes that complete final LF-terminated line as
  one bounded final record and flushes it. The external readiness commit is the
  consumer's observation of that complete final record; a complete record is
  irrevocable even if the following flush reports failure. A prefix
  write/flush failure, or a final write known to have transferred fewer than
  the complete record, closes the listener, workers, database, and lock in
  lifecycle order; removes only matching runtime/registry/client records and
  stages; emits no later readiness; and exits `IO_FAILURE`. If the final write
  or flush fails after a complete record may have become observable, the
  launcher cannot retract it and keeps the service ready while reporting the
  output failure diagnostically. No later stdout failure stops a ready service.

The ready file contains no secret and has this exact schema; its status field is
immediately before the universal final checksum:

```text
format=riverd-ready-v1
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
data=<normalized-absolute-path>/database
identity=<normalized-absolute-path>/instance.properties
runtime-file=<normalized-absolute-path>/runtime.properties
registry-record=<normalized-absolute-path>
listen-address=<localhost|127.0.0.1|::1>
listen-port=<1..65535>
pid=<positive-decimal-long>
protocol=river-v4
transport=tls-v1.3
client-config=<normalized-absolute-path>
server-certificate-sha256=<64-lowercase-hex>
owner-nonce=<32-lowercase-hex>
status=ready
record-sha256=<64-lowercase-hex>
```

`runtime.properties` is published atomically while the start process owns the
lock. Its exact ordered schema is:

```text
format=riverd-runtime-v1
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
listen-address=<localhost|127.0.0.1|::1>
listen-port=<1..65535>
client-config=<normalized-absolute-path>
credential-generation=<positive-decimal-long>
ready-file=<none|normalized-absolute-path>
owner-nonce=<32-lowercase-hex>
record-sha256=<64-lowercase-hex>
```

The forced `instance.lock` record uses:

```text
format=riverd-lock-v1
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
command=<normalized-absolute-ProcessHandle-command>
owner-nonce=<32-lowercase-hex>
record-sha256=<64-lowercase-hex>
```

Process start instant and command must be available; otherwise start returns
`FEATURE_NOT_SUPPORTED`.

The registry filename is lowercase SHA-256 of the UTF-8 normalized datadir.
Its exact ordered schema is:

```text
format=riverd-registry-v1
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
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
record-sha256=<64-lowercase-hex>
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
record, ready file or stdout commitment. The
shutdown hook is installed after database ownership. Startup failure and
shutdown release in reverse order except that the listener always closes
before the database. One idempotent lifecycle owner serves normal close,
SIGINT, SIGTERM delivered directly to the foreground server process, and the
cooperative request below; it reports both close statuses, preserves the first
fatal outcome, removes only matching readiness/runtime/registry/control
records, and never deletes database/identity/security/audit data.

`riverd stop` never signals a PID, calls `ProcessHandle.destroy`, acquires or
steals the live lock, or selects a process by an identifier that can be reused.
It securely validates the bounded runtime and lock records and requires their
datadir, incarnation, PID, start instant, command, and owner nonce to match and
an exclusive nonblocking lock attempt to remain contended. PID/start/command
are evidence and output only. It publishes a cooperative request into the
verified instance root with this exact schema:

```text
format=riverd-stop-request-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
owner-nonce=<32-lowercase-hex>
request-nonce=<32-lowercase-hex>
runtime-record-sha256=<64-lowercase-hex>
requested-at-epoch-millis=<nonnegative-decimal-long>
record-sha256=<64-lowercase-hex>
```

The CLI writes and forces `.stop-request-<request-nonce>.stage`, revalidates the
unchanged lock/runtime file keys and checksums, atomically publishes without
overwrite to `stop.request`, and forces `DATADIR`. The lock-owning server's sole
lifecycle-control thread checks this fixed file before readiness and at least
every 100 milliseconds while serving. It validates checksum, incarnation,
owner nonce, and runtime checksum, then atomically renames it without overwrite
to `.stop-accepted-<request-nonce>`, forces `DATADIR`, and only then invokes the
same idempotent listener-first lifecycle used by a direct SIGINT/SIGTERM.

Concurrent and repeated operations are deterministic. An exact retry joins its
recorded nonce. If another CLI has already published a valid request for the
same owner/runtime, every contender removes only its own unpublished stage and
joins the published request regardless of its newly generated nonce. A request
for a different owner/runtime is `NOT_OWNER`; malformed control state or an
accepted-receipt collision is `CORRUPTION` and is preserved. Before acceptance,
a CLI timeout races one atomic removal against
the server's atomic rename: removal of its unchanged checksum/file key succeeds
and is directory-forced, or acceptance wins and the request cannot be
cancelled. After acceptance, timeout returns `TIMEOUT` without undoing
shutdown. No timeout escalates or signals.

If the server exits or its PID is reused between validation and request
publication, post-publication lock/runtime revalidation cannot match a live
owner; the CLI removes only its unchanged, unaccepted request and returns
`NOT_OWNER`. A server crash before consumption leaves a request bound to its
old owner nonce. The next start, only after acquiring the lock and proving the
old process absent, removes and directory-forces that exact stale request and
never replays it. A crash after acceptance leaves the named accepted receipt;
the next start validates old-owner absence and instance recovery, removes only
that matching receipt, and forces `DATADIR` before readiness. Orderly shutdown
does the same after runtime/registry removal. Stop reports `OK` only after the
lock is free and matching runtime, registry, ready target, request, and accepted
receipt are absent; otherwise it waits until timeout. A completed retry sees no
live owner and returns `NOT_OWNER`.

| Stop crash/publication boundary | Only permitted recovery |
| --- | --- |
| One or more CLI stages exist, `stop.request` absent | A later stop validates every stage for the still-live owner, publishes the lexicographically smallest request nonce, then removes and directory-forces the other matching stages; invalid stages are preserved as `CORRUPTION`. A new owner removes only stages bound to the proved-absent old owner. |
| Request link is visible and its directory force fails or the CLI crashes | The request remains authoritative for the live owner; the CLI/later retry joins and never retracts the ambiguous publication. |
| Request is forced, server has not accepted | The server accepts it or timeout removes exactly that file; the atomic rename/remove winner decides. |
| Accepted receipt is forced, server is shutting down | No CLI cancels it; all contenders wait for cleanup or return `TIMEOUT`. |
| Server crashes before acceptance | The next lock owner proves old-owner absence, removes the matching request/stage, and directory-forces before readiness. |
| Server crashes after acceptance | The next lock owner validates recovery, removes only the matching receipt, and directory-forces before readiness; it never replays shutdown. |

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

While running, the launcher and session authorizer share one monotonic
credential-expiry fence. Every authentication and statement-admission attempt
checks the fence and current wall-clock epoch second before effect. At the first
observation `now >= notAfter`, the observer atomically closes the fence and
invokes the lifecycle owner; an independent timer observes the boundary no
later than one second after it. The listener stops accepting, TLS resumption is
already disabled, every later request on an existing session is rejected with
`ACCESS_DENIED`, and no new statement effect is admitted. Work already admitted
before the fence completes normally or follows ordinary shutdown
cancel/rollback. The launcher then closes workers and JSSE, destroys the token
authenticator, closes the database, removes matching discovery/control records,
releases the lock, and exits 1 with `ACCESS_DENIED` and renewal guidance. A
restart remains `ACCESS_DENIED` until offline renewal; no cached or resumed TLS
session bypasses this rule.

`riverd credentials renew -D PATH` requires the stopped instance lock. It
validates the complete current credential generation except that expiry is
allowed; corrupt, missing, or mismatched accepted material is `CORRUPTION`, not
repair. It checked-adds one to the positive `long` generation before mutation;
`Long.MAX_VALUE` returns `RESOURCE_EXHAUSTED` and is never wrapped.

The 16-byte random operation nonce is lowercase 32-hex. The only transaction
namespace is
`security/.renew-<old-generation>-to-<new-generation>-<operation-nonce>` and
contains `renewal.properties`, `new-generation`, and
`security.properties.stage`. `renewal.properties` binds the incarnation,
old/new generations, nonce, old security-record checksum, final archive name,
and all three fixed child names with this exact schema:

```text
format=riverd-renewal-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
old-credential-generation=<positive-decimal-long>
new-credential-generation=<positive-decimal-long>
operation-nonce=<32-lowercase-hex>
old-security-record-sha256=<64-lowercase-hex>
archive-name=credential-<old-generation>-sha256-<public-manifest-digest>
new-generation-name=new-generation
security-stage-name=security.properties.stage
record-sha256=<64-lowercase-hex>
```

The only public
archive staging/final names are respectively
`security/archive/.credential-<old-generation>-sha256-<public-manifest-digest>.stage-<operation-nonce>`
and
`security/archive/credential-<old-generation>-sha256-<public-manifest-digest>`.
The archive contains only `server-certificate.der` and this non-secret record:

```text
format=riverd-credential-public-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
credential-generation=<positive-decimal-long>
key-algorithm=ec-secp256r1
signature-algorithm=sha256-with-ecdsa
certificate-not-before-epoch-second=<nonnegative-decimal-long>
certificate-not-after-epoch-second=<positive-decimal-long>
server-certificate-file=server-certificate.der
server-certificate-sha256=<64-lowercase-hex>
record-sha256=<64-lowercase-hex>
```

The lowercase SHA-256 of the complete public-manifest bytes is the digest in
both names and success output. The credential-equivalent old
`security.properties`, token verifier, token, and private-key data never enter
the archive. Renewal performs these exact durable steps:

1. It creates the transaction namespace, writes and forces the renewal record,
   and forces `security/`.
2. It creates the archive staging directory, copies and validates the public
   certificate, derives the public manifest, forces both files and the staging
   directory, atomically renames it without overwrite to the final archive,
   then forces `security/archive/` and `security/`.
3. It creates and validates the new generation under `new-generation`, forces
   each secret/public file and that directory, atomically moves it without
   overwrite to `security/generations/<new-generation>`, then forces
   `security/generations/` and `security/`.
4. It writes and forces `security.properties.stage`, atomically replaces
   `security/security.properties`, and forces `security/`. That force is the
   sole authority switch: before it the old generation authenticates; after it
   only the new generation does.
5. After the new authority revalidates, it unlinks the old token, private key,
   certificate, and old generation directory using checked file keys; forces
   the affected old directory before its removal and then
   `security/generations/` and `security/`; removes the exact transaction
   namespace; and forces `security/`. This is durable logical deletion without
   a physical-media erasure claim.

A crash before the authority switch leaves the old authority. A retry under the
lock finds at most one exactly named valid transaction bound to its checksum,
validates or completes the public archive and new generation in order, and
resumes; incompatible, duplicate, extra, or partially mismatched state is
preserved as `CONFLICT`/`CORRUPTION`. A crash after the authority switch leaves
the new authority. Before any listener or readiness publication, start or renew
must validate it and finish only the bound old-secret unlink and transaction
cleanup; it never loads the old verifier/key. Safe cleanup is limited to the
checked identities and file keys named above. Renewal leaves any prior client
configuration stale; the next successful start replaces it only after binding
its selected endpoint.

| Renewal crash boundary | Authority and only permitted recovery |
| --- | --- |
| Renewal record/stage creation incomplete | Old authority; remove only the checksum/file-key-matching incomplete nonce namespace, force `security/`, and begin a new operation. |
| Public archive staging incomplete | Old authority; validate the renewal record, remove/recreate only that matching archive stage, and resume step 2. |
| Final public archive exists | Old authority; validate its name, manifest digest, certificate, and both directory forces, then resume new-generation construction. |
| `new-generation` is incomplete in the transaction namespace | Old authority; remove/recreate only that matching staged generation and resume step 3. |
| Final `generations/<new>` exists, old security authority remains | Old authority; validate the new generation and archive, then resume manifest staging; never authenticate with new yet. |
| New security stage is complete, old authority remains | Old authority; revalidate all inputs and perform only the replacement and directory force. |
| New security target is visible but its directory force may have crashed | New authority after complete validation; repeat the idempotent `security/` force, then perform only old-secret deletion. |
| New authority is durable, any old secret or transaction stage remains | New authority; before listener bind, unlink only matching old files, force every affected directory, and remove the exact stage namespace. |

An archive/new generation with the right numeric generation but the wrong
nonce, checksum, certificate, or file key is never adopted.

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
reloading a stale copied configuration whose referenced old secret has been
removed returns `IO_FAILURE`. These are distinct outcomes: loaded-old material
reaches authentication and is denied, while missing reload material never
constructs a connection. Neither can authenticate. The operator uses the
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
`tic-d2e9`, and `tic-b901` add stop, listing, and expiry/recovery commands
against the already accepted formats. `tic-9640` proves their composed recovery matrix;
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
