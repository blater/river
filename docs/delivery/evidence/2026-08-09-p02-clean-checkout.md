# P02 clean-checkout local evidence: 2026-08-09

<!-- markdownlint-disable MD013 -->

Status: independently reviewed source-checkout isolation evidence; not P02 or
G0 promotion evidence

## Scope

This slice adds `./verify-clean-checkout`, a local-only gate that clones the
committed `HEAD` into a temporary repository, checks out that commit detached,
and requires the checkout to be clean before invoking the authoritative
`./verify` gate. Local modifications, ignored files, and untracked files in the
developer worktree are consequently absent from the build input.

This is specifically a source-checkout isolation gate, not a hermetic runner or
supply-chain proof. It establishes which committed source tree is built and
prevents common workspace-local Git and Gradle build-logic injection.

The clean build runs all three Gradle invocations in offline mode using an
explicit existing absolute Gradle user home. It rejects Gradle user-home init
scripts, user-home `gradle.properties`, tracked repository symlinks, and Git
links. Checkout hooks are disabled, ignored files count as pre-build dirt, and
Git system/global configuration and attributes are suppressed. It derives the
exact wrapper cache path from the checked-in distribution URL and requires a
non-symlink completion marker and executable, the supported wrapper path
properties, exactly one install root, and the exact versioned launcher JAR
before wrapper startup. It then
relies on the integrated dependency-ledger gate to checksum every resolved
external JAR and the checked-in wrapper JAR metadata. Missing cached inputs fail
the build rather than being downloaded.

## Validation

| Field | Value |
| --- | --- |
| Implementation commits | `406a957`, `120d2b8`, `6dfe11c`, `2be227b`, `9ce3e2a`, `409dc62` |
| Host | macOS 26.5.2, arm64 |
| JVM | Oracle GraalVM 25.0.3 LTS |
| Git | 2.55.0 |
| Focused command | `GRADLE_USER_HOME=/private/tmp/river-gradle-home ./gradlew --offline verifyDependencyLedger verifySourcePolicy verifyModuleGraph verifyBuildPolicyFixtures --rerun-tasks --stacktrace --console=plain` |
| Focused result | `BUILD SUCCESSFUL`; 35 tasks executed |
| Clean-checkout command | `RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify-clean-checkout` |
| Archive results | Two offline, uncached, forced archive builds passed; 99 actionable tasks per build; the exact 58-archive set compared byte for byte |
| Final clean/check result | `BUILD SUCCESSFUL`; 117 tasks executed with task reruns and the build cache disabled in detached commit `409dc621c05d26867d1dd06336075c8a53a52ac0` |
| Independent review | `SAFE` at evidence tip `76d1b72`; all strict-review negative probes passed |

The clean-checkout gate disables the Gradle build cache for the final check as
well as both archive builds so no task output can be restored from the shared
user home.

The strict-review fix run removed its temporary checkout after the successful
gate.

The reviewed sequence was then integrated through `a9c5a07`. From that exact
detached root commit, `./verify-clean-checkout` again completed both offline,
uncached 58-archive builds and the final offline, uncached 149-task check. The
gate removed its temporary clone after reporting the exact commit and success.

Fail-closed preflight checks also produced their intended stable failures:

- `RIVER_GRADLE_OFFLINE=yes` exited 2 with
  `RIVER_GRADLE_OFFLINE must be 'true' or unset` before starting Gradle;
- a relative Gradle user home was rejected;
- an absent Gradle user-home selection was rejected; and
- an existing empty Gradle user home was rejected because the pinned wrapper
  distribution was not cached.

The final strict-review probes additionally proved that:

- an `init.d` symlink is rejected as user-home build configuration;
- user-home `gradle.properties` is rejected; and
- a fake completed distribution under `not-the-pinned-url-hash` is rejected
  before the wrapper is invoked. The probe explicitly asserted that its output
  contained none of `clean detached checkout ready`, `Fetching distribution`,
  or `Downloading`.
- a fake exact-key distribution with a real completion marker and executable
  but no launcher library is rejected as incomplete with the same no-wrapper,
  no-fetch, and no-download assertion.

## Limits

- This is local reproducibility evidence on one macOS/arm64 host, not CI runner
  portability or multi-host reproducibility evidence.
- The selected JDK, executable `PATH`, Java/Gradle option environment, extracted
  wrapper distribution, and Gradle dependency cache remain trusted runner
  inputs. The wrapper/distribution metadata and resolved dependency JARs are
  checked by the existing provenance gate, but this slice does not claim a
  hermetic JDK, wrapper installation, cache, or operating-system image.
- P02 still requires combined integration-branch validation and final promotion
  review. Deterministic style/source/bytecode validation is selected; an
  automated reformatter is deliberately deferred.
- This evidence does not independently promote P01, P05, P06, or G0 and does
  not establish device durability.
