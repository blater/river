---
id: tic-b1b7
status: closed
type: task
priority: 1
assignee: blater
parent: tic-5db4
delivery: code
base-commit: 590c4bfdfd486303e158377abba23c72f78c5e6c
branch: ticket/tic-b1b7-general-concurrency
delivered-commit: abdc506506efa487e459ed6adde6d5896754e536
evidence:
    - /Users/blater/src/river/benchmark-results/tic-b1b7-20260914/README.md
tags:
    - p0
    - concurrency
    - correctness
    - testing
created: 2026-09-14T08:48:39.540199Z
---
# Prove general SQL concurrency with deterministic P0 reproducers

### Outcome

Provide deterministic, correlated proofs of general SQL lock-path, deadlock,
Repeatable Read and Serializable behavior. Exercise ordinary SQL on small
schemas through the real SQL, relational, transaction and lock owners. Retain
Payment/New Order as one integration regression case for the historical P0 gap;
benchmark names and scores must not define kernel behavior.

The user explicitly authorized this bounded refinement and ticket creation after
the missing reproducer was explained. This ticket supplies the fixture and its
proofs; tic-1dda retains the independent P0 revalidation campaign and all existing
correctness/scaling criteria. Passing this ticket alone does not pass P0.

### Scope and owners

The lead integrator owns a test slice with a transactions/concurrency review
lens and relational-semantics review. Keep general SQL cases with existing
engine/transaction integration tests; keep the Payment/New Order regression at
the existing benchmark integration boundary. Reuse existing transaction bodies,
correlation diagnostics and test synchronization facilities where applicable.
No transaction or lock-layer code may know TPC-C families or schema names.

Before coding, declare a finite scenario matrix mapping each case to its SQL,
physical access path, isolation contract, controlled schedule and expected
outcome. Cover the obligations below with representative cases, not an exhaustive
Cartesian product, randomized stress campaign or enumeration of every schedule.
Derive expected semantics from River's existing isolation and scheduler owners;
this ticket does not change those contracts to make tests pass.

### Required scenarios

- Point reads, range scans, updates and inserts through primary and secondary
  indexes. Establish that each declared access path actually executes and capture
  the resulting row/key/range dependencies using existing diagnostic owners.
- Controlled two-transaction and three-transaction deadlock cycles, including
  applicable lock-conversion and queue-order dependencies. Include adjacent
  acyclic schedules that must complete without false victim selection.
- Repeatable Read/Repeatable Read, Repeatable Read/Serializable and
  Serializable/Serializable interactions. State which transaction has each level
  and verify effective levels. Assert each level's existing guarantees, including
  repeatable reads and Serializable phantom protection, without imposing stronger
  isolation on Repeatable Read. An outcome permitted at a level need not occur
  unless the declared schedule and current contract require it.
- Real Payment/New Order transactions around warehouse 1 under the original
  mixed and common-Serializable contracts, with two-client and three-client
  coverage. Control relevant lock interleavings; phase-start synchronization or
  the opposing-district-lock probe alone cannot satisfy this regression.

### Determinism, causality and cleanup

Use test-owned barriers/latches at the relevant boundaries to establish the
schedule. Do not use sleeps, TPS changes or elapsed-time guesses to infer that
a lock was acquired or a request queued. Ordinary bounded test waits detect a
failed test and must release barriers and join owned workers on every exit;
they introduce no database deadline policy.

Correlate transaction attempts, held resources, pending requests and outcomes.
For each selected victim, retain the cycle and the scheduler-enforced blocking
reason for every edge, including modes, queue relationship and grant predicate.
Check the expected victim under the existing selection policy with controlled
inputs; do not invent a new victim or fairness policy. Use scheduler-produced
causality rather than copying its grant predicates into a test-side lock model.

Prove victim cancellation and rollback release every holding and queued request,
allow survivors to finish and allow immediate victim-session reuse. Reconcile
victim, cancellation, rollback and retry counts as distinct quantities. Final
transactions, locks, waiters and retained snapshots must be zero, diagnostic
buffers must not overflow, and committed/rolled-back rows must match the expected
business-independent SQL outcomes. Preserve the integration case's invariants.

### Maximum change shape and DRY boundary

Focused tests and the minimum shared test support needed by these named cases,
plus evidence and this ticket's documentation. Reuse one existing concurrency
policy, one outcome classifier and the existing correlation/diagnostic owners.
Existing lower-level tests remain useful companions but cannot replace real SQL.
Do not copy the TPC-C transaction implementation or build a second executor,
retry loop, grant-policy model, telemetry framework or general scheduling DSL.

Start with existing observation/control seams. If deterministic proof requires a
production hook or new runtime instrumentation, identify the exact missing seam
and stop for a separately scoped decision; this ticket does not silently authorize
production behavior changes. Test support must remain local and proportionate.

### Non-goals and stop conditions

No lock removal/redesign, new lock mode, fairness or victim-policy change,
isolation weakening, retry tuning, WAL overlap, workload optimization, throughput
target, new benchmark family or external harness/comparator change. Do not run
or redefine tic-1dda's promotion matrix inside this ticket.

If a test exposes a database defect, retain the deterministic failing case and
its causal evidence, report the specific blocker and obtain a separately scoped
fix. Do not hide it with retries, larger waits, weaker assertions or broader
implementation work. Missing observation is an explicit gap, not a passing test.

### Acceptance and delivery

The declared matrix covers every obligation above, uses the real paths and
repeats with the same controlled ordering and expected outcomes. Focused tests
and affected-module checks pass with complete cleanup. Retain exact commands,
source revision, individual case outcomes and compact cycle/correlation evidence;
no new provenance framework or TPS campaign is required.

Independent concurrency and relational review verifies schedule validity,
isolation assertions, scheduler-derived cycle evidence, victim cleanup, acyclic
controls and scope. One coherent test delivery and evidence record complete this
ticket. Link its accepted fixture and reproducible command into tic-1dda before
that evidence-only campaign resumes; preserve the historical failed scaling
result and every remaining P0 gate.

The original missing-reproducer requirement is in
[perf_review.md](../perf_review.md#deterministic-reproducer). The general cases
above are the user-approved refinement; the Payment/New Order case remains an
integration obligation, not a special concurrency policy.

## Delivery evidence, 2026-09-14

Implementation: `5373ea1ded2cb48c7d68a2ef9167383b55760177`, based on stable
`590c4bfdfd486303e158377abba23c72f78c5e6c`.
[Declared matrix, proof limits and commands](../plans/p0-general-concurrency-reproducers.md).

All 46 controlled database cases passed: 38 general SQL cases, including 12
selected cycles, and eight real Payment/New Order integration cases. The final
focused run passed all 15 JUnit invocations with no skips. Final affected suites:
engine 1,030 passed; benchmark 101 passed with two existing environment-gated
promotion tests skipped; server-app 74 passed. `verifySourcePolicy` and
`verifyModuleGraph` passed. Full affected validation completed in 3m 6s;
no P0 campaign, external harness run or production change was made.

Independent concurrency and relational review by `execution_admission_review`
approved the final schedules, exact cycle and resource assertions, isolation
evidence, bounded worker waits, victim cleanup/session reuse, acyclic controls,
real workload regression and stated limits. No database defect was exposed.

Full commands, individual results, tagged SQL, scheduler edges, resource digests,
block classification and cleanup output are retained locally at
`/Users/blater/src/river/benchmark-results/tic-b1b7-20260914/README.md`.
The evidence remains in the workspace's ignored artifact directory.
`tic-1dda` links this fixture and command; its historical failed scaling result
and remaining P0 correctness/scaling requirements are unchanged.
