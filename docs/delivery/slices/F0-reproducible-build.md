# F0: reproducible empty build

Status: active

Deliverables: P02, P03

## Outcome

A clean checkout builds and tests all declared River module boundaries with a
repository-pinned JDK/Gradle contract. The build rejects dependency cycles,
dependencies outside the approved module graph, exported internal packages,
tabs, non-two-space source indentation, and non-reproducible archives.

## Authority and invariants

The module graph in Section 5.1 of the high-level plan is authoritative. The
build convention owns enforcement; individual modules cannot weaken it.

- Production code never depends on `river-testkit` or `river-bench`.
- `river-base` and the API boundary modules stay dependency-light.
- No package containing `.internal` is exported or consumed across modules.
- Later-phase modules may be declared boundaries, but their presence does not
  imply their deliverables have started or passed.
- The checked-in wrapper is the supported Gradle entry point.

## Trust and failure boundaries

Build scripts treat repository paths, module metadata, and generated reports as
bounded project input. CI treats pull-request source and dependency metadata as
untrusted and runs the same checks as a clean local build. A policy failure is a
build failure with a stable, actionable diagnostic; it is not silently fixed.

## Performance and ownership

F0 contains no engine hot path. Build caches and parallel workers may be used,
but build reproducibility and exact policy behavior take priority over build
speed. Generated output is confined to each module's `build/` directory.

## Merge evidence

- Wrapper distribution URL and SHA-256 are pinned.
- `./gradlew clean check` passes in a clean checkout.
- A second build produces byte-identical archives where archives exist.
- Negative fixtures prove tab/style, forbidden dependency, cycle, and internal
  package violations fail for the expected reason.
- `./verify` is the authoritative local gate and uses the checked-in wrapper.
- `./verify-clean-checkout` clones committed `HEAD` into a temporary detached
  checkout, rejects pre-build dirt and tracked symlinks, and runs `./verify`
  offline with task reruns and the build cache disabled against an explicitly
  selected populated Gradle user home. This
  proves that ignored or untracked files in a developer worktree are not build
  inputs without turning every ordinary local gate into a nested full build.
- Manually dispatched CI can reproduce the local gate when independent runner
  evidence is useful; it is not run on every initial-phase push.
- The root module report exactly matches the authoritative dependency graph.

## Implemented local policy mechanism

The build owns one shared deterministic policy implementation. It lexes Java
source while excluding comments, character literals, string literals, and text
blocks, and rejects raw Java Unicode escapes before lexing so escapes cannot
manufacture hidden tokens. It derives package ownership from declarations
rather than directory-name substrings, rejects cross-module references to an
owned `internal` package, and rejects an `internal` package in a Java module
export declaration. The module check derives inherited project edges from both
main compile and runtime classpaths, including custom configurations extended
into either classpath. It compares that graph with the approved graph and
independently detects cycles.

The current forbidden-API rule is intentionally narrow. It rejects stream and
collector APIs, including ordinary `.stream()` and `.parallelStream()` calls,
and string-formatting APIs only in production source under explicitly named
kernel hot-path package families. Tests are excluded from this performance
rule, but remain subject to layout and internal-package checks. The rule applies
today to the bounded observability event path and reserves specific WAL, buffer,
storage, transaction-commit, and vector execution package families. It does
not apply hot-loop rules to SQL parsing, planning, administration, or other
boundary/cold paths. The compiled-code audit now checks exact declared hot
methods for exception construction/throw, boxing, compiler-emitted array
allocation (including ordinary varargs call-site arrays), invokedynamic,
streams, and formatting. Transitive library
allocation and actual post-JIT allocation still require measured allocation
tests; neither static rule claims to prove them.

`verifyBuildPolicyFixtures` executes negative cases for tabs, odd indentation,
cross-module internal-package access, a designated hot-path `.stream()` call,
a Unicode-escape bypass, an unapproved project dependency inherited through a
custom configuration, and a dependency cycle. A positive fixture proves test
sources are excluded only from the hot-path API rule. Shell and tracked
extensionless gate scripts use the same tab/two-space policy, with distinct
negative fixtures; non-source extensionless text such as `LICENSE` is not
misclassified as a script. `./verify` invokes two clean archive assemblies with
task reruns and the Gradle build cache disabled.
Each build must produce exactly the main and sources JAR paths derived from the
declared module graph (currently 58 archives), and the two sets are compared
byte for byte before the final clean local check. The comparison can also be
run alone with `gradle/verify-reproducible-archives.sh`; it writes a local TSV
report beneath `build/reports/`.

`verifyHotPathBytecodeFixtures` compiles a deterministic Java 25 negative case
for every bytecode rule plus positive primitive-status and caller-owned-buffer
paths. `verifyHotPathBytecode` then audits the exact production method manifest;
missing selectors fail closed. The contract, narrow compiler-artifact allowlist,
and proof boundary are recorded in
`docs/delivery/evidence/2026-08-09-p02-p07-bytecode-policy.md`.

Set `RIVER_GRADLE_OFFLINE=true` to make all three Gradle invocations in
`./verify` use Gradle offline mode. Any other non-empty value fails closed.
`./verify-clean-checkout` always selects this mode and requires
`RIVER_GRADLE_HOME` or `GRADLE_USER_HOME` to be an existing absolute directory,
so it does not silently create a checkout-local cache or fetch missing inputs.
It also rejects Gradle user-home init scripts because those scripts would be
unversioned build logic, and it rejects user-home `gradle.properties` for the
same reason. Before invoking the wrapper it derives Gradle's exact URL cache key
from the checked-in properties and requires the completion marker and executable
at that exact non-symlink path. It accepts only the supported
`GRADLE_USER_HOME/wrapper/dists` layout and requires exactly one install root
with the exact versioned launcher JAR before wrapper startup. Resolved
dependency JARs remain subject to the provenance-ledger checksum gate. Checkout
hooks are disabled, ignored files
count as pre-build dirt, Git system/global configuration and attributes are
suppressed, and Git links are rejected with symlinks so the temporary tree
cannot silently acquire another worktree or repository as a build input.

## Out of scope

- Passing G0.
- Production durable formats or public APIs.
- SQL, server, JDBC, consensus, or native I/O implementation.
- Performance claims based on shared CI timing.
