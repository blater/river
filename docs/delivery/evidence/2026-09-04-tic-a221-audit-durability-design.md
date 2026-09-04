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
| `AUTHENTICATION_DECISION` | For every syntactically complete authentication proof evaluated after the TLS-bound challenge | format/version, sequence, instance incarnation, credential generation, principal ID, connection/request correlation, allowed/denied, stable status class | An allowed session may reach READY only after the receipt is durable. A denied proof returns its authentication status only after the denial is durable. TLS failures, disconnects, and timeouts before a proof decision are transport diagnostics, not fabricated principal decisions. |
| `STATEMENT_ADMISSION_DECISION` | Once for every external statement, prepared execution, or transaction-program request whose required permission is evaluated | format/version, sequence, instance/principal/session/request correlation, one permission bit, allowed/denied, stable status class | The engine call or program dispatch proceeds only after an allowed receipt is durable. A denial has no statement side effect and is returned only after its record is durable. |

Connection lifecycle, result status, commit outcome, audit archive, and
credential renewal are not relabelled as admission decisions. They retain their
own operational/transaction owners. Omitted authentication cannot open a
session and is counted by bounded server diagnostics; it does not invent an
identity-bearing audit record. Every event admitted by the two declared
classes has exactly one sequence and terminal receipt.

## Ownership and data model

`river-server` keeps one audit owner per server instance. Producers use
caller-owned reusable request and receipt carriers. The provider owns fixed
staging pages, the queue slots, checksum state, active file, and I/O buffers.
No SQL or protocol layer may append or force independently.

The on-disk stream has a versioned instance-bound header and self-delimiting
batch frames. Each frame names its first sequence, record count, encoded byte
length, and checksum; each record is fixed-size for that format version and
contains reserved-zero fields. Offsets, sequence numbers, and configured byte
limits are `long`. The format has no benchmark-derived row/event cap. File
addressability and a configured active-audit byte budget are its finite
boundaries.

The launcher supplies two explicit byte budgets:

1. `activeAuditMaximumBytes`, covering header plus durable batch frames; and
2. `pendingAuditMaximumBytes`, covering provider-owned staging, queue slots,
   receipts, and completion state.

Startup rejects a budget that cannot hold the header plus one authentication
and one statement-decision frame. Each request computes its exact encoded file
bytes and retained pending bytes with checked long arithmetic. It atomically
reserves both before receiving a sequence. Exhausted active capacity returns
`RESOURCE_EXHAUSTED` with archive recovery guidance; exhausted pending capacity
returns bounded `RETRY` or `RESOURCE_EXHAUSTED` according to the request
deadline. Both occur before session/statement admission. There is no hidden
record count, queue growth, or allocation fallback.

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
   present, appends it as one or more budget-derived frames, and issues one
   `CONTENT_AND_METADATA` force covering the exact cohort end. It does not wait
   an arbitrary coalescing interval. A later measured, deadline-bounded wait
   would require separate evidence and policy ratification.
3. Only an unambiguously successful synchronous force advances
   `durableSequence`. Waiters whose sequences are at or below that frontier are
   notified; only then may an allowed request cross the admission barrier.
   One force is equivalent to individual forces because records retain total
   sequence order and every released receipt is covered by the same forced
   prefix.
4. A reservation may abort and release both budgets only before sealing. Once
   sealed, cancellation marks the waiter but the coordinator still resolves
   the event. If it becomes durable, the caller returns `CANCELLED` without
   admitting work and the conservative orphan record remains valid. No sealed
   event is silently removed.
5. Close stops new reservations, resolves or fences the sealed prefix, wakes
   every waiter, forces no diagnostic-only data, and closes file then directory
   exactly once. No thread waits without its request deadline/cancellation or
   the instance fatal fence.

The coordinator is an audit persistence mechanism, not a second authorization
executor. It never changes allowed/denied or calls the engine.

## Failure, crash, and recovery contract

| Boundary | Required result |
| --- | --- |
| Reserve/encode fails before seal | Release both byte reservations; return the exact status; no sequence or file mutation is visible. |
| Partial write, write failure, force failure, coordinator failure, or invalid I/O progress | Admit none of the unresolved cohort, enter `FENCED`, wake all waiters with one stable non-OK status, preserve the active file, and reject new reservations. Do not retry an unknown append in process. |
| Crash before a successful force | No caller passed the admission barrier. Restart scans the stream. A torn/invalid terminal frame is `CORRUPTION` and remains untouched. A complete valid recovered frame may remain as a conservative record for work that was never admitted. |
| Crash after force and before notification/admission | The complete frame validates on restart. The record is retained; the interrupted work was not acknowledged or admitted by the dead process. Duplicate audit insertion is avoided through sequence/frame validation, not by promising that the work occurred. |
| Header, instance, generation, sequence, length, checksum, reserved field, or tail invalid | Startup returns `CORRUPTION`, publishes no readiness, and performs no truncation, repair, rollover, or implicit reinitialization. |
| Active capacity full at startup/runtime | Startup fails before readiness if minimum admission cannot fit. Runtime returns `RESOURCE_EXHAUSTED` before effects and names the stopped-instance archive operation. |

Java/NIO exceptions are translated at the platform adapter. Expected pressure,
cancellation, corruption, and I/O outcomes remain `StatusCode` values.

## Archive interaction

Only `riverd audit archive` under the stopped instance's exclusive lock may
replace the active stream. It first proves no live owner or pending reservation,
validates the complete stream, forces it, atomically renames it to a
content-digest name without overwrite, forces the directory, creates and forces
a fresh instance-bound header, and forces the directory again. Collision or
any I/O failure preserves the diagnosable artifacts and returns non-OK.
Corruption is never archived as valid or silently reset. `tic-b901` implements
this operation; `tic-72ea` exposes only the audit-owner primitives it needs.

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

Using identical JDK, filesystem, instance image, TLS material, fixed request
script, client counts, request count, and source/configuration fingerprints:

- run the existing `SecureRemoteJdbcGateTest` and a focused concurrent
  authenticated admission driver at 1, 2, 4, and 16 clients against the
  telemetry-only control and candidate in interleaved order;
- require identical ordered decision payloads, statuses, admitted statement
  count, zero unexplained outcomes, and complete resource cleanup;
- report decisions/second, p50/p95/p99 admission latency, force count/time,
  decisions per force, cohort distribution, bytes, and pending high water;
- preserve all samples and make no speedup claim from a single pair; and
- require the candidate to eliminate force-per-decision under demonstrated
  overlap without regressing the one-client result beyond stated adjacent-run
  variation. If no overlap occurs, report that and do not tune a delay.

Deterministic tests use the existing durable-I/O fault provider or a second
provider at the real platform boundary and cover: one force releasing a cohort;
no release before force; denied decisions; byte reservation races; impossible
request; pending/full pressure; cancellation in every state; close with waiters;
partial write; short/zero progress; force failure; coordinator failure; crash
before/after force and notification; restart validation; corrupt/torn tails;
archive collision/interruption; full-at-start; long-offset arithmetic; secret
scans; and the non-applicable embedded path's zero file/queue/thread/force cost.
Warmed admission must allocate zero per event after provider construction.

## Proposed conclusion

This state machine preserves audit-before-admission while allowing one ordered
force to cover a concurrent cohort. It accounts every declared event and every
retained byte, fails closed without an unaudited fallback, and gives archive
and cancellation exact ownership. It is not accepted until independent
concurrency, security, recovery, and performance reviewers record acceptance
and `tic-11a5` ratifies the public contract.
