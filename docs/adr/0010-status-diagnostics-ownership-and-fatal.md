# ADR 0010: Status, diagnostics, ownership, and fatal state

Status: Accepted

## Context

Expected exceptions, defensive checks at every internal call, eagerly formatted
logs, and implicit buffer lifetimes create allocation and correctness costs on
River's hottest paths. At the same time, a fatal durability uncertainty must
have one authoritative state transition rather than disconnected components
continuing independently.

## Decision

Expected outcomes return stable `StatusCode` values and optional bounded
caller-owned `StatusDetail`. Diagnostics use stable event IDs, fixed typed
fields, and `DEBUG | INFO | WARN | ERROR | FATAL` through a bounded/no-op-capable
sink. Security audit records use a separate durable authenticated path.

Validate public API, protocol, configuration, persisted bytes, replica input,
and ownership transfers once. Construct a typed bounded value/view and trust it
inside its declared lifetime; internal services do not repeat null/range checks
for callers River controls.

Every retained or asynchronous buffer operation declares `borrow`, `transfer`,
bounded `retain/release`, or `copy`. Published WAL/data views are immutable.
Reuse requires the owning completion/reclamation proof. One explicit bounded
copy is preferred when it creates a necessary lifetime boundary.

There is one database/engine `FatalStateFence`. Components may propose the
first immutable cause; an atomic one-way transition records it, rejects new
work, wakes waiters with stable status, preserves inspectable state where safe,
and starts controlled quiesce/shutdown. Subsequent causes are bounded secondary
diagnostics and cannot replace the first. A `FATAL` diagnostic alone does not
fence, and individual subsystems do not invent competing fatal states.

## Invariants

- Status is control flow; diagnostic emission is operator context. Neither is
  used as the other.
- Disabled diagnostics and expected kernel failures allocate no exception,
  message string, varargs array, map, future chain, or captured lambda.
- Exceptions are adapted only at throwing Java/library boundaries, cold control
  paths, and required public APIs such as JDBC `SQLException`.
- JVM `Error` is not broadly caught.
- Diagnostic overflow follows a bounded drop/coalesce policy and never blocks
  journal, recovery, checkpoint, or future consensus progress.
- Fatal fencing is idempotent and monotonic; no successful operation begins
  after observing the fence.

## Consequences

Callers must handle explicit statuses and ownership, but hot paths remain
measurable and failure propagation becomes testable. Public adapters can still
offer idiomatic Java/JDBC errors without leaking that model into the kernel.

## Alternatives

- Exceptions for retries, conflicts, validation, disk-full, or cancellation
  were rejected for allocation and hidden control flow.
- Defensive checks at every trusted internal method were rejected because they
  obscure the actual trust boundary and tax hot paths.
- Per-module fatal flags were rejected because they permit split-brain process
  state and contradictory recovery decisions.

## Required evidence

- Tests inject every status and prove one boundary mapping/meaningful emission.
- Ownership tests cover use-after-release, double release, mutation after
  publish, overwrite, and leaked retention.
- Allocation/bytecode checks cover designated status and disabled-diagnostic
  paths; fault tests cover concurrent fatal causes and waiter wakeup.

## Authoritative context

- [Engineering charter](../plans/river-engineering-personas-and-performance-charter.md)
- [Performance plan](../plans/river-performance-review-and-benchmark-plan.md)
- [High-level base/observability plan](../plans/river-high-level-plan.md)
