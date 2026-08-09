# River P09 prototypes

Status: disposable, developer-only measurements; not a production API or format

This module holds mechanisms used to test Phase 0 design assumptions. Production
modules must never depend on `river-bench`, and none of the layouts, checksums,
status mappings, queue algorithms, or measurement results here are authoritative.
WAL event/byte counters use race-safe atomics. Maximum occupancy is an observed
high-water diagnostic rather than a queue correctness invariant.

The current mechanisms cover:

- bounded preallocated WAL claim/direct-encode/checksum/gap-free publication;
- reusable primitive columns and selection-vector scans;
- 8/16/32 KiB positional NIO read/write/force against owned temporary files;
- first-page-image versus double-write first-dirty/redirty/checkpoint-storm
  accounting, retaining WAL/staging/data bytes and force classes separately; and
- a fixed-layout append/visibility-scan version-store alternative.

Run deterministic tests and all repository checks with:

```shell
RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify
```

Run the short local measurement with:

```shell
RIVER_GRADLE_HOME=/private/tmp/river-gradle-home \
  ./gradlew :river-bench:prototypeSmoke
```

It writes `results.json` and `manifest.json` under
`river-bench/build/prototype-smoke`. The files identify themselves as developer
evidence only, record environment and JOL layout details, and explicitly do not
freeze budgets or claim P05/G0. The NIO numbers include `FileChannel.force(false)`
on the developer machine and are not storage-device durability evidence.
The smoke intentionally samples only 10,000 hot-path iterations; use JMH and a
dedicated manifest for decisions or stable comparisons.

For a short JMH mechanism check, run `:river-bench:jmhSmoke`. Normal JMH defaults
on `MechanismBenchmark` provide longer warmup and measurement runs when invoked
directly. Neither command is a release gate.
