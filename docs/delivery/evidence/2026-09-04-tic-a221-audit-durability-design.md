# `tic-a221` scalable security-audit durability and admission

Status: proposed; pending independent concurrency, security, recovery, and
performance acceptance

Evidence class: design and executable evidence plan; no production behavior is
changed or accepted here

## Authority and source

This design is pinned to clean production commit
`0f7916153eeca3d3062f10c6588c7c4d6fb66bf8` and claim commit
`5826434751599ed60909f20fc01cfc9a3095419e` on
`ticket/tic-a221-audit-durability`. Accepted ADR 0012 requires authentication
and statement-admission records to be durable before work is admitted and
requires fail-closed full/corrupt behavior. `SecurityAuditLog`,
`RemoteSessionAuthorizer`, and `SessionEndpoint` are the current implementation
owners.

The standalone riverd plan remains proposed. Its ADR index points to a missing
ADR 0014, so this document is an input to `tic-11a5`, not a replacement ADR.
`tic-11a5` must ratify the final public contract before `tic-72ea` implements
it.

## Current constraint and preserved semantics

The current audit encodes fixed 40-byte records, serializes every caller on one
monitor, writes one record, and performs `CONTENT_AND_METADATA` force before
returning to the authorizer. A configured integer record count is both the file
capacity and admission bound. Existing-file validation is linear; corruption
fails closed. This preserves audit-before-admission, but one filesystem force
per authentication or permission decision serializes concurrent service and
the record-count cap is not a resource contract.

The replacement preserves these semantics:

- no authenticated session or statement/program effect is admitted before its
  decision record is durably covered;
- denied decisions are also recorded before their denial is returned;
- corruption, capacity exhaustion, cancellation, and I/O failure never select
  an unaudited fallback;
- audit diagnostics cannot change the authorization decision; and
- records contain no credential, proof, private key, SQL text, parameter, row,
  or result value.

## Event contract

The security owner defines exactly two durable event classes:

| Event | Produced | Durable payload | Admission meaning |
| --- | --- | --- | --- |
| `AUTHENTICATION_DECISION` | For every `AUTHENTICATE` message whose type and request identity decode far enough for the configured authenticator to evaluate its proof, including wrong length/content and replay | format/version, global sequence, audit generation, instance incarnation, credential generation, configured principal ID, connection/request correlation, allowed/denied, stable status | An allowed session may reach READY only after the record is durable. A denied or malformed proof returns its status only after the denial is durable. A frame that cannot be identified as an authentication message, TLS failure, disconnect, or timeout before evaluation is a bounded transport diagnostic, not a fabricated principal decision. |
| `STATEMENT_ADMISSION_DECISION` | For every actual invocation of the canonical `SessionAuthorizer`, including direct execute/query, prepared validation (`PREPARE`), prepared execute/query, transaction-control/admin SQL, and every transaction-program SQL step that the program really executes | format/version, global sequence, audit generation, instance/principal/session/request correlation, authorization phase, program step or zero, exactly one required permission bit, allowed/denied, stable status | The associated compile/execute/step effect proceeds only after an allowed record is durable. A denial has no associated effect and is returned only after its record is durable. |

`PREPARE_PROGRAM` only retains references to statements already authorized and
audited during their individual `PREPARE`; it creates no new permission
decision. `EXECUTE_PROGRAM` establishes one request correlation, and each
conditionally executed step produces its own decision before that step. Skipped
steps produce no false event. Beginning the program transaction may precede the
first step decision, but no SQL step effect or commit does; any audit failure
aborts the whole program, so earlier durably authorized step effects remain
uncommitted and are rolled back. Query `FETCH`/close, prepared/program handle
close, transport cancellation, result status, commit outcome, archive, and
credential renewal do not re-authorize and are not relabelled as admission
decisions. Omitted authentication cannot open a session and is a bounded server
diagnostic. Every actual authorization decision has exactly one global
sequence and terminal audit outcome.

## Ownership and data model

`river-server` keeps one audit owner per server instance. `SessionAuthorizer` is
replaced, with all River callers in the same delivery, by an explicit
authorization call carrying required permission plus phase/step identity. The
endpoint brackets each request with a borrowed request ID, absolute deadline,
and cancellation token; the engine supplies the actual authorization phase and
executed program step. The authorizer copies only non-secret scalar fields into
a provider-owned slot before the borrow ends. No receipt or buffer escapes.
The provider owns fixed staging pages, queue slots, checksum state, active
file, completion state, and I/O buffers. No SQL or protocol layer may append or
force independently, and no transitional `authorize(int)` wrapper remains.

The on-disk stream has a versioned instance-bound header followed by fixed-size,
self-checksummed event records. Each record includes its global sequence,
generation, event class, correlations, decision, status, and reserved-zero
fields. A force cohort is an I/O grouping, not a variable durable frame: each
request always reserves exactly one record's bytes before sequence assignment,
so `headerBytes + durableRecordBytes + reservedRecordBytes` can never exceed
`activeAuditMaximumBytes`. Offsets, sequence numbers, generations, and byte
limits are positive `long` values. `Long.MAX_VALUE` is reserved as the terminal
control generation and is never an event sequence, audit generation, or
ordinary control generation. Before reservation, control publication, or
archive mutation, the owner preflights all next values with checked arithmetic.
If any operational next value would reach the reserved value, then, under the
same audit owner that reserves slots, it transitions `ACTIVE -> EXHAUSTING` and
returns `RETRY` without a sequence to every later reservation until the
transition resolves. The coordinator resolves every already sealed or appended
sequence through the captured `lastAssignedSequence` and forces that complete
prefix; a drain failure uses the normal `IO_FAILURE`/`FENCED`
path and does not publish terminal authority. After a successful drain, the
owner computes the active file's exact durable length and digest and writes an
`EXHAUSTED` record at terminal control generation `Long.MAX_VALUE` into the
inactive control slot. That record binds the active generation, durable length,
digest, and `durableSequence`; the owner forces the control file and directory.
Only that successful terminal force returns `RESOURCE_EXHAUSTED` to the
triggering request and makes all later requests return the same status. Recovery
selects the terminal record over every ordinary generation, verifies its exact
active-file binding, and remains exhausted; a missing, torn, longer, or
mismatched active file is `CORRUPTION`. No sequence or
generation is reset or reused. Recovery requires a separately reviewed wider
durable format or a new instance incarnation; ordinary startup and archive
cannot clear it. The format has no benchmark-derived event cap.

Cancellation or deadline expiry detaches the unreserved triggering caller with
`CANCELLED` or `TIMEOUT`, but the provider-owned exhaustion transition continues.
A later caller already cancelled/expired gets that status; otherwise it gets
`RETRY` while `EXHAUSTING`. An already sealed last-legal slot follows the normal
provider-owned cancellation rule and is still part of the forced prefix. If
close wins before exhaustion starts, close semantics apply and no terminal
transition begins. If close begins after `EXHAUSTING`, it prevents admission of
all unresolved allowed slots, joins the drain, persists terminal authority,
then closes the files; affected allowed-decision waiters return `CLOSED`, while
the exhaustion trigger returns its already determined terminal/cancellation
status.

The launcher supplies two explicit byte budgets:

1. `activeAuditMaximumBytes`, covering the header plus durable/reserved records; and
2. `pendingAuditMaximumBytes`, covering provider-owned staging, queue slots,
   waiter identities, and completion state.

Startup rejects a budget that cannot hold the header plus one authentication
and one statement-decision record. Each request charges the fixed record bytes
and exact fixed provider-slot bytes with checked long arithmetic. It atomically
reserves both before receiving a sequence. Insufficient active capacity returns
`RESOURCE_EXHAUSTED`; unavailable pending capacity returns `RETRY`; cancellation
before seal returns `CANCELLED`; an already-expired deadline returns `TIMEOUT`.
All occur before session/statement admission and before a sequence exists.
There is no hidden record count, variable frame overhead, queue growth, or
allocation fallback.

## State machine and group force

One provider-owned state machine is authoritative:

```text
FREE -> RESERVED -> SEALED -> APPENDED -> DURABLE -> RELEASED
                    |                         |
                    +-> FENCED  <-------------+
FREE <- ABORTED <- RESERVED
```

1. The producer prepares non-secret fields in reusable scratch. Under the audit
   owner it reserves exact active/pending bytes, assigns the next sequence,
   encodes into provider-owned storage, and seals in one operation. No caller
   can retain an unsealed sequence or create a queue gap.
2. A single coordinator selects the longest consecutive sealed prefix already
   present, appends its fixed records from provider-owned pages, and issues one
   `CONTENT_AND_METADATA` force covering the exact cohort end. It does not wait
   an arbitrary coalescing interval. A later measured, deadline-bounded wait
   would require separate evidence and policy ratification.
3. Only an unambiguously successful synchronous force advances
   `durableSequence`. Waiters whose sequences are at or below that frontier are
   notified; only then may an allowed request cross the admission barrier.
   One force is equivalent to individual forces because records retain total
   sequence order and every released waiter is covered by the same forced
   prefix.
4. A reservation may abort and release both budgets only before sealing. The
   submit call borrows caller context only until the provider slot is sealed;
   afterward the provider owns all state. Cancellation or deadline after seal
   lets the caller return `CANCELLED` or `TIMEOUT` without admission while the
   coordinator resolves and later releases the slot. A resulting durable record
   is a conservative orphan. Caller carriers may be reused immediately because
   they are neither retained nor revisited.
5. Close rejects unsealed/new work with `CLOSED`, marks every sealed allowed
   decision non-admitting, drains the sealed prefix when I/O remains healthy,
   and returns `CLOSED` to those waiters after their provider state is detached.
   Denied decisions may retain their denial status only if already durable.
   Close never abandons a provider slot, and closes file then directory once.

The coordinator is an audit persistence mechanism, not a second authorization
executor. It never changes allowed/denied or calls the engine.

## Failure, crash, and recovery contract

| Boundary | Required result |
| --- | --- |
| Validate/reserve/encode before seal | Invalid external fields return `INVALID_EXTERNAL_INPUT`; checked-byte overflow or active-capacity failure returns `RESOURCE_EXHAUSTED`; unavailable pending bytes returns `RETRY`; cancellation/deadline returns `CANCELLED`/`TIMEOUT`; an internal encoding invariant returns `INVARIANT_BROKEN`. Release reservations; no sequence/file mutation is visible. |
| Partial write, invalid/zero I/O progress, or adapter I/O/force exception | Admit none of the unresolved cohort; return `IO_FAILURE` to attached waiters, preserve the file, enter the audit `FENCED` state, and return `FENCED` to later requests. Do not retry an unknown append in process. |
| Coordinator invariant failure | Admit none of the unresolved cohort; return `INVARIANT_BROKEN`, trip the instance fatal fence, wake waiters, and preserve all state. |
| Cancellation/deadline after seal | Return `CANCELLED`/`TIMEOUT` with no admission after detaching the caller; provider-owned resolution continues and releases its slot only at durable or fenced terminal state. |
| Close races a sealed event | No allowed work is admitted after close begins. The caller returns `CLOSED` unless an earlier I/O/fatal status takes precedence; provider-owned drain/fence and release continue. |
| Crash before a successful force | No caller passed the admission barrier. Restart scans the stream. A torn/invalid terminal record is `CORRUPTION` and remains untouched. A complete valid recovered record may remain conservatively for work that was never admitted. |
| Crash after force and before notification/admission | The complete record validates on restart. It is retained; the interrupted work was not acknowledged or admitted by the dead process. Duplicate insertion is avoided through global sequence/record validation, not by promising that the work occurred. |
| Header, instance, generation, sequence, length, checksum, reserved field, or tail invalid | Startup returns `CORRUPTION`, publishes no readiness, and performs no truncation, repair, rollover, or implicit reinitialization. |
| Active capacity full at startup/runtime | Startup fails before readiness if minimum admission cannot fit. Runtime returns `RESOURCE_EXHAUSTED` before effects and names the stopped-instance archive operation. |
| Global sequence, audit generation, or ordinary control generation reaches its reserved terminal boundary | Under the reservation owner, stop new work, capture the last assigned sequence, drain and force every earlier slot, then bind that exact durable frontier/length/digest in the forced terminal `EXHAUSTED` control record. On drain or persistence failure return `IO_FAILURE` and fence without terminal authority. Never wrap, reset, or reuse an identity. |

Java/NIO exceptions are translated at the platform adapter. Expected pressure,
cancellation, corruption, and I/O outcomes remain `StatusCode` values.

## Archive interaction and sequence continuity

Audit generations use immutable `audit-<generation>.log` files and a redundant,
independently checksummed two-generation control store following ADR 0003. A
control record contains instance identity, control generation, state
`ACTIVE|ARCHIVING|EXHAUSTED`, old/new audit generation and names,
content-archive name and digest, first/next/durable global sequence, exact
durable byte length, active-file digest, and predecessor control generation and
digest. Recovery selects the highest valid control generation; disagreement, missing
authoritative data, or invalid identity/checksum is `CORRUPTION`.

For terminal recovery, the named active generation's header and lineage, every
record through `durableSequence`, exact byte boundary, and whole-file digest
must match the `EXHAUSTED` record, with no suffix. A crash after the active
prefix force but before a valid terminal record leaves the prior control
authoritative; startup validates the complete prefix and retries exhaustion.
A crash after the terminal-file force but before its directory force may expose
either the prior valid control or the complete terminal record; recovery accepts
only one of those fully validated states. After the directory force, terminal
authority must be present. A partial or mismatched candidate is never selected.

Only `riverd audit archive` under the stopped instance lock may transition it:

1. prove no live owner/pending slot; validate and force the active generation;
2. derive its digest and last sequence; create and force a new generation whose
   first sequence is old last plus one and whose header links the old digest,
   then force the parent directory so the new name is durable;
3. publish/force an `ARCHIVING` control generation naming both files and the
   collision-checked content archive, then force the directory;
4. atomically rename the old generation to the archive name without overwrite
   and force the directory; and
5. publish/force the next `ACTIVE` control generation selecting the new file,
   then force the directory.

Before the `ARCHIVING` control is durable, the old `ACTIVE` control remains
authoritative and a staged new file is non-authoritative but preserved; retry
reuses it only when every header/identity/sequence byte matches, otherwise it
returns `CONFLICT` without overwrite. In
`ARCHIVING`, restart validates all recorded identities: if the old name exists,
it resumes the rename; if the exact archive exists, it resumes the control
switch; an unexpected collision, neither file, or mismatched digest is
`CORRUPTION`/`CONFLICT` and changes nothing. Repeating any completed step is
idempotent. Global sequence never resets across archives. Corrupt audit is
never renamed as valid, truncated, repaired, or implicitly reinitialized.
`tic-b901` implements the command; `tic-72ea` owns the generation/control
primitives and crash-state tests.

## Secret and applicability rules

The record contains stable numeric identity/correlation, permission, decision,
and status only. Token bytes, token proof, TLS exporter value, certificate
private material, environment/argument values, SQL, parameters, and arbitrary
diagnostic strings are forbidden. Producer scratch holding authentication
material is erased by the protocol owner independently of audit completion.

Audit is mandatory for the supported remote `riverd` path and cannot be
disabled by a flag or capacity value. It is non-applicable to an embedded
session with no remote `SessionAuthorizer`: that path creates no audit file,
coordinator, queue, staging arena, thread, or force and adds no per-row work.
The current plain production listener is deleted during the authenticated
lifecycle migration rather than retained as a disabled-audit mode.

## Matched baseline and regression evidence required by `tic-72ea`

The implementation ticket must first add allocation-free audit telemetry to
the existing owner and capture a pushed telemetry-only control commit. The same
telemetry remains in the candidate: decisions, appended bytes, batches, force
calls/nanoseconds, cohort histogram, pending-byte high water, capacity/pressure
rejections, cancellations, fences, and durable frontier. It must not include
secrets or affect control flow.

`tic-72ea` adds the engine-neutral
`io.riverdb.bench.security.SecurityAuditAdmissionMain` and Gradle JavaExec task
`:river-bench:securityAuditAdmission`. The driver owns all workload defaults,
starts the real authenticated loopback path on a fresh instance, executes a
fixed seeded mix of allowed reads, denied writes, prepare/execute, and a
conditional mixed-step program, and writes a checksummed immutable artifact.
No shell script copies its semantics. A fixed-count correctness command is:

```sh
./gradlew :river-bench:securityAuditAdmission --args="--mode=correctness \
  --clients=16 --requests-per-client=10000 --seed=410221 \
  --output-dir=/private/tmp/river-audit-<source>-correctness-16"
```

This mode has no timed cutoff. Its manifest defines every request ID and
per-client order, so the control and candidate must produce the identical
request-correlated decision multiset and admitted effects while each audit
stream independently has gap-free global sequences. A typical timed sample
command is:

```sh
./gradlew :river-bench:securityAuditAdmission --args="--mode=performance \
  --clients=16 \
  --warmup-seconds=5 --measured-seconds=30 --seed=410221 \
  --output-dir=/private/tmp/river-audit-<source>-16-<sample>"
```

Build a telemetry-only control SHA and candidate SHA in separate worktrees and
Gradle homes/project caches. First run fixed-count correctness once per source
at client counts 1, 2, 4, and 16. Then, with no other build/workload active,
run timed samples at those client counts using the fixed interleave order
`C,A,A,C,C,A,A,C,C,A`, giving five 30-second measured samples per source/count.
Every artifact records source/configuration fingerprint, JDK/host/filesystem,
decision/status counts, admitted effects, p50/p99/p99.9 latency, throughput,
force count/time and cohort histogram, queue occupancy/pressure, CPU/profile
and monitor contention, allocation bytes/objects, River-owned copy bytes/count,
and GC count/pause/allocation rate.

Correctness is absolute in the fixed-count phase: identical request-correlated
decision multiset, per-client request order, and admitted effects for the seed;
each independently scheduled audit stream must have gap-free global sequences.
Timed samples may complete different request counts, but each must independently
reconcile every issued request with its decision and admitted effect. Both
phases require zero unexplained outcomes, valid audit/restart, and zero resource
residue.
Warmed audit allocation is 0 bytes/objects per event and the
audit record is encoded directly once into reserved provider storage with zero
River-owned byte-array copies. The deterministic overlap test requires one
force to release the whole forced cohort. At 4 and 16 clients, the upper 95%
bootstrap confidence bound for forces/decision must be at most 0.75. At every
client count, the lower 95% bound for candidate/control throughput must be at
least 0.95 and the upper 95% bound for p99.9 latency ratio at most 1.10. Compute
the upper 95% bounds for candidate/control CPU nanoseconds per audited decision
and monitor-blocked nanoseconds per decision; each must be at most 1.10. The
same 1.10 upper-bound gate applies to GC pause nanoseconds per decision and GC
collections per million decisions. If a control metric is zero, the candidate
must also be zero. Profile shape and cache-miss counters are diagnostic and
cannot waive a failed numeric gate. Compute
10,000 fixed-seed bootstrap resamples over complete samples; preserve failures
and both interval endpoints. If an interval fails, the ticket does not promote.

CI runs the deterministic/fault/ownership/allocation/copy tests below plus
`SecureRemoteJdbcGateTest`; local promotion records the repeated artifacts and
an async-profiler/JFR comparison for CPU, allocation, locks, and GC. The
implementation must add the named runner/task and artifact schema before the
plan is considered executable evidence.

Deterministic tests use the existing durable-I/O fault provider or a second
provider at the real platform boundary and cover: one force releasing a cohort;
no release before force; denied decisions; byte reservation races; impossible
request; pending/full pressure; cancellation in every state; close with waiters;
partial write; short/zero progress; force failure; coordinator failure; crash
before/after force and notification; restart validation; corrupt/torn tails;
archive collision/interruption; full-at-start; long-offset arithmetic; secret
scans; near-`Long.MAX_VALUE` sequence and generation preflight with persistent
exhaustion across restart; a concurrent final legal slot racing terminal
detection; cancellation and close during `EXHAUSTING`; and crashes before/after
the active-prefix force, during terminal-record write, before/after terminal-file
force, and before/after directory force. The suite also proves the non-applicable
embedded path's zero file/queue/thread/force cost.
Warmed admission must allocate zero per event after provider construction.

## Proposed conclusion

This state machine preserves audit-before-admission while allowing one ordered
force to cover a concurrent cohort. It accounts every declared event and every
retained byte, fails closed without an unaudited fallback, and gives archive
and cancellation exact ownership. It is not accepted until independent
concurrency, security, recovery, and performance reviewers record acceptance
and `tic-11a5` ratifies the public contract.
