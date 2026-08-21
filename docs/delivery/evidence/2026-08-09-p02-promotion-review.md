# P02 engineering-policy promotion review

Date: 2026-08-09

Status: passed after independent review

## Accepted engineering contract

The engineering/persona charter is an accepted working agreement. Its review
matrix, two-space/no-tab rule, trusted-internal versus external-input boundary,
status-first error model, bounded ownership rules, and allocation/copy review
requirements are the Phase 0 engineering contract.

The project-owner decision selects deterministic source/style enforcement,
Java 25 `-Xlint:all -Werror`, source forbidden-API checks, compiled hot-path
bytecode auditing, dependency/cycle/package checks, and warmed allocation tests.
An automated source reformatter is deliberately deferred until inconsistent
formatting is an observed maintenance problem.

## Executable evidence

The reviewer confirmed that:

- tabs and odd indentation fail for Java/build/configuration sources, `.sh`
  files, and tracked extensionless scripts including `gradlew`, `verify`, and
  `verify-clean-checkout`;
- dedicated negative fixtures cover shell tabs and extensionless odd
  indentation while the non-script `LICENSE` text is deliberately excluded;
- exact inherited dependency edges, unknown/missing/forbidden edges, cycles,
  and cross-module internal-package leakage fail closed;
- the expected set of 58 main/source archives is derived from the module graph
  and two clean, uncached, forced builds compare byte for byte;
- the detached-checkout gate suppresses hidden Git/Gradle source inputs, uses
  the exact pinned offline wrapper cache, disables the task cache, and removes
  its temporary clone; and
- the manual Ubuntu/JDK 25 workflow invokes the same local gate without making
  slow hosted execution mandatory during the initial local-first phase.

The reviewer independently ran `./verify-clean-checkout` against exact commit
`56c29d0`. Both 58-archive builds and the final 149-task check succeeded, and
the gate reported the exact detached commit before removing the clone.

## Promotion decision

P02 is `passed`. This review does not promote P01, P03, P05, P06, P07, G0, or
M0. P03 may now receive its own final promotion review because its P02 hard
dependency and isolated-checkout evidence are satisfied.
