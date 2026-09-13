---
id: tic-edoras
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-primula
delivery: evidence
tags:
    - performance
created: 2026-09-13T11:46:48.664078Z
deps:
  - tic-da4e
---
# Admit transport optimizations from current exchange and ownership evidence

### Outcome

Select a concrete remaining protocol mechanism from current-master evidence,
using tic-da4e. Preserve the existing ordered authenticated transport, transaction
program executor and public SQL contract; do not assume Payment must become one
request or that River still has the removed prepared-close acknowledgement.

### Required decisions

- Attribute exchanges, flushes, bytes, waits and lock residence by workload family
  and by attempt/commit. Distinguish prepared server-plan reuse (already delivered)
  from client handle churn. Identify independent requests versus those needing a
  prior value, status or transaction result. API capability alone is not a consumer.
- Decide separately whether `tic-gwindor` and `tic-morgoth` have useful
  real consumers and material removable cost. For pipelining name the first real
  caller and exact request sequence it can submit without dependent values; if
  none exists, reject the candidate. Any new generic program operation needs its
  own independently reviewed contract; do not smuggle workload semantics into River.
- Specify ordered response association, first-error handling and drainage,
  transaction/autocommit/savepoint boundaries, cancellation, transport loss,
  indeterminate commit and retry ownership. No automatic replay of unknown commits.
- Specify configured byte/request admission, pending-response and streaming-result
  pressure, fairness, buffer lifetime/erasure and the barrier after one-way release.
  State whether framing/version must change; migrate Java and external Go consumers
  together if it does, with no legacy dual path.
- Record the exact River/harness repository file ownership and protocol fixtures.
  External harness workload SQL/mix/isolation/retry semantics remain identical;
  its Go adapter is a separate repository delivery. Missing publication ownership
  is an explicit blocker for the affected cross-repo story, not permission to
  embed harness policy in River.

Independent boundary/security and relational reviewers approve the selected
contract plus partial-failure/streaming matrix before code. A rejection/defer
with evidence is a completed investigation. Supersedes the unconditional Payment
mapping/one-request pilot in tic-00e1/tic-af0a; those are not delivered mechanisms.
