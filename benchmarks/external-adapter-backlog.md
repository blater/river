# Benchmark dataset and canonical-run backlog

Status: backlog; no external data is downloaded, copied, or approved here

River-owned partial in-memory tiny-schema-v1 generators and pinned fixtures are
the reproducible default for local harness development. They do not implement
the full RiverBank or RiverPapers canonical schema, streaming scale generation,
or expected aggregate suites. External datasets are optional realism inputs. An
adapter may land only after the provenance policy permits the intended download,
use, derived output, and redistribution behavior. Adapters pin metadata and
validate source checksums; they never silently fetch data in a test or build.

## Optional adapters

### Banking transactions

- Pin the Kaggle owner/slug, dataset version, retrieval date, declared license,
  archive and file SHA-256 values, expected tables/columns/row counts, and River
  adapter version.
- Keep downloaded CSV files outside the repository and outside build caches.
- Validate primary/foreign-key and balance/ledger assumptions before using a
  clone for River-owned transactional mutations.
- Record attribution and share-alike handling in the approved run manifest.
- Use the adapter for ingestion, constraint/index build, joins, reports, and
  distribution realism; it does not replace RiverBank contention workloads.

### BioRxiv preprints

- Require an explicit decision for the non-commercial/share-alike terms and for
  BioRxiv text-and-data-mining guidance before any download or use.
- Pin source identity, version, retrieval date, file SHA-256, expected schema and
  rows, and adapter version without vendoring abstracts.
- Use it only for import correctness and realistic UTF-8 width/cardinality.
  Scale tests with RiverPapers generation rather than duplicating abstracts.

## Exact gaps before a canonical P05 run

The local harness does not close P05 or G0. Canonical evidence still requires:

1. a reserved, calibrated Linux runner with declared CPU, memory, thermal,
   kernel, filesystem, mount, storage firmware/cache, and network topology;
2. accepted control-variance limits and quarantine rules, supported by repeated
   calibration samples;
3. an approved, provenance-cleared Ingres build and River build produced with
   pinned toolchains and executed on the same hardware;
4. matching database images or fixed-seed regeneration and a fresh restore or
   clone for every independent write sample;
5. durability-tier and acknowledgement-contract declarations backed by device,
   filesystem, force, and network calibration;
6. cold, lukewarm, and warm states kept separate, with declared warm-up,
   measurement duration, concurrency or offered rate, and complete result
   consumption;
7. at least five independent interleaved baseline/candidate samples with
   reversed starting order, saved raw HdrHistograms, counters, time series,
   logs, and uninstrumented timings;
8. separate JFR/async-profiler/OS profiles with quantified overhead and
   allocation, copy, amplification, contention, boundedness, and recovery
   attribution;
9. reviewed numeric budgets and confidence intervals derived from those runs,
   not from developer laptops or shared CI; and
10. an independent repeatability/performance review and explicit promotion,
    blocker, or recorded trade-off decision.

Before a canonical generated-data run, replace or extend the tiny v1 generators
with bounded-memory streaming generators, version the complete relational
schema, constraints, distributions and expected aggregates, and start a new
baseline for every generator-version change.

GitHub Actions remains a manual secondary portability check during the initial
build. Local validation is the primary functional gate; canonical performance
runs belong on the reserved runner, not Actions or Colima. Colima is reserved
for repeatable functional cluster and fault-path tests once those lanes are
ready, never canonical performance claims.
