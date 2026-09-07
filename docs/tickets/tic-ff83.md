---
id: tic-ff83
status: in_progress
type: task
priority: 2
assignee: blater
delivery: code
base-commit: 5627543e624ac5a22d35e940e1f8bf6cf506b325
branch: ticket/tic-ff83-test-build
tags:
    - testing
    - maintenance
created: 2026-09-07T20:19:28.661318Z
---
# Avoid repeated nested builds in dependency visibility checks

Implement only the accepted build findings from tic-37c1, preserving the named survivor coverage.


## Scope and validation

Use the accepted decisions in [tic-37c1](tic-37c1.md). Test-only changes; preserve
production behavior and named coverage. The lead owns serial Gradle validation
with --no-daemon, integration, and closure. No TPS or provenance redesign.

## Delivery evidence

The existing positive and negative compiler proof is unchanged. Gradle can now
reuse its passing output until root/module/buildSrc/settings/wrapper inputs or
the Gradle/JVM/compiler identity changes. The selected Java 25 compiler is
recorded and pinned for the nested build. A clean removes the output and forces
the proof to run. Actual module graph checks still run independently.

The lead authored the input declarations; Luna independently reviewed them and
identified the need to record and pin the selected compiler rather than only
the outer JVM. No general provenance or compiler-test framework was added.

Validation with `./gradlew --no-daemon`:
- `verifyProjectDependencyVisibility verifyModuleGraph verifyBuildPolicyFixtures`: passed, 31s.
- unchanged `verifyProjectDependencyVisibility`: UP-TO-DATE, build 7s.
- temporary comment in a module build file: proof reran and passed, build 18s.
  The temporary comment was then removed.

Logs are under `/private/tmp/river-test-streamline-evidence-20260907/` as
`build-first.log`, `build-repeat.log`, and `build-invalidated.log`. These times
include Gradle startup and are diagnostic samples. Clean integration validation
is recorded with the final test-streamlining delivery.
