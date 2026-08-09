# River benchmarks

Status: P05/P09 scaffolding; no canonical baseline yet

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
