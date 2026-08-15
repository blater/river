# Project-owner Phase 0 decisions

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Authority: River project owner and qualified provenance reviewer

Status: accepted scope, provenance, review, and initial measurement decisions

## Decisions

- The Phase 0 gate model must not require implementation evidence from a later
  kernel, protocol, replication, or transaction phase. Production-provider
  conformance belongs to K04/G1, and later consumer/crash/isolation evidence
  belongs to the first gate that contains the relevant implementation.
- The existing bytecode-policy and clean-checkout work may be integrated after
  review and must be validated together on the integration branch.
- AGPL-3.0 is River's initial repository and release license. A future license
  change requires a separately accepted project decision and migration review.
- The project owner approves the current repository dependencies and the legacy
  Ingres source and test trees as reference material. River may use that
  material as a core design and test reference, with explicit provenance for
  adapted logic, tests, fixtures, messages, or layouts.
- River has no direct legacy compatibility requirement. The River-owned product
  and SQL profiles define support; legacy evidence informs design and tests but
  does not require a one-for-one historical test matrix before G0.
- Initial benchmark calibration and budgets use the current physical development
  machine with a complete environment manifest and repeated controlled runs.
  Dedicated Linux validation remains desirable for later portable performance
  claims but is not a G0 prerequisite.
- Building and benchmarking an Ingres baseline is authorized, but direct
  comparison is optional and may be deferred.
- A separate agent review context satisfies the independent-review role for
  implementation and milestone evidence unless a later gate explicitly names
  a human, security, operational, or external certification authority.
- Checksum-pinned third-party formatter and static-analysis tooling is allowed
  subject to the provenance ledger.
- Provenance remains minimal: resolved binary artifacts are ledgered and
  checksum-verified. Ordinary POM, BOM, and Gradle module metadata needs a
  separate ledger row or checksum lock only when vendored, redistributed,
  patched, or made a published claim's direct evidence. Gradle verification
  metadata may be added when that control has a demonstrated use.

## Gate effect

These decisions close the project-owner approval part of P01 and replace the
legacy-compatibility and dedicated-Linux assumptions in P04/P05. They do not by
themselves supply repeated benchmark results, numeric
budgets, proposed-ADR review, or the final independent G0 audit.

Subsequent clarification on 2026-08-09: permission to add formatting tooling
does not make it a requirement. River defers an automated reformatter until
inconsistent formatting becomes an observed maintenance problem. The existing
deterministic two-space/text policy, compiler warnings, source checks, and
bytecode audit satisfy the Phase 0 style/static-analysis intent.
