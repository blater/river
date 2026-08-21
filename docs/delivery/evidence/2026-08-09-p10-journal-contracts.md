# P10 provisional journal-contract evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Integrated commits: `0b16b1e`, `0e79d86`, `6002a2f`, `6363970`, `b2c9dec`

Evidence class: reviewed provider-contract implementation; not frozen P10 or
G0 promotion evidence

## Scope

`river-journal-api` now defines provider-neutral, caller-owned contracts for:

- bounded ordered reservation, publication, and cancellation tombstones;
- gap-free journal frontiers and local exclusive durable-WAL ends;
- capability-gated durability waits with timeout, cancellation, unknown
  completion, and fail-stop fencing;
- database, journal-generation, local-WAL-generation, and node-incarnation
  lineage;
- bounded retention leases and safe retained-minimum calculation;
- idempotent request-outcome lookup independent of reclaimable WAL slots; and
- journal-position, local-WAL-range, transaction-decision, and commit-sequence
  inspection mappings.

The deterministic provider in `river-testkit` implements those contracts
without selecting a local WAL block/segment format or a consensus algorithm.
Reusable test fixtures run the same behavioral suite against any future
provider.

## Independent review

The implementation required three correction cycles before integration. The
reviews found and the final commits fixed:

- durable request outcomes incorrectly tied to reclaimable ring slots;
- forgeable or destructively reusable reservation, wait, and lease handles;
- retention expiry and reclaimed-history lifecycle gaps;
- sequence overflow that could consume journal order;
- incomplete unknown-force and restart resolution semantics;
- stale and fatal durability-wait cancellation precedence; and
- a provider suite that originally exercised only the local durability tier.

The final review found the provisional P10/K03 journal contract safe to
integrate. The last documentation correction makes the tested stale-handle
retirement behavior explicit.

## Local validation

At integrated commit `b2c9dec`:

- `RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks`
  completed successfully;
- both uncached, forced archive builds produced the exact 58 expected archives
  byte-for-byte identically;
- the final check executed 84 tasks; and
- 130 tests ran with zero failures, errors, or skips.

The reusable journal-provider suite contains 20 contract scenarios. Additional
API, review-regression, allocation, platform, base, observability, testkit, and
prototype tests are included in the 130-test integrated total.

## Required before P10 can pass

- Establish numeric allocation, copy, queue, latency, and capacity budgets on
  the declared P05 runner; the current warmed allocation test proves a local
  mechanism property, not an accepted budget.
- Complete and accept the P06 decisions that depend on P01/P05/P09 evidence.
- Independently review the final vocabulary together with the accepted durable
  formats. The provisional transaction-side K03 ports now have their own
  reviewed evidence, but neither side is a frozen production provider.

Production local-WAL conformance is K04/G1 evidence, not a P10/G0 prerequisite.
The later replicated provider runs the same suite at its owning replication
gate; provider-specific mechanics may not weaken the P10 contract.

## Promotion decision

P10 is `implemented`: code and independent contract review exist, but the
vocabulary is provisional and not frozen. P05, P06, G0, and M0 remain
unpromoted. No milestone tag is authorized by this evidence.
