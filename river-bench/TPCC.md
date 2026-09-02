# River one-warehouse TPC-C engineering acceptance

This JDBC-only production driver creates the nine standard TPC-C entities and
their composite keys, streams a one-warehouse load, runs all five transactions,
checks invariants, and writes a bounded evidence artifact. Results are River
engineering measurements. They are not audited or official TPC-C results and
are never labeled `tpmC`.

## Promotion lifecycle

The database/server lifecycle belongs to the caller. Phase one must target an
empty River database and exits after `CHECKPOINT`:

```sh
./gradlew :river-bench:tpccAcceptance --args='--url=jdbc:river://localhost:54321 --phase=load-run-checkpoint --artifact=/absolute/path/river-tpcc.properties'
```

The defaults are the promoted profile: one warehouse, ten terminals fixed to
home districts 1 through 10, five minutes of warmup, 30 measured minutes,
standard keying/think scheduling, 32-row load commits, and four bounded
whole-transaction attempts. Stop the server, close River, open the same
database from its checkpoint/recovery path, restart the loopback server, then
run phase two (the port may change):

```sh
./gradlew :river-bench:tpccAcceptance --args='--url=jdbc:river://localhost:54322 --phase=recovery-verify --artifact=/absolute/path/river-tpcc.properties'
```

Phase two refuses an artifact with a different scale or seed, recomputes the
database fingerprint, reruns invariants and a Stock-Level transaction, and
adds the recovery timestamp, run identity, and recovered fingerprint to the
same artifact. Merely reconnecting JDBC does not satisfy this lifecycle.

The test `TpccLifecycleAcceptanceTest` owns an actual database close/open and
server restart. Its tiny no-wait case runs normally. Set
`RIVER_TPCC_FULL=true` to enable its full-scale five-minute/30-minute case.

## Explicit stress profile

The scheduling override below is a nonstandard saturation profile. It removes
keying and think waits but preserves terminal ownership, transaction semantics,
and the 45/43/4/4/4 selection mix:

```sh
./gradlew :river-bench:tpccAcceptance --args='--url=jdbc:river://localhost:54321 --phase=load-run-checkpoint --artifact=/absolute/path/tiny.properties --tiny --scheduling=no-wait-stress --warmup-seconds=2 --measured-seconds=5'
```

For the fixed ten-second no-wait smoke, `tools/tps-test.sh` builds the bench
classes, creates a temporary database, starts a loopback server, and launches
the JDBC workload. The temporary database is removed after the run:

```sh
tools/tps-test.sh
```


Retry base, cap, and attempt count are configurable with
`--retry-base-micros`, `--retry-maximum-millis`, and `--maximum-attempts`.
Retries use randomized exponential backoff bounded by both that configuration
and the current phase deadline. `--fresh-load=false` never appends data and
proceeds only when exact initial cardinalities and invariants match.

## Evidence and bounds

The artifact is capped at 64 KiB and atomically replaced. It records run and
recovery identity, configuration, environment, family mix, outcomes, bounded
latency percentiles, retries, rollback/retry probes, process heap/peak-pool and
GC observations, and worker allocation bytes when the VM exposes them. The
driver retains only 5-by-64 latency buckets per terminal and no generated rows
or transaction samples.

River engine page/WAL/lock/version high-water counters are not exposed through
JDBC, so the artifact reports `unavailable_via_jdbc` rather than fabricating
them. The declared JDBC batch bound (64), configured loader bound (at most 32),
retry bound, terminal count, and histogram bound are reported explicitly.
