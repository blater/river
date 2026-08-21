# P05 streaming workload evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Integrated commits: `ae5358f`, `316befb`

Evidence class: reviewed partial developer workload infrastructure; not the
canonical P05 workload, performance baseline, numeric budget, or G0 evidence

## Scope

`river-bench` now streams deterministic v2 relational cores with bounded
generator state:

- RiverBank accounts and transactions, with exactly two accounts per customer,
  bounded identifiers/amounts/types, hot/cold account skew, and monotonic
  timestamps spanning every month from 2020 through 2024; and
- RiverPapers authors, documents, and document-author links, with bounded UTF-8
  title/abstract/token distributions and distinct in-range authors per paper.

Versioned v2 schemas bind workload name, family, table schema, configuration,
seed, rows, bytes, and SHA-256. Generation uses a two-pass create-once artifact
path and reusable encoding buffers. Independent observers count emitted bytes
and TSV rows during both passes, then reread persisted files for size, rows, and
digest before publication.

No external dataset, corpus text, or legacy artifact is used.

## Independent review

The first review rejected integration because an emitter could lie consistently
about row/byte counts, RiverBank timestamps covered only about 23 days at the
maximum scale, odd account counts violated the two-accounts-per-customer
invariant, and v2 workload identities lacked semantic cross-checks. It also
required the docs to stop implying this partial core was the canonical P05
workload.

The correction added independent physical observations and lying-emitter
regressions, exact five-year temporal coverage, even-account admission,
unique/family-consistent v2 identity validation, cheap path/target rejection
before expensive preflight, and conservative claim-window/manual-recovery and
machine-durability documentation. Allocation language is limited to bounded
state/source structure rather than an unmeasured zero-allocation claim.

The final independent review found no remaining required issue for integration
as developer workload infrastructure.

## Validation

The isolated final branch passed both 99-task archive builds, reproducible
comparison, the final clean check, 178 tests with zero failures/errors/skips,
source policy, module graph, workload smoke, Markdown lint, and JSON parsing.

After integration, `verifyDependencyLedger`, source policy, module graph, all
`river-bench` tests, and `workloadSmoke` completed successfully with all 72
invoked tasks executed. Persisted smoke metadata matched observed rows, bytes,
and SHA-256.

## Canonical workload gaps retained

- RiverBank branches, customers, cards, loans, payments, card transactions,
  employees, support tickets, executable operation mixes, expected aggregates,
  mutations, and version histories;
- RiverPapers revision histories, citation/query corpora, search/index
  selectivity targets, and executable query mixes;
- streaming scale calibration, allocation/copy measurement, target-size
  manifests, and canonical dedicated-runner repetition; and
- provenance-approved optional realism adapters for external datasets.

## Promotion decision

P05 remains `active`. This slice improves deterministic data generation and
artifact integrity but supplies no throughput, latency, durability, or budget
claim. P09, P06, G0, and M0 remain unpromoted; no milestone tag is authorized.
