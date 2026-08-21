# F1: contracts and deterministic test mechanics

Status: active

Deliverables: P07, P08

## Outcome

River has dependency-light identity, status, diagnostics, ownership, clock,
scheduling, and fault-injection contracts. Tests can reproduce the same ordered
trace from the same seed and can force every modeled I/O outcome without using
exceptions as engine control flow.

## Authority and invariants

- A `StatusCode` is the caller-visible outcome; a diagnostic is not control
  flow and is emitted once only where operational context exists.
- Expected retry, cancellation, validation, capacity, I/O, and corruption
  outcomes do not allocate or throw on designated hot paths.
- A fatal status and a `FATAL` event do not themselves own shutdown. One
  component/database fencing state machine rejects new work after the
  transition.
- Disabled observability is a singleton no-op path with no per-call allocation.
- Async work and events enter bounded queues. Full queues return a stable
  backpressure/resource status; they do not grow or create unbounded futures.
- The deterministic scheduler orders work by declared scheduling class and a
  reproducible sequence. No test relies on wall-clock timing.
- Persisted bytes are boundary input after reopen. The fault model validates
  and may corrupt, truncate, tear, delay, or reject them at named boundaries.

## Ownership and copies

Callers own and reuse bounded status-detail storage. Event fields are primitive
or stable IDs, not maps or formatted messages. The file model copies bytes only
where stable persistence or an injected torn-write boundary requires an
independent lifetime; tests account for those copies.

## Failure matrix

- cancellation before enqueue and before execution;
- bounded scheduler rejection and reserved critical progress;
- short read and short/partial write;
- write, force, and capacity failure;
- delayed start and delayed completion at every atomic-install half-step;
- torn stable image and explicit corruption;
- crash discarding volatile bytes followed by deterministic reopen;
- crash before and after temporary create/write/force, destination replacement,
  parent-directory force, and reopen verification;
- fault at a registered but disabled point;
- same seed and action script producing the same trace.

## Merge evidence

- Identity units cannot be interchanged accidentally.
- Bounded status detail resets and truncates without per-use allocation.
- Close/ownership misuse is detected in test/debug mode.
- Fatal fencing transitions are monotonic and idempotent.
- No-op metric and diagnostic paths reuse singleton instances.
- Scheduler and file-fault tests cover bounds, trace reproducibility, crash,
  reopen, and every supported injected action.
- Atomic-install tests distinguish forced file content, visible replacement,
  forced directory state, and verified reopen without claiming real device
  durability.
- General-directory tests distinguish file content/length force from namespace
  force for validated child creation, bounded listing, non-replacing rename,
  removal, and named truncation. Every modeled boundary is exercised with
  process crash and in-call restart before and after application.
- Installer progress is opaque to callers; provider-authenticated snapshots and
  monotonic transitions prevent cross-provider or caller-forged durability.
  Provider-issued per-install identities also prevent same-provider request
  carriers with matching versions and lengths from borrowing one another's
  progress.
- Allocation evidence records the warmed no-op/status common paths.

## Out of scope

- Production NIO durability semantics (K01).
- Durable format codecs (K02).
- Journal frontier or transaction visibility semantics (P10/K03).
- A claim that P08 covers later WAL/page/checkpoint crash points before those
  points are registered by their owning slices.
