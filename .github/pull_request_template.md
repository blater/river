## Slice contract

- Deliverable IDs:
- User-visible outcome:
- Authoritative state and invariants:
- Deliberately out of scope:

## Boundaries and ownership

- External/untrusted inputs and validation point:
- Buffers/batches/views and owner/reuse point:
- Bounded queues/history and behavior at the bound:

## Outcomes and diagnostics

- Returned status codes and caller action:
- Operational diagnostic emission point:
- Expected paths that can allocate or throw:

## Evidence

- Correctness/failure/race/crash tests:
- Allocation/copy/latency/throughput evidence:
- Compatibility/format fixtures:
- Commands run:

## Independent reviews

- [ ] Architecture steward
- [ ] Correctness adversary
- [ ] Performance/allocation reviewer
- [ ] Relational semantics reviewer, if SQL-visible
- [ ] Boundary/security reviewer, if externally reachable
- [ ] Operations/compatibility reviewer, if durable or operational
