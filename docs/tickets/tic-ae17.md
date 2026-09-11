---
id: tic-ae17
status: open
type: bug
priority: 1
delivery: code
created: 2026-09-11
---
# Restore native compilation on the Apple M5 platform

Unchanged master `c7302290` fails native compilation on Apple M5/macOS 26.6
with Oracle GraalVM 25.0.4+7.1. The ordinary O3 task fails with a
`ControlFlowGraph.rpoInnerLoopsFirst` NullPointerException compiling
`IndexedKernelVisibility.nextEntry`. The documented PGO instrumentation task
also fails: both control-flow analysis and a shared-arena session-check
inlining assertion involving `Unsafe.convEndian` are reported.

Logs: `/private/tmp/river-rebaseline-20260911/native-build.log` and
`instrumented-build.log`. The prior platform's PGO profile is unavailable.
The failed build removed the previous ignored `bin/river` output. A tested
JVM launcher remains at `/private/tmp/river-rebaseline-20260911/river-jvm`.

Independent investigation identified upstream
[Graal issue 13321](https://github.com/oracle/graal/issues/13321) and
[PR 13334](https://github.com/oracle/graal/pull/13334). Installed 25.0.4 lacks
that concrete segment-accessor change. It is not a demonstrated fix for River's
`Unsafe.convEndian` assertion or the separate control-flow crash.

## Approach

Obtain a supported GraalVM 25 release/backport addressing the failures, or
produce a focused compiler reproducer and resolve the cause. Preserve O3,
shared-arena support, buffer ownership, durable ordering and runtime semantics.
Do not silently downgrade optimization or disable safety to make the build pass.
Keep this inherited compiler problem separate from the source-score campaign.

## Acceptance

Current master builds through the ordinary native task and documented PGO
workflow. The actual executable passes authenticated startup, SQL writes/reads,
crash/restart durability and graceful stop. Record exact toolchain, build input,
profile and workload evidence, refresh `bin/river`, and establish the native
baseline on this platform. JVM evidence does not certify native execution.
