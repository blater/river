# ADR 0012: Embedded API and protocol boundaries

Status: Accepted

## Context

Embedded use, native remote access, JDBC, administration, and inspection need
shared semantics without exposing page, transaction-manager, WAL, or server
implementation types. Protocol convenience must not bypass validation,
security, flow control, or the engine's status model.

## Decision

`river-engine-api` owns stable public values and lifecycle/session/transaction/
statement/result interfaces. It depends only on `river-base`; `river-engine`
adapts those contracts to the kernel. Embedded calls validate external values
at entry, map returned status at exit, and do not expose mutable engine buffers.

River v1 uses a versioned native River protocol rather than claiming an
ecosystem wire protocol. `river-protocol` owns pure bounded frame/message/value
codecs and legal state transitions over engine-API values; it has no server,
socket, SQL parser, or kernel dependency. `river-client` owns reusable request,
session, cursor, deadline, and result-flow mechanics. `river-server` owns TLS,
authentication, authorization/admission hooks, connection state, cancellation,
and the engine adapter. JDBC is a client adapter and creates `SQLException`
only at its public boundary.

The first secure service is deliberately loopback-only. One configured
high-entropy token identifies one service principal with an immutable primitive
permission mask; this is the smallest immediate authorization consumer, not a
claim that SQL-managed roles/grants exist. The server durably forces bounded
authentication and statement-admission records before admitting work and fails
closed when that audit is full or corrupt. SQL-managed roles, grants, rotation,
and non-loopback policy wait for the first multi-principal operational consumer.

The v1 remote session is ordered with bounded in-flight work and no multiplexed
active statements. Frame length, counts, encodings, lifecycle, and negotiated
capabilities are validated before allocation. Authentication and version
negotiation occur inside server-authenticated TLS and are bound to session state
to prevent downgrade. Result delivery uses explicit bounded credits and
batch/view ownership; slow or disconnected clients release server resources.

V1 fixtures prove same-version encoding/decoding and explicit rejection of
unknown required capabilities, incompatible versions, invalid state, and
malformed frames. A later compatibility window or ecosystem protocol requires
its own ADR; decoder tolerance is not accidental compatibility.

The offline inspector is a separate read-only tool using `river-platform`,
`river-format`, and explicitly permitted read-only WAL decoders. Admin/CLI code
calls engine services and contains no checkpoint, backup, recovery, or storage
correctness algorithm.

## Invariants

- Client/protocol modules cannot depend on kernel implementation packages.
- External errors map to stable safe codes/SQLSTATE; diagnostics, SQL literals,
  credentials, file paths, and secrets do not leak across the boundary.
- Every frame has a configured maximum before allocation and every retained
  result has a credit, deadline, cancellation, and release path.
- Disconnect closes transactions, statements, cursors, and retained buffers
  according to the explicit session state machine.
- Remote access is not opened before TLS, authentication, authorization,
  quotas/admission, and security review pass.
- Inspector/admin tools cannot mutate live durable state through decoder APIs.

## Consequences

Embedded and remote execution share semantics while adapters remain
replaceable. V1 deliberately favors a small secure protocol over broad wire or
JDBC surface claims; conformance matrices can expand without coupling clients
to the kernel.

## Alternatives

- Exposing engine implementation objects to embedded callers was rejected for
  ownership and compatibility risk.
- Reusing PostgreSQL or legacy Ingres wire syntax was deferred because semantic
  compatibility is larger than framing compatibility.
- Unbounded multiplexing was rejected until scheduling, cancellation, and
  ownership are measured and proved.

## Required evidence

- Q01/engine API semantic fixtures and JDBC supported-method matrix.
- Protocol state-machine, malformed-input, downgrade, authentication,
  authorization, slow-client, cancellation, and disconnect tests.
- Same-version/reject fixtures and explicit compatibility review before a
  published protocol promise.
- Allocation/copy measurements for encode/decode/result paths through
  `river-bench`.

## Authoritative context

- [High-level API/protocol plan](../plans/river-high-level-plan.md)
- [Implementation plan Q01 and N01-N06](../plans/river-project-implementation-plan.md)
- [Engineering trust-boundary charter](../plans/river-engineering-personas-and-performance-charter.md)
