# ADR 0014: `riverd` instance security and client discovery

Status: Accepted, amended 2026-09-08

## Authority and scope

The [riverd CLI contract](../riverd-cli.md) owns the user-facing interface.
This ADR defines the security and durable lifecycle mechanisms for the first
installed `riverd`. It ratifies
[`docs/plans/riverd-standalone-server-plan.md`](../plans/riverd-standalone-server-plan.md)
and replaces every alternative or deferred description of the same behavior.
The decision closes the ownership and deletion gaps inventoried by `tic-de1d`, merged at
`4827f84e349c0aed7b4c585aede13d505efb1eb9` and recorded closed at
`5b120a179055a9ec1c640152b4e2bf057d23f5ac`.
The earlier audit design and its measurements remain historical records. They
are superseded as an active River contract by the 2026-09-08 amendment below.

[ADR 0012](0012-embedded-api-and-protocol-boundaries.md) remains authoritative
for the embedded API, protocol, client, server, TLS, authentication, and
authorization boundaries. This ADR supplies the
launcher-owned identity, filesystem, discovery, and recovery
contract. River is pre-V1: the authenticated lifecycle replaces all unreleased
plain production paths; it does not wrap or preserve them.

## 2026-09-08 audit deferral amendment

SQL/security audit collection is off the immediate roadmap. It is not required
for `riverd` readiness, SQL admission, authorization, lifecycle acceptance, or
the installed-server milestone, and there is no `riverd audit archive` command
in the current contract. Authentication, authorization, TLS, instance
ownership, filesystem safety, database/WAL durability, recovery, and resource
cleanup remain required.

The former `tic-a221` design, `tic-72ea` implementation, audit archive work, and
their measurements are preserved as superseded historical evidence. They do
not create a prerequisite, placeholder implementation, compatibility path, or
near-term study. Reconsider audit only after a concrete design demonstrates
neutral impact on TPS, latency, and resource use and supports that performance
property through its architecture and acceptance evidence.

## 2026-09-07 platform requirement amendment

The user requires macOS and Linux support, plus at least one modern Windows
filesystem. This amendment selects APFS, ext4/XFS, and NTFS, replaces the
Linux-only filesystem mechanism and per-JAR qualification contract, and keeps
the security, durability, and recovery outcomes. Historical reviews below
accepted the earlier design; they do not establish implementation or acceptance
of this amendment. The required-platform matrix is part of standalone delivery.

## Command contract and mutation boundary

The [riverd command-line contract](../riverd-cli.md) owns commands, options,
defaults, help, user-facing operation and exit codes. This ADR owns the security,
identity, filesystem, publication and recovery mechanisms behind that contract.

Validate command input and path collisions before filesystem or network
mutation. Help and version are side-effect free. Platform shutdown and the
cooperative stop request enter one ordered shutdown owner; forced termination
follows crash recovery. Command changes do not weaken these guarantees.

## Security and lifecycle outcomes

The owning boundary uses these exact outcomes:

| Boundary | Status |
| --- | --- |
| Bad syntax, duplicate option, invalid path/listen/duration/count/config field | `INVALID_EXTERNAL_INPUT` |
| Unsupported filesystem security or process-identity capability | `FEATURE_NOT_SUPPORTED` |
| Wrong owner/mode, symlink/special file, denied file access, wrong TLS peer/token/proof, or credential validity | `ACCESS_DENIED` |
| Existing ready file, held instance, bind collision, archive name collision, or incompatible staged retry | `CONFLICT` |
| Missing/stale/reused/mismatched runtime owner or unexpectedly free live lock | `NOT_OWNER` |
| Malformed, missing after authority, mismatched, torn, or checksum-invalid persistent state | `CORRUPTION` |
| Cancelled/deadline/stop wait | `CANCELLED`, `TIMEOUT`, or `TIMEOUT` respectively |
| Java/NIO, control-publication, or lifecycle I/O failure | `IO_FAILURE` |
| Ready visibility committed, then target/parent/source force fails, or stdout-only final transfer/flush is ambiguous | Irrevocable ready observation followed by terminal `IO_FAILURE`, ordered shutdown, and eventual exit 1 |
| Impossible River-owned state | `INVARIANT_BROKEN` and the owning fatal fence |

A well-formed but expired/not-yet-valid credential is `ACCESS_DENIED` on
`start`; `credentials renew` may validate and replace either out-of-interval
generation.
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
  .instance-<nonce>.stage
  .riverd-bootstrap-<nonce>/
  database/
  security/
    security.properties
    client.properties
    renewal.intent
    .renewal-intent-<nonce>.stage
    .security-<nonce>.stage
    generations/<credential-generation>/
      token.bin
      server-private-key.pkcs8
      server-certificate.der
    archive/
    .renew-<old>-to-<new>-<nonce>/
```

The launcher resolves an absolute normalized path through the nearest existing
ancestor and uses the real ancestor path as the base. Before mutation it proves
that the prospective data directory, each fixed child, the per-user runtime
directory, and
an explicit ready target have only the declared containment relation of each
fixed child directly beneath `DATADIR`. Every other equality,
ancestor/descendant overlap, symbolic-link alias, and existing-file-key alias
(including a hard link) is rejected as `INVALID_EXTERNAL_INPUT`. The ready
target must be outside the data and runtime trees. A missing leaf is compared as its verified
real parent plus one validated component; the comparison is repeated after
creation and before publication.

### Required platforms and filesystem guarantees

The first supported standalone `riverd` must run on macOS with local APFS,
Linux with local ext4 and XFS, and Windows with local NTFS. NTFS is the initial
required Windows filesystem; adding another does not remove that requirement.
These are delivery requirements, not claims that the implementations or tests
already exist. No required platform is an optional follow-up.

River requires the same outcomes on each platform:

- One process owns a database instance for writing. Competing starts cannot
  bypass the instance lock, including through an alias of the data directory.
- Credentials and writable control files are accessible only to their owner,
  apart from privileged system administrators outside this threat boundary.
  Inherited permissions must not grant another unprivileged user access.
- An open, publication, or cleanup acts on the verified object. Symlinks,
  junctions, reparse points, hard links, case aliases, and namespace changes
  must not redirect it to an unrelated object or expose credentials.
- Exclusive publication never overwrites an existing target. Replacement is
  atomic to readers. A crash leaves an old or new accepted authority, or a
  recognized incomplete state which the owning recovery protocol can finish.
- A successful durable publication survives the supported crash and power-loss
  conditions. File content and every affected namespace change must be durable
  before success. A failed or indeterminate durability operation retains its
  error and recovery behavior; visibility alone is not durable success.
- Shutdown, cancellation, and failure release owned resources and preserve
  unrelated files and processes. Runtime probes and tests leave no secrets.

The planned `RiverDaemonFileSystem` boundary must implement these
operations using the platform's permission, identity, locking, publication, and
flush facilities. Reuse River's durable-I/O contracts where they already own
an operation. Keep OS decisions inside platform adapters, not in transaction,
WAL, credential, or lifecycle policy. Native calls are permitted behind that
boundary when supported Java APIs cannot meet a required guarantee; native
code must pass the same contract and failure tests.

POSIX modes `0700` for directories and `0600` for files are one implementation
of owner-only access. They are not a Windows requirement and must not be used
as proof that an overriding ACL is harmless. Windows uses an owner-restricted
security descriptor with effective inherited access checked. Each platform
must prove effective access according to its own permission model.
`SecureDirectoryStream`, a particular Java channel class, hard-link publication,
and `FileChannel.force` on a directory are implementation choices, not universal
admission requirements. Their absence alone cannot justify rejecting a required
platform. The implementation ticket must specify and test the concrete
operations which provide each guarantee on that platform before accepting it.

An adapter must retain a stable object identity and prevent redirection across
validation and use; a path check followed by an unchecked path operation is
insufficient. The scope of trusted same-account actors and privileged
administrators is unchanged. Missing intermediate path components may be
created only through validated parent ownership and identity. Ready-file,
runtime, and data trees retain the containment and collision rules above.

The publication and recovery state machines below remain authoritative.
Immutable stages belong beside their regular-file targets. Any platform
implementation must preserve exclusive versus replacement behavior, authority
ordering, bound stage identity, and safe recovery after interruption. Cross-
parent directory moves must make changes to both parents durable. A platform
without a suitable primitive must implement an equivalent recoverable protocol
in this owner; it must not silently omit a flush, weaken atomicity, or introduce
a second lifecycle path. References below to force, file keys, owner/mode checks,
and no-follow operations name these guarantees through the adapter, rather than
mandating a particular Java or POSIX API.

Exclusive-publication recovery accepts a target and leftover stage as aliases
only when stable object identity, canonical contents, and expected checksum all
match. Make the target and affected namespace durable before removing the exact
owned stage, then make that removal durable. Preserve a mismatched or ambiguous
pair. An implementation which publishes by rename instead must document its
possible interrupted states and prove the same exclusive-publication outcome;
it does not create fictitious hard-link states. Recovery never infers ownership
from a filename alone.

### Validation and supported configurations

`tic-485d` owns the shared operations and APFS implementation; `tic-867d`
and `tic-b75d` provide the Linux and Windows adapters. `tic-615d` consumes them
for instance credentials. `tic-95e8` exercises the
installed distribution's API, permission, publication-race, and process-crash
behavior on every required platform. `tic-9640` owns power-loss and operational
qualification across the required filesystem matrix, including the database
WAL and control-file path as well as launcher files. A working launcher alone
cannot establish database durability.

Evidence records the tested source, adapter implementation, JDK/native runtime,
OS/filesystem, relevant mount and storage settings, and test method. Separate
runtime-observable capabilities from externally established storage guarantees.
Runtime checks reject a concrete missing required capability, insecure path, or
unsupported storage configuration before dependent mutation; they do not infer
power-loss durability from a successful scratch probe. Network and other
unqualified filesystems are not implicitly supported by the desktop/server OS
requirements.

Qualification follows the operation and its relevant platform dependencies.
Changes to permission, identity, publication, flush, recovery, or those platform
dependencies require review and affected tests. An unrelated launcher change
or different JAR checksum does not automatically require repeating the entire
power-loss campaign. Record why existing evidence remains applicable and test
the changed path. There is no per-launcher-JAR qualification record or mandatory
runtime match to a single machine's JDK/kernel/device tuple.

Insufficient capability returns `FEATURE_NOT_SUPPORTED`; insecure ownership,
access, or path redirection returns `ACCESS_DENIED`. Unintended data/runtime/
ready-path collisions return `INVALID_EXTERNAL_INPUT`. Required platforms must
have implementations which pass, rather than permanently returning unsupported.
No weaker-durability or no-authentication mode is introduced.

`--ready-file` uses the same owner-only, verified-parent rules and is never
overwritten. The per-user runtime directory is `.river/run` under the resolved
user home, with owner-only directories and records. Each live instance has one
record at `.river/run/<sha256(normalized-datadir)>.properties`. `$HOME` in this ADR denotes
that platform's user home, not a required environment variable. Path parsing,
normalization, containment, and identity checks must cover Windows drive paths,
separators, case aliases, and reparse points as well as Unix paths. Apart from
the instance tree, runtime directory, and explicit ready file, `riverd` writes nothing.
Paths containing NUL, CR, LF, `=`, or another Unicode control character are
rejected; version 1 has no encoding alternative.

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
most 4096 bytes; security, client, runtime, lock, stop-request, ready-file,
renewal-intent, and credential-public records are each at most
8192 bytes. Stdout is not a persistent-properties record and has no checksum.
These are format framing bounds, not workload caps. Oversize external input is
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

`instance.lock` is acquired before its record is trusted. While holding the
exclusive OS lock on the same verified file key, the launcher handles
pre-bootstrap lock bytes as follows. A newly created zero-length file is filled
with the new incarnation/owner record and forced before any bootstrap stage. A
pre-existing canonical record may be replaced only when its PID/start
is proved absent. A torn, checksum-invalid, or otherwise unparsable record may
be replaced only when `instance.properties`, `bootstrap.properties`, every
bootstrap/stop stage, and every fixed child are absent and `DATADIR` contains
only that exact locked file key. This is safe because no instance or bootstrap
authority exists; the old bytes are retained in the failure diagnostic, the
new canonical record is written through the locked channel, then the file and
`DATADIR` are forced. If a canonical record names a live process despite the
free lock, or any other entry exists, state is preserved as `CORRUPTION`.

When a valid bootstrap authority exists, a torn/stale lock record is replaced
only after the bootstrap PID/start is proved absent and every staged or
final entry validates against its incarnation and nonce. With an accepted
instance authority, replacement instead requires that incarnation plus absent
old-process proof; the acquired launcher retains the prior lock bytes until any
matching stale ready/runtime cleanup completes, then publishes its new
lock record. No lock-record recovery changes bootstrap, instance,
database or credential authority.

The temporary first-create record has this exact ordered schema:

```text
format=riverd-bootstrap-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
attempt-nonce=<32-lowercase-hex>
database-name=database
security-name=security
staging-name=.riverd-bootstrap-<attempt-nonce>
instance-stage-name=.instance-<attempt-nonce>.stage
record-sha256=<64-lowercase-hex>
```

The nonzero attempt nonce is 16 random bytes encoded as 32 lowercase hex
characters. Its only staging namespace is
`.riverd-bootstrap-<attempt-nonce>`; inside it the only names are `database`
and `security`. The instance-authority stage is the bootstrap-bound
`.instance-<attempt-nonce>.stage` directly in `DATADIR`, the same parent as its
target. While holding the forced lock record, first creation performs these
exact durable steps:

1. It validates an empty/new `DATADIR`, fixes the incarnation and nonce, writes
   and forces `.bootstrap-<nonce>.stage`, atomically publishes it without
   overwrite as `bootstrap.properties`, and forces `DATADIR`.
2. It creates the nonce staging directory with the fixed-component directory
   operation, validates its identity, and forces `DATADIR`.
3. It creates and forces the bound database, initial credential generation and
   manifest beneath the staging namespace; it forces each file and containing
   directory.
4. It atomically publishes staged `database`, then `security` without overwrite
   to their final fixed names, forcing `DATADIR` after each rename. A staged
   child's bootstrap/incarnation header and file key are
   checked immediately before its rename.
5. It writes and forces the bound `.instance-<attempt-nonce>.stage` directly in
   `DATADIR`, uses same-parent exclusive-file publication without overwrite as
   `instance.properties`, and forces `DATADIR`. This directory force is the
   sole instance-authority commit.
6. It removes only the matching bootstrap record and now-empty matching nonce
   namespace plus any already-validated instance-stage alias residue, then
   forces `DATADIR`.

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
| Bootstrap target and stage are matching hard-link aliases | Apply exclusive-file alias recovery, retain the target as intent, and resume namespace creation with the recorded identity. |
| Bootstrap authoritative, namespace absent or empty | Recreate the one recorded namespace and resume step 3 with the recorded incarnation/nonce. |
| A staged child is partial, no corresponding final child | Validate its bootstrap identity; remove/recreate only that child, force its parent, and resume. |
| Exactly the ordered prefix of `database` and `security` is final | Validate the prefix and remaining staged identities, then perform only the next rename and force. |
| Bound `.instance-<nonce>.stage` exists in `DATADIR` but is partial/noncanonical, instance target absent | Only when the canonical bootstrap binds that exact direct-child name and nonce and all three final children validate: require one owner/mode-correct regular file, no other direct child in that verified parent with its file key, and one unchanged non-null file key across no-follow lookup, adapter open/read, parent scan, and immediate pre-remove lookup. Remove only that exact name through the verified `DATADIR` handle, force `DATADIR`, and recreate step 5. An unbound name, alias, symlink/special/wrong-type object, or missing/changed/null file key is preserved as `CONFLICT`/`CORRUPTION`. |
| Bound `.instance-<nonce>.stage` is complete in `DATADIR`, instance target absent | Revalidate all three final children, then same-parent publish/force the recorded instance authority. |
| Instance target is complete but its matching `.instance-<nonce>.stage` alias or last directory force remains | Apply same-parent alias recovery if needed, revalidate all final bytes and identities, and repeat the idempotent `DATADIR` force before treating authority as committed. |
| Instance authority is committed and bootstrap/namespace remains | Validate authority and remove only matching bootstrap/stage residue, then force `DATADIR`. |

Any state outside exactly one row, including a gap in the published-child
order, is preserved and fails closed.

After `instance.properties` exists, the instance is authoritative. Every
required database and security artifacts must validate against it;
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
public archive, and never enter argv, environment, URLs, readiness, runtime,
or logs.

The launcher reads token and private-key bytes into fixed owned buffers and
zeros every caller scratch buffer in `finally` on success and every failure.
Ownership of the verifier buffer transfers exactly once to `TokenAuthenticator`;
that object owns and zeros the stored HMAC key, offered-proof scratch, and
expected-proof scratch. Its destruction is idempotent and occurs only after the
listener rejects admission, all connection workers have stopped, and River has
completed the public JSSE cleanup below. Private-key DER buffers are zeroed
after key-manager construction.

This contract does not claim that generic JSSE erases provider-owned session,
ticket, key-manager, or `SSLContext` internals. After listener close and worker
join, River takes the server `SSLSessionContext`, enumerates its public
`getIds()` snapshot, calls `invalidate()` on every session returned by
`getSession(id)` and on every connection session still referenced by River,
then retains one cleanup-local Bouncy Castle key reference while clearing every
other River listener, engine, session, manager, context, and key reference.
Enumeration/invalidation failure is `IO_FAILURE`; cleanup continues. The local
key is then `destroy()`ed and checked with `isDestroyed()` before its last
reference is cleared. Any destroy throw, including `DestroyFailedException`, or
`isDestroyed()==false` is `IO_FAILURE`; River still clears its reference and
completes shutdown. Only River-owned arrays and the
successfully destroyed provider key have a zeroization guarantee. Finally
`TokenAuthenticator.destroy()` zeros its River-owned buffers. Any failure is
combined with the lifecycle's existing first-failure rule.

TLS resumption may exist inside the selected JSSE provider, but it conveys only
transport state. Every new or resumed connection still passes current token
authentication and the application validity/admission fence; every statement
on an existing connection passes that same fence. Security therefore does not
depend on generic ticket/cache erasure. Durable unlink likewise claims logical
deletion after directory force, never physical-media or provider-memory erasure.

## Client configuration and authenticated-only migration

After listener bind selects the concrete port, `river-server-app` atomically
replaces its own `security/client.properties` under the instance lock and
forces the security directory before runtime/readiness publication.
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
protocol=river-v5
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
wrapper, inactive plain flag, or unauthenticated remote mode after `tic-ec50`.

## Historical audit admission design (superseded)

The following section records the former `tic-a221` proposal for historical
traceability only. It is not a current requirement, admission gate, resource
budget, or implementation direction. SQL/security audit collection is deferred
until a concrete design proves neutral TPS, latency, and resource impact.

For the historical candidate, the former accepted `tic-a221` event and
state-machine contract was normative:

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

The former design made audit mandatory for remote riverd. That decision is
superseded; the current riverd path has no audit file, coordinator, queue,
staging arena, audit thread, audit force, or per-row audit work.

## Readiness and runtime record format

After identity/security/database validation, listener bind, current
client-configuration publication and runtime publication succeed, start prepares
these ordered UTF-8 records for standard output:

```text
riverd_datadir=<normalized-absolute-path>
riverd_data=<normalized-absolute-path>/database
riverd_identity=<normalized-absolute-path>/instance.properties
riverd_runtime_file=<normalized-absolute-path>/.river/run/<sha256-datadir>.properties
riverd_listen_address=<localhost|127.0.0.1|::1>
riverd_listen_port=<1..65535>
riverd_pid=<positive-decimal-long>
riverd_protocol=river-v5
riverd_transport=tls-v1.3
riverd_client_config=<normalized-absolute-path>
riverd_server_certificate_sha256=<64-lowercase-hex>
riverd_status=ready
```

The protocol value comes from `river-protocol`, not launcher duplication. The
two readiness modes have one commit point each and never require both sinks:

- With `--ready-file`, the launcher writes and forces this canonical bounded
  record to a `.<target-name>.riverd-ready-<owner-nonce>.stage` file in the
  verified target parent, then uses the exclusive immutable-file sequence to
  link it without overwrite to the target. Target-link visibility is the sole,
  irrevocable readiness commit. It then forces the target, forces the parent,
  unlinks the matching stage, and forces the parent again. A crash before the
  link leaves no ready target; a matching stage follows alias recovery. A crash
  after the link leaves a complete target and never a partial record. Any
  target/parent/source-unlink force failure after visibility cannot retract the
  commitment: it sets terminal `IO_FAILURE`, invokes ordered lifecycle cleanup,
  emits the final failure records on stderr where possible, and exits 1. It
  never continues indefinitely after that durability failure. Only after the
  publication sequence succeeds does start write/flush standard output as a
  best-effort mirror. Broken/closed stdout, partial mirror output, or mirror
  flush failure is a nonterminal warning and neither retracts the ready file nor
  stops the service.
- Without `--ready-file`, the launcher writes and flushes all lines before
  `riverd_status=ready`, then writes that complete final LF-terminated line as
  one bounded final record and flushes it. The external readiness commit is the
  consumer's observation of that complete final record; a complete record is
  irrevocable even if the following flush reports failure. A prefix
  write/flush failure, or a final write known to have transferred fewer than
  the complete record, closes the listener, workers, database, and lock in
  lifecycle order; removes only matching runtime/client records and
  stages; emits no later readiness; and exits `IO_FAILURE`. If the final write
  or flush fails after a complete record may have become observable, the
  launcher cannot retract it: it sets terminal `IO_FAILURE`, performs ordered
  cleanup, emits final failure records on stderr where possible, and eventually
  exits 1. The complete ready observation remains a historical commit even
  though its service then terminates; the launcher never reports a clean exit.

The ready file contains no secret and has this exact schema; its status field is
immediately before the universal final checksum:

```text
format=riverd-ready-v2
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
data=<normalized-absolute-path>/database
identity=<normalized-absolute-path>/instance.properties
runtime-file=<normalized-absolute-path>/.river/run/<sha256-datadir>.properties
listen-address=<localhost|127.0.0.1|::1>
listen-port=<1..65535>
pid=<positive-decimal-long>
protocol=river-v5
transport=tls-v1.3
client-config=<normalized-absolute-path>
server-certificate-sha256=<64-lowercase-hex>
owner-nonce=<32-lowercase-hex>
status=ready
record-sha256=<64-lowercase-hex>
```

The single runtime record is published atomically at
`$HOME/.river/run/<sha256(normalized-datadir)>.properties` while the start
process owns the lock. Its exact ordered schema is:

```text
format=riverd-runtime-v2
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
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
format=riverd-lock-v2
datadir=<normalized-absolute-path>
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
pid=<positive-decimal-long>
process-start-epoch-millis=<nonnegative-decimal-long>
owner-nonce=<32-lowercase-hex>
record-sha256=<64-lowercase-hex>
```

Process start instant must be available; otherwise start returns
`FEATURE_NOT_SUPPORTED`.

The runtime filename is lowercase SHA-256 of the UTF-8 normalized datadir with
`.properties` appended. It is published after listener readiness and removed
only when its full identity matches. `ps` validates every direct regular child
of `.river/run`, verifies each filename against the record's datadir and the
corresponding held datadir lock. It prints verified instances sorted by
datadir, ignores inactive records, warns only for malformed or unreadable
records, never scans arbitrary processes, and never signals. Its empty output
remains the exact guidance in the plan. Under the instance lock, a later `start` may
replace only the canonical same-datadir record after the exact recorded process
is proved absent. Malformed or mismatched collisions are preserved and return
`CORRUPTION`/`CONFLICT`; `ps` itself never performs this recovery.

An existing ready target is normally `CONFLICT` and is never overwritten. The
only stale-ready recovery occurs after `start` holds the instance lock: a
canonical old runtime record and the pre-replacement lock record must bind the
same incarnation, normalized datadir, owner nonce, PID/start, and
canonical checksums to a process proved absent; the ready file must
be a canonical `riverd-ready-v2` record with the same incarnation, owner nonce,
PID, runtime path, and client path. After revalidating the external ready parent
and target file key, start
deletes that exact ready file through its verified parent handle and forces its parent, then removes
and forces only the matching runtime record. It may then publish a
new runtime/ready generation. A missing binding, live/unverifiable process,
checksum mismatch, different file key, different target contents, or unrelated
existing ready file is preserved as `NOT_OWNER`, `CORRUPTION`, or `CONFLICT`
and receives no cleanup.

## Foreground lifecycle and exact stop fencing

Start owns resources in this order: secure directory handle, instance lock,
validated identity/security, `RiverDatabase`, authenticated
`LoopbackRiverServer`, current client configuration, runtime record, ready file
or stdout commitment. The
shutdown hook is installed after database ownership. Startup failure and
shutdown release in reverse order except that the listener always closes
before the database. One idempotent lifecycle owner serves normal close,
Unix SIGINT/SIGTERM, Windows console shutdown, and the
cooperative request below; it reports both close statuses, preserves the first
fatal outcome, removes only matching readiness/runtime/control
records, and never deletes database/identity/security data.

`riverd stop` never signals a PID, calls `ProcessHandle.destroy`, acquires or
steals the live lock, or selects a process by an identifier that can be reused.
Before creating a stage it scans through the verified parent handle the direct instance-root control names:
the fixed `stop.request` and every `.stop-accepted-<nonce>`.
Staging files are private to the caller writing them: another live caller must
never read or publish them, because their contents may still be incomplete. One canonical accepted receipt bound to the instance
and currently contended lock owner is joined immediately even if shutdown has
already removed runtime state. One canonical pending request is joined after
its runtime checksum and lock owner validate. Multiple accepted receipts,
unknown control names, bad name/content nonce, or malformed control bytes are
`CORRUPTION` and are preserved; a well-formed different owner is `NOT_OWNER`.

Only when there is no pending or accepted request does the CLI securely
validate the bounded runtime and lock records, requiring datadir, incarnation,
PID, start instant, and owner nonce to match and an exclusive
nonblocking lock attempt to remain contended. PID/start are evidence
and output only. It then publishes a cooperative request into the verified
instance root with this exact schema:

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
unchanged lock/runtime file keys and checksums, uses exclusive immutable-file
publication for `stop.request`, and forces `DATADIR`. It then performs the late-
publication check: re-read lock/runtime and scan pending/accepted controls. A
still-matching pending request is joined; a matching accepted receipt is joined
even if runtime disappeared during shutdown. If ownership changed or became
free while the exact request remains pending, the CLI deletes only that request
and its stage by checksum/file key, forces `DATADIR`, and returns `NOT_OWNER`.
It never deletes the accepted receipt. If another request was accepted before
its own publication completed, it removes only its own redundant pending
request and joins that receipt.

The lock-owning server's sole
lifecycle-control thread checks this fixed file before readiness and at least
every 100 milliseconds while serving. It validates checksum, incarnation,
owner nonce, and runtime checksum, then atomically renames it without overwrite
to `.stop-accepted-<request-nonce>`, forces `DATADIR`, and only then invokes the
same idempotent listener-first lifecycle used by a direct platform shutdown (Unix SIGINT/SIGTERM or Windows console shutdown).

Concurrent and repeated operations are deterministic. An exact retry joins its
recorded nonce. If another CLI has already published a valid request for the
same owner/runtime, every contender removes only its own unpublished stage and
joins the published request regardless of its newly generated nonce. A request
for a different owner/runtime is `NOT_OWNER`; malformed control state or a
multiple/mismatched accepted-receipt collision is `CORRUPTION` and is
preserved. A single matching accepted receipt is always an idempotent join.
Before acceptance,
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
that matching receipt, and forces `DATADIR` before readiness. During orderly
shutdown the receipt is retained while the lifecycle closes listener, workers,
public JSSE state, authenticator, and database; removes and forces the matching
external ready target and runtime record; then deletes and
forces the accepted receipt as the final filesystem control record immediately
before releasing the lock. Stop reports `OK` only after the lock is free and
matching runtime, ready target, request, and accepted receipt are
absent; otherwise it waits until timeout. A completed retry sees no live owner
and returns `NOT_OWNER`.

| Stop crash/publication boundary | Only permitted recovery |
| --- | --- |
| One or more CLI stages exist, `stop.request` absent | Each caller publishes or removes only its own stage. Other callers ignore unpublished stages. A new lock owner may remove a complete stage bound to the proved-absent old owner; incomplete stages are preserved and do not prevent startup or stop. |
| Request link is visible and its directory force fails or the CLI crashes | The request remains authoritative for the live owner; the CLI/later retry applies matching stage/target alias recovery, joins, and never retracts the publication. |
| Request is forced, server has not accepted | The server accepts it or timeout removes exactly that file; the atomic rename/remove winner decides. |
| Accepted receipt is forced, server is shutting down | Every matching prepublication or retry scan idempotently joins it; no CLI cancels it; contenders wait for cleanup or return `TIMEOUT`. |
| Server crashes before acceptance | The next lock owner proves old-owner absence, removes the matching request/stage, and directory-forces before readiness. |
| Server crashes after acceptance | The next lock owner validates recovery, removes only the matching receipt, and directory-forces before readiness; it never replays shutdown. |

## Historical audit archive design (superseded)

`riverd audit archive` is removed from the current CLI contract. The following
description is retained only as historical evidence of the former design; it
does not authorize a command or create an implementation prerequisite.

The former `riverd audit archive -D PATH` required the stopped instance lock and no live
owner or pending slot. It performed the accepted `tic-a221` five-step protocol:
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
credential-validity fence and checked millisecond `notBefore`/exclusive
`notAfter` bounds. Every new, resumed, or existing-session authentication and
every statement-admission attempt performs one `System.currentTimeMillis()`
read and one fence read before effect. If `now < notBefore` or
`now >= notAfter`, the observer atomically closes the fence permanently and
invokes the lifecycle owner. A primitive-state watcher calls the same check at
least once per second so an idle server also exits; admission correctness does
not depend on watcher timing. The listener stops accepting, any later request
 on an existing or resumed transport is denied with `ACCESS_DENIED`, and no new
 statement effect is admitted. Work already admitted before the fence completes normally
or follows ordinary shutdown cancel/rollback.

The launcher then closes listener/workers, performs the honest public-JSSE and
River-buffer cleanup above, closes the database, removes matching
discovery/control records, releases the lock, and exits 1 with `ACCESS_DENIED`
and renewal guidance. The fence never reopens if the clock returns to the valid
interval. Restart remains `ACCESS_DENIED` until current time is within the
generation bounds or offline renewal publishes a valid generation; a cached or
resumed transport cannot bypass application authentication/admission.

`tic-b901` owns the validity-fence cost evidence. It proves by counters that
each authentication/statement admission performs exactly one wall-clock read
and one fence read, and by warmed JFR that those checks allocate zero
River-owned bytes. It also records five 30-second fixed authenticated-query
samples for the parent and candidate at 1, 4, and 16 clients, interleaved
`C,A,A,C,C,A,A,C,C,A`, with identical SQL, seed, resource plan, durability,
JDK, and host. Report successful operations/second, p99.9 latency, CPU per
admission, clock/fence reads, allocation, errors, and retries for every sample. Zero
allocation and correctness are absolute; a repeated throughput/latency/CPU
shift outside adjacent-sample variation is investigated with longer
interleaving and cannot be silently waived. This evidence is separate from and
does not add an audit performance gate to credential renewal.

`riverd credentials renew -D PATH` requires the stopped instance lock. It
validates the complete current credential generation except that current-time
validity is not required; corrupt, missing, or mismatched accepted material is
`CORRUPTION`, not repair. It checked-adds one to the positive `long` generation before mutation;
`Long.MAX_VALUE` returns `RESOURCE_EXHAUSTED` and is never wrapped.

The 16-byte random operation nonce is lowercase 32-hex. The only transaction
namespace is
`security/.renew-<old-generation>-to-<new-generation>-<operation-nonce>` and
contains only `new-generation`. The authority stage is
`security/.security-<operation-nonce>.stage`, directly beside its
`security.properties` target and bound by the intent below. Durable intent is
outside and precedes the namespace: the launcher derives the public archive
digest without mutation, writes and forces
`security/.renewal-intent-<operation-nonce>.stage`, then exclusively publishes
and forces `security/renewal.intent` before creating the namespace. Its exact
schema is:

```text
format=riverd-renewal-intent-v1
database-incarnation-high=<signed-decimal-long>
database-incarnation-low=<signed-decimal-long>
old-credential-generation=<positive-decimal-long>
new-credential-generation=<positive-decimal-long>
operation-nonce=<32-lowercase-hex>
old-security-record-sha256=<64-lowercase-hex>
archive-name=credential-<old-generation>-sha256-<public-manifest-digest>
namespace-name=.renew-<old-generation>-to-<new-generation>-<operation-nonce>
new-generation-name=new-generation
security-stage-name=.security-<operation-nonce>.stage
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

1. It forces the intent stage, exclusively links it as `renewal.intent`, forces
   the target and `security/`, unlinks the stage, and forces `security/` again.
   This is the intent commit; no transaction namespace exists before it.
2. It creates the exact namespace named by the intent, revalidates it through
   handle, and forces `security/`.
3. It creates the archive staging directory, copies and validates the public
   certificate, derives the public manifest, forces both files and the staging
   directory, atomically renames it without overwrite to the final archive,
   then forces `security/archive/` and `security/`.
4. It creates and validates the new generation under `new-generation`, forces
   each secret/public file and that directory, atomically moves it without
   overwrite to `security/generations/<new-generation>`, then forces
   `security/generations/` and `security/`.
5. It writes and forces `security/.security-<operation-nonce>.stage`, performs
   same-parent atomic replacement of `security/security.properties`, and
   forces `security/`. That force is the sole authority switch: before it the
   old generation authenticates; after it only the new generation does.
6. After the new authority revalidates, it unlinks the old token, private key,
   certificate, and old generation directory using checked file keys; forces
   the affected old directory before its removal and then
   `security/generations/` and `security/`; removes and forces the exact
   transaction namespace; then deletes `renewal.intent` by checksum/file key as
   the final record and forces `security/`. This is durable logical deletion
   without a physical-media erasure claim.

A crash before the authority switch leaves the old authority. A retry under the
lock finds at most one valid `renewal.intent` bound to its checksum,
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
| Intent stage exists; final intent and namespace are absent | Old authority; validate its current-authority checksum/file key, remove it, force `security/`, and begin a new operation. An unvalidated stage is preserved. |
| Intent target and stage are matching hard-link aliases | Old authority; complete exclusive-file alias recovery, retain the forced target as intent, and create only its named namespace. |
| Namespace exists without forced `renewal.intent` | No recoverable intent; preserve as `CORRUPTION`. Never infer identity from the namespace name or remove it. |
| Forced/visible intent exists; namespace is absent | Old authority; validate and repeat target/`security/` forces, create only its named namespace, force `security/`, then resume step 3. |
| Intent and its empty namespace exist | Old authority; validate both, repeat the namespace and `security/` forces, then resume step 3. |
| Public archive staging incomplete | Old authority; validate the intent, remove/recreate only that matching archive stage, and resume step 3. |
| Final public archive exists | Old authority; validate its name, manifest digest, certificate, and both directory forces, then resume new-generation construction. |
| `new-generation` is incomplete in the transaction namespace | Old authority; remove/recreate only that matching staged generation and resume step 4. |
| Final `generations/<new>` exists, old security authority remains | Old authority; validate the new generation and archive, then resume manifest staging; never authenticate with new yet. |
| Intent-bound `.security-<nonce>.stage` exists beside the old authority but is partial/noncanonical | Only when the canonical intent binds that exact direct-child name/nonce, the old target still matches `old-security-record-sha256`, and the archive/new-generation prefix validates: require one owner/mode-correct regular file, no other direct child in that verified parent with its file key, and one unchanged non-null file key across no-follow lookup, adapter open/read, `security/` scan, and immediate pre-remove lookup. Remove only that exact name through the verified `security/` handle, force `security/`, and recreate step 5. An unbound name, alias, symlink/special/wrong-type object, or missing/changed/null file key is preserved as `CONFLICT`/`CORRUPTION`. |
| Bound `.security-<nonce>.stage` is complete beside the old authority | Old authority; revalidate all inputs and perform only the same-parent replacement and directory force. |
| New security target is visible but its directory force may have crashed | New authority after complete validation; repeat the idempotent `security/` force, then perform only old-secret deletion. |
| New authority is durable, any old secret, namespace, or intent remains and no security stage exists | New authority; before listener bind, unlink only matching old files, force every affected directory, remove/force the exact namespace, then remove/force the intent last. Any security-stage name after authority replacement is impossible state and is preserved. |

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
returns `ACCESS_DENIED` for a well-formed generation outside its validity
interval and names this stopped-instance command.

## Module owners and deletion gates

| Owner | Responsibility and delivery |
| --- | --- |
| `river-server-app` | Identity, credentials, filesystem-policy composition, command/lifecycle composition, resource plan, readiness, runtime, and credential-renewal operations. `tic-615d` creates this non-empty module with identity/security/config production code; `tic-ec50` adds the installed application and complete composition. |
| `river-server` | Authenticated TLS listener, authentication/authorization, connection and shutdown behavior; never concrete engine composition. |
| `river-client` | One bounded client-configuration parser and pinned authenticated connector. |
| `river-jdbc` / `river-cli` | Public adapters over `river-client`; no duplicate trust/config parser and no optional plain path. |
| `river-bench` | Authenticated River diagnostics only; no launcher defaults or public process contract. |
| `river-engine` / `river-engine-api` | Embedded implementation and public database lifecycle respectively; launcher composes without leaking engine types. |

`tic-ec50` also removes the unused root `river-server -> river-engine`
dependency allowance while adding only used app edges. `tic-95e8` proves the
installed lifecycle and source/compiled absence of plain paths. `tic-0803`,
`tic-d2e9`, and `tic-b901` add stop, listing, and expiry/recovery commands
against these formats after ADR acceptance. `tic-9640` proves their composed
recovery matrix;
`tic-4cb6` publishes the stable external consumer subset only after that gate.

## Historical audit performance contract (superseded)

The former audit thresholds below are retained as historical evidence only.
They are not a current gate or near-term study. Any future audit proposal must
first provide a concrete architecture with neutral TPS, latency, and resource
impact, then pass matched evidence for that property.

The former `tic-72ea` plan required the exact accepted `tic-a221` matched evidence plan. Its
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
single-principal authorization, credentials, exact lifecycle,
authenticated-only River callers, database durability, and WAL recovery are
mandatory and are not included in those deferrals. SQL/security audit
collection and its archive command are deferred.

## Review history

- Candidate `d77793206a4edf43c5ffca207535a33b2526cdf0` was independently
  rejected for readiness, process-stop, filesystem, provider, secret,
  checksum, renewal, help, and path-collision ambiguity.
- Candidate `238d6198ae186824e037b1005483882e2e2be296` closed those findings
  but was independently rejected for incomplete path-operation/durability
  qualification, unimplementable generic-JSSE erasure claims, and remaining
  lock, ready, stop-receipt, and renewal-intent recovery ambiguity.
- Candidate `56b8911a144d9e5accae1d4e3569820891d1fd54` closed those findings
  but was independently rejected for cross-parent authority stages, an
  unspecified SDS channel capability, a non-executable qualification artifact,
  overbroad failure/readiness wording, and an inaccurate stop-scan summary.
- Candidate `a86c5c5c4e66c6f1a846abb7275a78463e35802e` closed those findings
  but was independently rejected for missing partial-authority-stage recovery,
  one stale bootstrap-plan sentence, an overbroad SDS-channel statement, and
  incomplete qualification-name/runtime-versus-evidence exactness.
- Exact pushed candidate `a7ca80184fb91141c2d4266e9a28dd3d53cc1c2e`
  closed every prior finding and was independently accepted with no blocker or
  required finding. Architecture, boundary/security, durable-filesystem and
  lifecycle, and operations/compatibility adversary lenses all passed. The
  reviewer verified the exact clean worktree and remote SHA, valid Ticket
  metadata, and docs/static scope with `tk validate`; no build or runtime test
  was claimed or required for this contract-only ratification.
- This acceptance fixes the public contract and unblocks its named downstream
  implementation tickets. It does not claim that `riverd`, its distribution,
  filesystem qualification, recovery, security, or operational promotion has
  been implemented or validated; those gates remain with their named owners.

## Consequences

The first riverd must support macOS/APFS, Linux/ext4/XFS, and Windows/NTFS
with the same security and durability guarantees. Unsupported storage
configurations do not receive weaker security, accepted corrupt state is preserved rather than
repaired, and credential expiry requires an explicit offline rotation. In return, a process/file consumer
has one non-secret discovery contract and River owns one authenticated remote
path without unreleased compatibility debt.
