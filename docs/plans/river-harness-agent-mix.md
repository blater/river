# River Harness Agent Mix

<!-- markdownlint-disable MD013 -->

Status: Proposed operating profile

Date: 2026-08-21

Audience: River harness integrators, agent operators, implementation agents,
and independent reviewers

Related plan:

- [River Standalone Workload Harness Plan](river-standalone-workload-harness-plan.md)

## 1. Purpose

Define the smallest effective agent team for implementing and validating the
standalone `river-harness` repository. This profile assigns models, reasoning
effort, ownership, review independence, and escalation rules without creating
agents merely to increase activity.

This document is a bridge in the River planning workspace. When the separate
`river-harness` Git repository is created after RH0 acceptance, it becomes a
tracked `docs/agent-mix.md` in that repository and is reviewed against the
models then available.

## 2. Operating decisions

1. Use one lead integrator and at most three concurrent builders by default.
2. Give every active agent disjoint file ownership and one concrete outcome.
3. The lead integrator alone owns shared contract changes and final integration.
4. Use `high` as the default effort for critical implementation and review.
5. Use `medium` for bounded, mechanical, or well-specified work.
6. Assign no standing `xhigh` role. Escalate one bounded question only when the
   high-effort path cannot establish a correctness-critical answer.
7. Do not use `max` in the normal harness workflow.
8. Authors do not solely approve their own protocol, transaction, concurrency,
   recovery, security, or comparison-methodology work.
9. Rotate fresh review agents into freed concurrency slots rather than keeping
   a large permanent team.
10. Re-evaluate model assignments from task evidence at RH1 and RH2; model names
    and service characteristics are operational choices, not durable harness
    contracts.

## 3. Default build cell

The preferred active build cell has four agents:

| Role | Model | Effort | Primary ownership |
| --- | --- | --- | --- |
| Lead integrator and contract owner | `gpt-5.6-sol` | `high` | Repository structure, DBMS session contract, suite/binding boundary, milestone integration, disputed decisions |
| Runtime and performance builder | `gpt-5.6-terra` | `high` | Phase runner, actors, bounded queues, open/closed loop, metrics, artifacts, comparison mechanics |
| MariaDB adapter and lifecycle builder | `gpt-5.6-sol` | `high` | Driver boundary, SQLSTATE/native outcomes, sessions, process ownership, readiness, graceful shutdown, non-destructive cleanup |
| Workload semantics and binding builder | `gpt-5.6-terra` | `high` | TPC-C generation, logical transactions, invariants, verification, MariaDB/PostgreSQL bindings |

The four roles are responsibilities, not permanent agents. A milestone that
does not need one role leaves its slot free for a fresh reviewer or a bounded
reference-DBMS task.

### 3.1 Lead integrator

The lead:

- owns shared interfaces and dependency direction;
- records file ownership before parallel work begins;
- freezes a contract before sending disjoint implementation tasks;
- integrates in milestone order;
- resolves reviewer findings without silently weakening an invariant;
- runs the final affected test and evidence gates; and
- does not become the sole independent reviewer of a critical change.

Use Sol/high for normal lead work. Do not raise the complete integration turn
to xhigh because one subproblem is difficult; isolate and escalate that
subproblem under section 6.

### 3.2 Runtime and performance builder

Use Terra/high for well-bounded scheduler, metrics, artifact, and comparison
implementation after the lead freezes the contracts. Switch a task to Sol/high
when it requires material new reasoning about concurrency, coordinated
omission, bounded admission, cancellation, or comparison validity. This is a
model reassignment, not an automatic effort escalation.

### 3.3 MariaDB adapter and lifecycle builder

Use Sol/high because this work crosses a driver/server boundary, classifies
transaction outcomes, and owns a local process without permission to damage
the existing Homebrew data directory. The builder owns only MariaDB connection,
native status, and lifecycle details plus adapter-local tests. They do not
redefine TPC-C semantics, generic metrics, or the shared DBMS contract. A fresh
operations reviewer must approve process identity, start/stop, timeout, stale
PID/socket, and cleanup paths before RH1 exits.

### 3.4 Workload semantics and binding builder

Use Terra/high for deterministic generation, transaction inputs, expected
results, and DBMS bindings once the semantic profile is explicit. Move a
specific transaction or isolation question to Sol/high when it requires deeper
relational or concurrency reasoning. The suite remains independent of DBMS
connections; DBMS-specific SQL stays in bindings.

## 4. Supporting implementation roles

These roles rotate into a free slot when their milestone arrives:

| Role | Model | Effort | Scope |
| --- | --- | --- | --- |
| PostgreSQL adapter builder | `gpt-5.6-terra` | `high` | Driver configuration, session/transaction implementation, SQLSTATE mapping, adapter contract tests |
| River adapter builder (deferred) | `gpt-5.6-sol` | `high` | Wire codec, authentication, sessions, typed values, streaming results, native status and unknown-outcome mapping after the River capability gate opens |
| Reporting/exporter builder | `gpt-5.6-terra` | `medium`, then `high` for backpressure | Bencher projection, Prometheus/OpenMetrics, OTLP, bounded exporter queues |
| CI, fixtures, and documentation builder | `gpt-5.6-luna` | `medium` | Workflow wiring, deterministic fixture formatting, dashboard JSON, documentation synchronization |
| Reference test expansion | `gpt-5.6-luna` | `medium` | Repetitive table-driven cases after a Sol/Terra author establishes the test contract |

Luna is not the sole author or reviewer for wire framing, authentication,
transaction outcomes, concurrency, recovery, workload semantics, lifecycle
deletion/reset, or comparison admission.

## 5. Independent validation wave

Critical milestones replace idle builders with fresh review agents:

| Review lens | Model | Default effort | Blocking focus |
| --- | --- | --- | --- |
| Correctness adversary | `gpt-5.6-sol` | `high` | Rollback, retry, cancellation, conflict, partial response, unknown commit, crash/restart |
| Relational semantics reviewer | `gpt-5.6-sol` | `high` | TPC-C meaning, isolation, schema deviations, result and post-run invariants |
| Performance/allocation reviewer | `gpt-5.6-sol` | `high` | Coordinated omission, allocation, client saturation, queue bounds, telemetry overhead, comparison method |
| Boundary/security/operations reviewer | `gpt-5.6-sol` | `high` | Frame/config validation, credentials, lifecycle hooks, artifact integrity, cross-repository compatibility |

One fresh reviewer may carry two compatible lenses for a small milestone. The
review still names each lens and its evidence separately. Durable, recovery,
concurrency, security, and comparison claims require a reviewer other than the
author.

Review findings use the River levels:

- `BLOCKER`: correctness, durability, security, invalid comparison, or
  unbounded-resource risk;
- `REQUIRED`: violates an accepted contract or working agreement;
- `SUGGESTION`: a nonblocking simplification or improvement; and
- `QUESTION`: missing rationale or evidence.

## 6. Xhigh escalation policy

Xhigh is an escalation state, not a role configuration. Start every critical
review at Sol/high. Escalate to a fresh `gpt-5.6-sol` agent at `xhigh` only when
one of these conditions holds:

1. A high-effort review cannot resolve whether an outcome is definitely
   committed, definitely rolled back, retryable, or unknown.
2. A concurrency or recovery history remains ambiguous after focused tests and
   two high-effort analyses.
3. Two independent high-effort reviewers disagree on a correctness-critical
   contract and the lead cannot resolve the disagreement from existing
   evidence.
4. A cross-DBMS comparison depends on a subtle durability or isolation
   equivalence that high-effort review cannot establish.
5. An RH1 or RH7 lifecycle/recovery audit still has a credible data-loss,
   wrong-process shutdown, or unsafe destructive-target concern after its
   normal high-effort review.

An xhigh task must:

- state one bounded decision or failure hypothesis;
- identify the exact files, tests, histories, and prior findings to inspect;
- begin read-only;
- avoid reopening unrelated architecture;
- produce a concrete finding, reproducer, or required evidence; and
- record why high effort was insufficient.

Do not rerun an entire milestone at xhigh because one question was escalated.
If the xhigh review resolves the question, return implementation and subsequent
review to their normal model/effort settings.

## 7. Max-effort policy

`max` is outside the normal agent mix. It requires explicit project-owner
approval after a scoped xhigh review leaves an unresolved, high-impact risk such
as possible data loss, unsafe lifecycle deletion, or a materially false
comparison claim. Cost, latency, and quality must be evaluated against the same
task at xhigh before max becomes a recurring choice.

## 8. Milestone rotation

| Milestone | Active roles | Review rotation |
| --- | --- | --- |
| RH0 | Lead, runtime architect, MariaDB boundary/lifecycle builder | Architecture plus boundary and non-destructive-operations review at high |
| RH1 | Lead, runtime builder, MariaDB adapter/lifecycle builder, smoke/artifact builder | Fresh correctness/operations reviewer replaces completed smoke role |
| RH2 | Lead, PostgreSQL adapter builder, comparison builder | Fresh comparison and boundary reviewer |
| RH3 | Lead, generator builder, MariaDB binding builder, PostgreSQL binding builder | Fresh relational-semantics reviewer |
| RH4-RH5 | Lead plus transaction/binding builders with disjoint transaction ownership | Correctness adversary after every transaction slice; full semantics review before mixed workload |
| RH6 | Lead, telemetry builder, artifact/Bencher builder | Performance/allocation reviewer measures enabled/disabled overhead |
| RH7 | Lead, recovery/lifecycle builder | Fresh correctness and operations reviewers; xhigh only if section 6 triggers |
| RH8 | Lead, YCSB suite builder, existing adapter owner | Architecture review proves core contains no TPC-C conditionals |

Do not run canonical benchmarks while builder agents are compiling, testing, or
profiling on the same host. One controlled operator owns a canonical run.

## 9. File and worktree discipline

- Give every implementation agent a separate Git worktree.
- Record the worktree, branch, task, and owned paths before work begins.
- No two builders edit the shared DBMS contract, report schemas, or suite
  semantic types concurrently.
- The lead integrates contracts before dependent implementation branches.
- Keep Go build/test caches separate when measuring client allocation or timing.
- Only one lifecycle owner may use the Homebrew MariaDB data directory, and no
  canonical run overlaps builds, tests, profiling, or another DBMS process.
- After the River gate opens, only one River Gradle build runs at a time; use a
  separate River worktree and caches for cross-repository integration.
- Review agents inspect the exact candidate commit in a clean worktree.
- Only the lead performs final merges and release tagging.

## 10. Agent task brief

Every delegated task states:

1. user-visible or reviewer-visible outcome;
2. owned files and prohibited files;
3. accepted contracts and invariants;
4. external trust boundaries;
5. failure, retry, cancellation, and cleanup expectations;
6. allocation, copy, queue, and artifact bounds;
7. focused tests and measurements;
8. expected handoff to the integrator; and
9. stopping conditions that require a question or escalation.

The task brief specifies model and effort explicitly. It does not ask the agent
to redesign shared contracts unless the lead has assigned contract ownership.

## 11. Evaluation and recalibration

At RH1 and RH2, compare representative Sol/high and Terra/high assignments
where either model could reasonably own the task. Record:

- focused-test success on the first handoff;
- correctness or boundary findings discovered after handoff;
- integration rework;
- elapsed time and tool retries;
- input/output tokens when available; and
- whether the final result met every required evidence item.

Promote Terra for a task class when it matches Sol's accepted outcome with
lower cost or latency. Retain Sol where it materially reduces missed defects or
integration rework. Do not increase effort merely because a task is long; use
xhigh only under section 6.

## 12. Current model guidance

This operating profile follows the official OpenAI model positioning current
when the plan was written: Sol for complex reasoning and coding, Terra for a
balance of capability and cost, and Luna for cost-sensitive high-volume work.
Official guidance recommends medium as a balanced starting point, high or
xhigh when representative evaluation shows a quality gain, and max only for
the hardest quality-first work.

- [OpenAI model catalog](https://developers.openai.com/api/docs/models)
- [OpenAI GPT-5.6 model and reasoning guidance](https://developers.openai.com/api/docs/guides/latest-model)
