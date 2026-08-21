# W0 local verification: 2026-08-09

<!-- markdownlint-disable MD013 -->

Status: passing implementation evidence; not G0 promotion evidence

## Scope

| Field | Value |
| --- | --- |
| Integration commit | `41abe1c` |
| Host | macOS 26.5.2, arm64 |
| JVM | Oracle GraalVM 25.0.3 LTS |
| Gradle | Wrapper 9.7.0, distribution SHA-256 pinned |
| Command | `RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks` |
| Result | `BUILD SUCCESSFUL` |
| Tests | 61 run, 0 failed, 0 errors, 0 skipped |

Every Gradle task was rerun. The evidence therefore does not rely on restored
test results from a previous source state.

## Checks exercised

- Java 25 compilation with `-Xlint:all -Werror`;
- all module unit and concurrency tests;
- complete approved project-dependency graph check;
- tabs, two-space source indentation, and internal-package reference policy;
- reproducible archive configuration;
- checksum-pinned wrapper execution through the local `./verify` gate.

Test distribution:

| Module | Tests |
| --- | ---: |
| `river-base` | 23 |
| `river-observability-api` | 11 |
| `river-platform` | 2 |
| `river-testkit` | 25 |
| Total | 61 |

## Independent review record

The first base review reproduced ownership resurrection after release. Commit
`0b4f4e3` made release irreversible, made retryability code-specific, hardened
fatal first-failure fencing, and added race and edge tests. The independent
re-review accepted the result as partial P07 evidence.

The first deterministic-testkit review reproduced an injected crash for which
the crash harness never restarted or invoked its verifier. It also reproduced
an unsupported fault action being silently ignored. Commit `16c2434` made the
harness recover injected crashes, made fault scripts fail closed, added
per-critical-class reserves and scheduler injection, corrected overlapping
rule counting/corruption/lifecycle semantics, and expanded reproducible trace
tests. Independent re-review is recorded separately when complete.

## What this does not prove

- P02/P03 still need negative policy fixtures and an independent clean-checkout
  reproduction before being marked passed.
- P05 has no dedicated-runner calibration, Ingres baseline, or accepted numeric
  budgets yet.
- P06 ADRs are not yet accepted.
- P07 active ring-path allocation/copy/throughput evidence remains for P09.
- P08 delay/stale-read semantics depend on the filesystem/I/O ADR and the named
  crash registry grows with each later slice.
- P09 prototypes and P10 journal provider contract suite have not begun.
- No durable format, local WAL, page, transaction, SQL, JDBC, or consensus gate
  is claimed by this run.
