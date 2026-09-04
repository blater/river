# River Development Manifesto

River is a greenfield database, and its development process must make ambitious
change safe without making change slow. We work in small, coherent increments;
measure the real system; preserve stable source checkpoints; and correct
regressions close to the change that caused them.

This manifesto defines our shared engineering intent. [`AGENTS.md`](AGENTS.md)
is the operational contract, including exact build, benchmark, worktree, and
completion rules. The
[`engineering charter`](docs/plans/river-engineering-personas-and-performance-charter.md)
contains the detailed rationale.

## 1. Stable means recoverable source

- `master` is a sequence of accepted, reproducible source checkpoints, not a
  storage place for unfinished experiments.
- A known-good state is not safe until its code, tests, evidence, merge commit,
  and tag are pushed. Cached classes or an uncommitted worktree are not a
  checkpoint.
- We deliver one coherent vertical capability at a time. Each delivery has a
  named purpose, an owning subsystem, focused proof, and an obvious rollback
  boundary.
- Performance-sensitive stable points receive an annotated checkpoint tag and
  recorded evidence. This gives us useful history for reproduction, comparison,
  and bisection.
- [`docs/performance-checkpoints.md`](docs/performance-checkpoints.md) is the
  concise human-readable history of baselines, samples, decisions, and stable
  tags; bulky raw artifacts remain outside Git under recorded immutable paths.
- We do not accumulate a large local batch in the hope of sorting it out later.
  Feature branches are pushed regularly, and accepted slices are integrated
  while their behavior is still understood.

## 2. The backlog turns plans into deliverables

- River's execution backlog is stored as source-controlled Markdown tickets in
  [`docs/tickets/`](docs/tickets/), selected by the visible repository-root
  [`ticket.yaml`](ticket.yaml). It is part of the source history, not private
  agent state or a user-global configuration.
- Plans and ADRs remain the authorities for architecture and semantics. Tickets
  link to those sources and identify the part being delivered; they do not copy
  requirements into a second design that can drift.
- An epic represents a capability or promotion gate. A story represents one
  coherent merge and rollback boundary. An investigation answers one explicit
  evidence question and produces a decision. Implementation checklists remain
  inside a story unless an item has an independent outcome, owner, dependency,
  or review boundary.
- We plan in rolling waves. The full epic map and dependencies stay visible,
  but only the immediate dependency frontier is decomposed into implementation-
  ready stories. Later work remains coarse until earlier evidence determines
  the right mechanism.
- A dependency means work genuinely cannot complete before another ticket.
  Non-blocking relationships use links. A blocked ticket names the unmet
  condition and its owner; priority labels do not override dependency truth.
- Ticket flow is `open` to `in_progress` to `closed`. Readiness and blocking
  are derived from unresolved dependencies rather than duplicated as manually
  maintained states. Review is a required delivery gate recorded in ticket
  evidence, notes, and commits, not a second mutable workflow status.
- Work starts only from a ready ticket and is claimed atomically across Git
  worktrees. The claim records the owner, stable base revision, feature branch,
  and worktree. Claims are released or recovered explicitly and are never
  silently expired or stolen.
- Story, bug, and feature branches identify their ticket using the configured
  `ticket/<ticket-id>-description` form. Every story-owned non-merge commit and
  the canonical delivered or merge commit carry an exact `Ticket: <ticket-id>`
  Git trailer. Branch names aid live coordination; the recorded immutable
  commit is the durable code link.
- A ticket closes only when its accepted implementation and relevant evidence
  are merged and pushed. Code and documentation deliveries record and verify
  their delivered commit; evidence-only investigations record a commit or
  evidence reference; only epics may have no direct delivery. Performance-
  sensitive work also records its checkpoint tag. Completed tickets remain in
  source history so decisions, dependencies, and rollback evidence stay
  reproducible.
- Ticket status is the execution view. The checked-in
  [`delivery Kanban`](docs/backlog-kanban.md) records the priority decision among
  simultaneously ready tickets, but ticket files remain authoritative for
  status and dependencies. The Kanban is refreshed with the graph rather than
  becoming a second independent backlog.

## 3. Isolation enables concurrency

- Each feature is developed in its own Git worktree. Parallel agents have
  disjoint file ownership, one lead integrator, and one agreed contract.
- Each building worktree owns its Gradle user home and project cache. Builds and
  benchmarks that share host resources are serialized even when source
  worktrees are separate.
- Only the integrator promotes a feature to `master`. Promotion uses a short
  integration lease so the tested commit is the commit that is merged.
- If `master` changes after a feature was measured, the feature is integrated
  with the new head and the relevant evidence is rerun. Evidence does not
  transfer automatically across a changed source base.
- Concurrent work is never discarded or rewritten merely because it is
  inconvenient. Overlap is resolved explicitly by the owning contributors.

## 4. Evidence comes before explanation

- Before changing a performance-sensitive path, capture the exact source
  revision, workload, configuration, host conditions, correctness result,
  throughput, latency, retries, failures, and relevant subsystem counters.
- Compare like with like. Workload, data profile, isolation, seed, workers,
  warehouses, retry policy, warmup, duration, lifecycle, and evidence mode must
  match unless the changed variable is the subject of the experiment.
- Repeat measurements. A single short run is a diagnostic sample, not a
  performance claim. We report observed figures and variability, never a
  promised or extrapolated number as achieved performance.
- We do not invent convenient pass thresholds. Gates come from correctness
  contracts, an explicitly justified capacity target, or statistically useful
  comparison evidence.
- Throughput is not sufficient evidence. Every run must preserve phase and
  outcome distinctions and account for retries, cancellations, resource
  pressure, lock waits, deadlocks, WAL behavior, and protocol work relevant to
  the change.
- Removing one bottleneck may expose the next. A gate passes only when its
  target mechanism and failure modes are explained; an improved TPS number
  alone does not prove that the mechanism is healthy.
- `river-harness` owns engine-independent stress execution through supported
  database process contracts; River benchmarking waits for `riverd` rather than
  teaching the harness River build or classpath internals.
- Cross-database comparison is a separate sidecar responsibility over versioned
  immutable artifacts. It lives outside River, does not import River or harness
  implementation packages, and never turns River-specific TPS evidence into a
  cross-engine denominator.

## 5. Fast loops, deliberate checkpoints

- During implementation, use the narrowest daemon-backed compile and focused
  test that can disprove the current change quickly.
- At a feature checkpoint, run the affected module tests, policy checks, real
  end-to-end path, repeated benchmark samples, and architecture review required
  by the risk.
- Clean full builds are checkpoint tools. We use them for important accepted
  feature points with exclusive access to the checkout and host workload, not
  as routine iteration or alongside another agent's build.
- Benchmark output is evidence only when the run completed honestly: phases are
  distinguished, invariants pass, outcomes reconcile, and artifacts identify
  the source and configuration that produced them.

## 6. Correctness and performance are separate gates

- Correctness comes first: no false dependency cycles, incomplete cleanup,
  unexplained outcome or retry classification, committed-but-undeliverable
  result, or timeout/liveness failure is accepted.
- Performance then asks whether retry rate, latency, throughput, resource use,
  and scaling behavior improved or remained within understood variability.
- A correctness or observability change may be worth merging despite an
  inconclusive speed result, but it must be labelled honestly. An unexplained,
  repeated regression is not accepted.
- Durable format, recovery, concurrency, consensus, and security changes receive
  an independent adversarial review lens. The author is not the sole approver
  of the assumptions that make those paths safe.

## 7. One design, one owner

- Architectural improvements replace the superseded unreleased design. We
  change River-owned callers and tests together and remove obsolete APIs,
  adapters, flags, scratch state, and duplicate paths in the same delivery.
- We preserve compatibility only for a named external contract with an owner
  and removal condition. Implementation convenience is not such a contract.
- Technical responsibilities have one semantic owner. Lock grant policy,
  retries, commit stages, value ownership, result encoding, and evidence
  validity are not reimplemented in adjacent layers.
- DRY applies most strongly to policy and responsibility. Small local
  duplication is preferable to a speculative abstraction that couples
  unrelated modules.
- `slopmark` is a stop-and-review signal. If a touched hot file gains another
  responsibility, equivalent policy appears in another layer, or its score
  materially worsens, we pause behavior work and restore cohesion.

## 8. Build for scale from the first slice

- We do not add arbitrary low limits for rows, bytes, transactions,
  concurrency, cardinality, diagnostics, or retained state to make a prototype
  pass.
- Finite boundaries must arise from a named structural fact or admitted
  resource budget, with an explicit status and recovery, continuation,
  streaming, spill, paging, or backpressure behavior. Page and protocol frame
  sizes are legitimate structural boundaries; unexplained prototype caps are
  not.
- Hot transaction, lock, WAL, page, queue, vector, and SQL paths are designed
  to avoid steady-state and per-row allocation. Copies exist only at explicit
  ownership or consistency boundaries and are counted where material.
- Zero-copy is an ownership contract: owner, validity, immutability, and reuse
  must be clear. It is never permission for unsafe aliasing.

## 9. Diagnostics must reveal, not perturb

- Expected database outcomes are statuses, not exceptions. Exceptions from
  platform APIs are translated at adapters; public JDBC errors are created at
  the JDBC boundary.
- Diagnostics explain control flow but never determine it. Disabled tracing is
  cheap, enabled tracing is bounded and attributable, and formatting or export
  cannot change transaction semantics.
- Aggregate accounting covers every event. Bounded exemplars provide detail;
  they do not replace complete counters. Correlation joins a client attempt to
  one server transaction outcome without placing benchmark-family types in the
  kernel.
- Instrumentation distinguishes eligibility, queueing, execution, waiting,
  append, force, publication, fallback reasons, and outcome classes sufficiently
  to test the claim being made.

## 10. Regressions are handled immediately

- When repeated like-for-like measurements regress, stop feature accumulation.
  Reproduce the prior tag from source in a separate worktree and compare it with
  the candidate under the same conditions.
- We first assume the measured code path changed. Host variability is measured,
  not used as a blanket explanation for a stable repeatable drop.
- Small merge boundaries make `git bisect` useful. If the cause is not quickly
  understood, revert the feature merge on the shared branch, preserve the
  investigation branch, and restore the last accepted checkpoint.
- The post-merge smoke tests the exact merged revision. If it fails, the merge
  is reverted or repaired before unrelated work is stacked on top.

## 11. Definition of done

A River change is done when the real user-visible path works; success and the
material failure/recovery boundary are tested; correctness outcomes reconcile;
allocation, copying, and scaling expectations are checked where relevant;
architecture remains cohesive; performance evidence is honest and repeatable;
unrelated work is untouched; and the accepted source, merge, evidence, and tag
are pushed so another engineer can reproduce or reverse it.
