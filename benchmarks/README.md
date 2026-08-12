# River benchmarks

Status: local P05 infrastructure and P09 scaffolding; no canonical baseline yet

Canonical results use the process in the performance plan. Shared CI and local
developer machines run functional and smoke measurements only. Dedicated Linux
hardware, raw samples, calibration, an approved Ingres comparison build, and
numeric budgets are still required before P05 or G0 can pass.

Each run writes an immutable manifest and raw result directory outside source
control. The manifest records:

- River and baseline commits;
- JDK, Gradle, collector, heap/direct/native settings;
- OS, kernel, filesystem, mounts, device, firmware, write-cache and force
  settings;
- CPU, memory topology, frequency/governor, isolation and background load;
- network interfaces, topology, RTT and failure domains;
- durability tier and exact acknowledgement condition;
- generator/dataset version, seed, scale, skew and checksums;
- warm-up, measurement, concurrency/offered rate and sample ordering;
- raw HDR histograms, counters, profiles and time series.

`manifest-template.yaml` is intentionally invalid as promotion evidence until
every required field is replaced with an observed value.

## Local harness smoke

The `river-bench` local harness now supplies:

- version 1 JSON Schemas for the manifest, result, and logical sample row under
  `river-bench/src/main/resources/io/riverdb/bench/harness/schema`;
- a schema-driven validator which rejects missing, unknown, mistyped, and
  bounded numeric fields before any run directory is created;
- fixed-seed, River-owned, partial in-memory `riverbank_tiny` and
  `riverpapers_tiny` schema-v1 generators with pinned fixtures and SHA-256
  checksums; and
- fixed-footprint HdrHistogram recording which keeps closed-loop service
  latency distinct from open-loop service, intended-schedule, and
  coordinated-omission-corrected service views.

Run it locally with:

```shell
GRADLE_USER_HOME=/private/tmp/river-gradle-home \
  ./gradlew :river-bench:benchmarkSmoke
```

Each invocation creates a new directory under
`river-bench/build/benchmark-smoke`. The writer preflights every document and
path, snapshots and verifies workload bytes, writes into same-filesystem
staging, verifies all referenced SHA-256 values, emits `result.json` as the last
completion marker, and atomically publishes the directory. A failure or existing
run ID never exposes or overwrites a partial result. `manifest.json` records the
environment, workload versions, seeds, checksums, and generator configuration.
`samples.tsv` contains latency summaries, and `result.json` binds the manifest,
sample table, and workload TSV files by name, path, and SHA-256. These synthetic
local artifacts prove harness behavior only. They are explicitly marked
`developer_smoke_not_promotion_evidence`.

An interrupted retry with the same run ID recovers only a direct child staging
directory carrying the matching v1 ownership marker and expected regular-file
names. Unexpected files, directories, or symbolic links cause recovery to stop
without deleting them.

The v1 generators build a deliberately small TSV in memory. They cover harness
determinism and relational workload shape only; they are not the canonical
RiverBank/RiverPapers schemas or a scale generator. Canonical work requires
streaming generation/adaptation, complete constraints and expected aggregates,
and the dedicated-run evidence listed in the backlog.

See [external-adapter-backlog.md](external-adapter-backlog.md) for the optional
provenance-cleared realism adapters and the remaining canonical-run work.
