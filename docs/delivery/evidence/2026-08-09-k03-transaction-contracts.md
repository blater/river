# K03 provisional transaction-contract evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Integrated commits: `c0be335`, `c0ada9a`, `1148af9`, `6816986`, `c442c6a`

Evidence class: reviewed provisional transaction/storage seam; not K03, P06,
or G0 promotion evidence

## Scope

`river-tx-api` now defines dependency-minimal contracts for transaction
contexts, isolation levels, snapshots, outcomes, visibility, lock requests and
tokens, lineage-qualified version pointers, caller-owned version reads,
transaction storage, and separately granted recovery transaction storage.

`river-testkit` provides a bounded deterministic provider and reusable contract
suite. The seam deliberately contains no durable codec, page layout, WAL
record, transaction manager, public engine facade, or dependency on
`river-storage`/`river-tx` implementations.

## Independent review

The first review rejected the provisional implementation for:

- an immutable indeterminate outcome that recovery could never resolve;
- version pointers that could alias across databases;
- unsafe CSN-only vacuum and starvation semantics;
- physical rollback keyed by a version pointer instead of WAL/CLR authority;
- cached-CSN visibility that could expose an aborted/in-progress owner; and
- borrowed provider backing bytes that vacuum/reuse could overwrite.

The correction removed vacuum, rollback, and cached-status shortcuts; split
recovery decision authority from the runtime storage port; qualified pointers
by database incarnation and store generation; copied reads into caller-owned
buffers; and added stale, forged, cross-provider, reuse, and retained-copy
tests.

A final review edge required stable recovery to install an earlier validated
WAL pointer when an uncertain tail is absent after restart. The final fix and
regression preserve ordinary monotonic runtime updates while allowing only the
recovery authority to make that validated lineage correction.

The final independent review found the tip safe for provisional integration.

## Local validation

The isolated final tip passed both clean 98-task archive builds, archive
comparison, an 85-task clean check, and 143 tests with zero failures, errors,
or skips. The 13 transaction-specific tests include a warmed allocation test
that executed rather than being skipped.

After integration, the dependency ledger, source policy, module graph, and
transaction suite completed successfully with all 72 invoked tasks executed.

## Deliberately deferred

- transaction/commit-sequence allocation and ordered commit publication;
- snapshot acquisition and the production transaction outcome/status stores;
- wait queues, conversion, deadlock detection, intention/table/range locking,
  escalation, fairness, and concurrency histories;
- WAL/CLR-driven statement rollback and crash-resume handlers;
- vacuum, status freezing/compaction, maintenance horizons, and long-snapshot
  policy;
- durable version/page formats and production allocation/copy budgets; and
- the public embedded command/session/result API.

These are owned by T01-T09 and the accepted P05/P06/P09/K01/K02 dependencies;
the provisional seam must not be cited as their implementation evidence.

## Promotion decision

The transaction side of K03 is implemented provisionally and independently
reviewed, but K03 is not promoted. P06, P10 vocabulary freeze, G0, and M0 remain
blocked by their named evidence, and no milestone tag is authorized.
