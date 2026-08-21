# P02/P03 local build-policy evidence: 2026-08-09

<!-- markdownlint-disable MD013 -->

Status: integrated and independently reviewed local evidence; not P02/P03 or
G0 promotion evidence

## Scope

| Field | Value |
| --- | --- |
| Integrated commits | `6132515`, `ace4ba4` |
| Host | macOS 26.5.2, arm64 |
| JVM | Oracle GraalVM 25.0.3 LTS |
| Gradle | Wrapper 9.7.0, distribution SHA-256 pinned |
| Full local gate | `GRADLE_USER_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks` |
| Full-gate result | `BUILD SUCCESSFUL`; 91 tests, 0 failures, 0 errors, 0 skipped |
| Policy command | `GRADLE_USER_HOME=/private/tmp/river-gradle-home ./gradlew verifyBuildPolicyFixtures verifySourcePolicy verifyModuleGraph --stacktrace` |
| Policy result | `BUILD SUCCESSFUL`; all seven negative fixture families produced their required diagnostic and the test-source exclusion fixture passed |
| Archive command | `GRADLE_USER_HOME=/private/tmp/river-gradle-home ./gradle/verify-reproducible-archives.sh` |
| Archive result | `BUILD SUCCESSFUL` twice with `--no-build-cache --rerun-tasks`; the derived exact set of 58 archives was present and compared byte for byte as identical |

The 58 archives comprise the main and sources JAR for each of the 29 declared
River modules. The standalone comparison emitted
`build/reports/reproducible-archives.tsv` with one result row per archive.

## Checks exercised

- Java package ownership and cross-module internal-package access based on
  lexed package and qualified-name declarations, not raw substring matching;
- raw Java Unicode-escape rejection before lexical policy evaluation;
- explicit internal-package export rejection;
- exact inherited main compile/runtime project-dependency graph comparison and
  deterministic cycle detection, including a custom-configuration fixture;
- stable diagnostics for tab, odd indentation, internal access, forbidden
  hot-path API, Unicode bypass, forbidden dependency, and dependency-cycle
  fixtures;
- the same tab/two-space rules for `.sh` and the tracked extensionless Gradle
  and River gate scripts, with dedicated shell-tab and extensionless-indent
  negative fixtures;
- a source-level forbidden-API rule scoped only to named hot-path package
  families in production sources, with a test-source exclusion fixture; and
- byte-for-byte comparison of main and sources JARs from two clean local
  uncached, forced assemblies using the repository wrapper and selected local
  Gradle user home. `RIVER_GRADLE_HOME` explicitly overrides an existing
  `GRADLE_USER_HOME`; otherwise the existing value is preserved.

## What this does not prove

- The corrected policy slice is integrated and its focused final review found
  no remaining integration blocker.
- P02 still requires integrated clean-checkout evidence and promotion review.
  Deterministic source style, compiler, source-policy, and bytecode checks are
  the selected Phase 0 stack; an automated reformatter is deliberately deferred.
- P03 still requires independent graph/package-boundary review and a clean
  checkout reproduction after integration.
- Source analysis cannot prove absence of hidden library allocation, boxing,
  exception construction, or reflection. Those remain bytecode and runtime
  measurement responsibilities.
- The run is local functional/reproducibility evidence, not canonical
  performance, device-durability, P05, or G0 evidence.
