# Local cluster validation

Status: reserved for R20/R21 research and R2/R3 implementation

River will use the available Colima environment as the preferred local cluster
and fault-integration runner once the replicated-journal contracts are ready.
It complements the in-process deterministic simulator; it does not replace it.

## Intended local coverage

- isolated River node containers with independently mounted data/WAL volumes;
- client and workload drivers outside the replica containers;
- deterministic transport proxy rules for delay, loss, duplication, reorder,
  asymmetric partitions, and connection reset;
- process kill/restart and fresh-incarnation behavior;
- volume loss, capacity limits, I/O failure adapters, and corruption fixtures;
- leader transfer, member replacement, state sync, ring-wrap catch-up, and
  rolling mixed-version rehearsal;
- capture of node logs, metrics, frontiers, histories, manifests, and failure
  seeds as one test artifact.

## Evidence boundary

Colima results are authoritative for local functional cluster integration and
reproducible orchestration bugs. They are not canonical TPS, latency, device
durability, or failure-domain evidence because the containers share one macOS
host and its virtualized Linux kernel/storage stack.

Canonical R2/R3 performance and resilience claims still require the dedicated
Linux topology in the performance plan, with real node/device failure domains,
client load off replica hosts, calibrated storage/network, raw histograms, and
independent repeated samples.

## Activation dependency

Do not build containerized replica stubs merely to exercise orchestration. The
first Colima topology is activated after P10/K03 freezes the journal contracts
and R20/R21 define the consensus ports, failure model, and deterministic
cluster oracle. Until then, local validation remains `./verify` plus the
in-process fault scheduler/file model.
