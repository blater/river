---
id: tic-6d42
status: in_progress
type: epic
priority: 1
delivery: none
created: 2026-09-10
---
# Prepare, protect and locate each insert once

Remove repeated moderately expensive work from River's INSERT path. Give each
calculation and semantic decision one owner, and carry its result to the consumers
that need it. Improve actual database work per transaction without changing SQL,
isolation, durability or the benchmark workload.

## Evidence

Four-worker prepared single-row INSERT profile at `ad1db42f`, checkpoint
`perf-checkpoint-20260910-wal-range-sync`:
`/private/tmp/insert-wall-time-20260910/README.md`, raw profiles and tables beside it.
`SqlDescriptorPointInsertExecution.execute` occupies 7.99 thread-seconds;
unique-key validation beneath it occupies 5.74 (72%), published-index probes
3.96, and lock acquisition 2.53. These inclusive figures overlap. Source confirms
three value-building passes, two row encodings/mutation plans, two unique-key
validation phases and further key protection during staging.

`LockRadixDirectory.get` accounts for 1.45 seconds of self time beneath INSERT,
traversing seven directory levels before the final slot. MariaDB's clustered
insert uses a positioned leaf cursor and an optimistic leaf-local modification;
River separately stages a logical base row and primary-key tuple mapping.
The graphs are statistical wall estimates, omit unmounted River virtual-thread
waits and use different database transports. They identify work to investigate;
they do not establish a cross-database latency ratio or promised TPS gain.

## Engineering contract

- Do moderately expensive work once per valid input and lifetime: expression
  evaluation, encoding, key planning, uniqueness checks, index searches, lock
  resolution and resource sizing. DRY means sharing the result and its owning
  decision, not merely calling the same helper repeatedly.
- Retain results in existing statement, transaction, page or lock working state.
  State the owner and lifetime. Recompute only when an input or relevant state
  changes; name that change. A cursor or pointer must not outlive its page pin,
  latch, generation or other validity boundary.
- Validate external inputs and persisted bytes at admission. Consumers of
  validated internal values must not repeat the same expensive validation.
  Do not remove checks for genuinely changed data or visibility.
- Preserve bounded memory and long addressability. No general memoization
  framework, new cache registry, duplicate executor, cross-layer admission token
  system, permanent method inventory or arbitrary capacity cap.
- Replace superseded paths and their tests in the same delivery. Apply the rule
  across the touched vertical path, not as a repository-wide cleanup campaign.
  Cheap local checks need no caching machinery.

## Children and delivery order

1. [tic-a73c](tic-a73c.md): one preparation and admission path through staging.
2. [tic-2e91](tic-2e91.md): one focused unique-key lookup with reusable validated
   key/page state; builds on the admission owner from the first delivery.
3. [tic-8b64](tic-8b64.md): eliminate fixed-depth lock storage traversal and
   repeated segment resolution. Technically independent; scheduled third so the
   remaining cost is measured after duplicate acquisitions are removed.
4. [tic-4f20](tic-4f20.md): decide whether clustered rows and leaf-local mutation
   are the next justified architecture change. Decision only; do not smuggle a
   storage-format rewrite into the earlier stories.

The larger commit pipeline remains with [tic-e5ff](tic-e5ff.md), including
[tic-ca05](tic-ca05.md). This epic owns statement insertion and its immediate
storage/lock consumers; it does not duplicate commit-sealing or WAL work.

## Validation and completion

Each code child starts from the latest accepted checkpoint. Use fresh adjacent
baselines, the same four-worker INSERT probe and matched `tools/tps-test.sh`
samples. Inspect work removed as well as throughput; use temporary diagnostics
or an existing test-provider seam, not permanent invocation-count policies.
Investigate repeated regressions, retries or invalid outcomes. Capture touched
slopmark scores and focused independent correctness review, then existing affected
module/clean checkpoint and real executable checks with Gradle `--no-daemon`.
Do not repeat MariaDB or a JVM/native matrix for every local edit. Follow the
existing performance checkpoint process, recording results in the existing ledger.

Close when code children are delivered, the architecture decision is recorded,
and evidence shows the targeted repeated work removed with correctness preserved
and no unexplained sustained regression. A rejected architecture proposal is a
valid final decision; implementation requires a separately bounded ticket.

## User-directed execution plan (2026-09-10)

The host will temporarily run at reduced CPU power. Implement steps 1–3 on
separate reviewable branches and prepare step 4's source-based architecture
assessment. Run correctness tests and slopmark now; defer all performance runs,
performance acceptance, tags and integration merges to step 5. Do not label
pre-throttle profiles as adjacent baselines for the new implementations.

Step 5 starts when the user confirms the platform is ready for measurement.
Take fresh control/candidate measurements for each ticket in dependency order,
remediate it, then tag/merge/push that accepted ticket before advancing to the
next. Rebase dependent candidates onto the accepted predecessor as needed and
repeat affected correctness checks after remediation. The architecture decision
remains provisional until the resulting profiles are available. This explicit
user instruction supersedes the usual per-feature immediate performance gate;
no code ticket closes merely because correctness tests pass.

## Candidate branches

These pre-measurement candidates share production base `ad1db42f`. Step 5 was
authorized on 2026-09-10 and is now running in dependency order. Each ticket and
the performance ledger record its subsequent acceptance and integration point.

| Ticket | Branch | Current checkpoint |
| --- | --- | --- |
| tic-a73c | `ticket/tic-a73c-insert-admission` | `b66de835`: full engine and source/module checks passed; independent review accepted. |
| tic-2e91 | `ticket/tic-2e91-unique-lookup` | `1a1d9084`: format/storage and focused engine tests passed; independent review accepted. |
| tic-8b64 | `ticket/tic-8b64-lock-storage` | `7c7fee93`: focused and full transaction tests passed; independent review accepted. |
| tic-4f20 | `ticket/tic-4f20-storage-assessment` | `efb95e00`: source assessment prepared; storage decision awaits step 5 profiles. |

Each branch owns its ticket's implementation evidence. The planning branch is
`ticket/tic-6d42-insert-efficiency`; it changes no production code.
