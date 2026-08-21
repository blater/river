# P05 initial physical reference host

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Status: declared initial host; calibration and repeated benchmark evidence pending

## Environment

| Field | Value |
| --- | --- |
| Host | Physical MacBook Pro (`MacBookPro17,1`) |
| CPU | Apple M1; 8 cores (4 performance, 4 efficiency) |
| Memory | 16 GB unified memory |
| Storage | Apple SSD `AP0256Q`; TRIM enabled |
| Filesystem | APFS; local and journaled |
| Free space at declaration | 12 GiB of 228 GiB data volume available (94% used) |
| Power at declaration | AC power; battery fully charged |
| OS | macOS 26.5.2 build 25F84; arm64 |
| JVM | Oracle GraalVM 25.0.3 LTS; HotSpot JVMCI |

## Use and limits

This is the project-owner-approved initial P05 reference host. G0 mechanism
budgets may be derived from repeated controlled runs on this machine with raw
artifacts, warmup/steady-state rules, control-variance samples, power state,
thermal observations, filesystem free space, and background activity recorded.

Results from this host are not portable Linux or server-hardware performance
claims. Direct Ingres comparison is authorized but optional. A later dedicated
Linux runner should revalidate performance before portable product claims.

The current 12 GiB free-space margin is sufficient for bounded mechanism
smokes but is a constraint for larger scale, recovery, checkpoint-storm, and
write-amplification runs. Those runs must declare bounded scratch use and fail
before exhausting the data volume; larger evidence needs reclaimed space or a
separately declared physical volume.
