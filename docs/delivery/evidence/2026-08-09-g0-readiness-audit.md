# G0 implementation-readiness audit

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Audited commit: `dcce8b2`

Reviewer role: independent G0 readiness auditor

Verdict: **no-go**. Keep G0 `not-started`, M0 `active`, and do not create a
milestone tag.

Historical note: this verdict applies to audited commit `dcce8b2`. The later
[project-owner decision record](2026-08-09-project-owner-decisions.md) supplies
the provenance approval, removes direct legacy compatibility and an Ingres
comparison from G0, permits initial measurement on the current physical host,
and corrects circular future-implementation dependencies. G0 still requires a
new audit after the remaining local evidence is integrated; this file is not
silently rewritten as that future verdict.

## Evidence already present

- The accepted product charter fixes the local-first product scope, durability
  vocabulary, source-of-truth model, exclusions, and engineering objectives.
- The Java 25 build has a checksum-pinned wrapper, an isolated local gate,
  reproducible JAR settings, compiler warnings as errors, a declared module
  DAG, source-policy checks, and a manual CI workflow.
- The module skeleton, base/status/ownership primitives, bounded observability
  contracts, deterministic scheduler, faulting file model, and bounded crash
  harness exist and have locally passing tests.
- The coupled ADR set now uses consistent lineage, durability-end, retention,
  checkpoint, backup, and fatal-fence terminology. Its latest architecture
  review found no remaining blocker in that contract scope.
- P09 mechanism prototypes and P10/K03 journal contracts are active in isolated
  worktrees. They are not evidence at the audited commit and are not yet
  promotion-ready.

## Hard blockers

| Area | Missing promotion evidence | Source of authority |
| --- | --- | --- |
| P01 provenance | Qualified legal/provenance approval and complete approved artifact inventory | P01 and ADR 0002 |
| P04 legacy support | Approved, pinned legacy source/test inventory and reviewed classification | P04 and provenance policy |
| P05 benchmark baseline | Calibrated dedicated Linux/device runner, approved Ingres build, repeated raw same-machine results, and numeric budgets | P05 and performance plan |
| P06 architecture | Acceptance of evidence-gated ADRs 0002, 0004, 0005, and 0008 | P06 and ADR index |
| P09 prototypes | All five evidence families rerun on P05 hardware and independently reviewed | P09 |
| P10 journal | Provider-neutral contracts, deterministic fake, and mapping/frontier/outcome evidence | P10 and F3 |
| G0 | Complete P00-P10 promotion set and independent gate review | G0 |

The local macOS host and Colima may provide developer and functional evidence.
They do not establish physical device durability, independent failure domains,
or canonical performance budgets.

## Locally closable gaps

1. Add negative fixtures for the module graph, internal-package boundary,
   source policy, and forbidden APIs; add isolated clean-build and reproducible
   archive comparison evidence.
2. Strengthen package/export enforcement beyond text matching and record an
   independent dependency-graph review.
3. Complete typed lineage identities, allocation/ownership evidence, the
   durable-directory and atomic-installer SPIs, and deterministic fake behavior
   for delayed completion.
4. Build versioned benchmark/result schemas, generated workload seeds,
   latency-aware drivers, immutable manifests, and local developer smokes.
5. Complete provisional K03 journal and transaction contracts with deterministic
   providers. Do not freeze durable encodings while P05/P09 remain open.

## Dependency-correct continuation

1. Integrate P09 and P10 only after correctness, performance, and architecture
   review. Record them as active or implemented local evidence, never passed.
2. Close the local P02/P03/P07/P08 gaps and finish the P05 harness lane.
3. Obtain the qualified provenance decision, approved legacy inventory, and
   dedicated baseline runner independently of the clean-room implementation.
4. Rerun P09 on declared P05 hardware, freeze numeric budgets, resolve the
   proposed core ADRs, and perform a new independent G0 review.
5. Only after G0 passes, promote the K01/K02/K03 fan-out and pursue S1: an
   inspectable empty database. Production WAL work must not bypass that gate.

## Promotion decision

No G0 or M0 status changes and no tag are authorized by this audit. Passing
local tests is necessary but not sufficient for this gate.
