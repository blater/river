# River 0.1.0-alpha.1

River 0.1.0-alpha.1 is the first public evaluation checkpoint. It provides a
useful embedded and authenticated loopback SQL/JDBC surface, but it is not a
production release. Use disposable data and keep independent backups.

## Included in this alpha

- Durable heap and B+tree storage, WAL recovery, checkpoints, transactions,
  indexes, constraints, sequences, views, and quiescent backup/restore.
- Embedded SQL plus authenticated TLS loopback JDBC and CLI access.
- The eight documented SQL scalar families, typed parameters/results, DML,
  joins, derived tables, aggregation, grouping/HAVING, DISTINCT, ordering,
  bounded spill, EXPLAIN, and EXPLAIN ANALYZE.
- Bounded raw nested/correlated query forms and durable direct or
  deepest-derived two-table JOIN views.

## Known limitations

- This is pre-V1 software. Public APIs, protocol details, catalog records, WAL,
  pages, and other durable formats may change incompatibly. River does not
  provide upgrade adapters for this alpha.
- The network service is intentionally restricted to authenticated TLS
  loopback use. Non-loopback deployment is not supported.
- The complete crash, recovery, isolation-history, fault-injection,
  bounded-growth, and long-running soak promotion matrices are not finished.
  The accepted focused recovery paths do not constitute a production
  durability guarantee.
- The end-to-end JDBC disconnect/rollback recovery promotion fixture is not
  green in this build. After a restored authenticated session, opening the
  probe statement can return `CONFLICT` before the disconnect rollback check
  begins (`M5TypeRecoveryBoundaryTest`). Treat disconnect-time transactional
  recovery through JDBC as unpromoted; close and reopen the connection after a
  server recovery boundary.
- Backup is quiescent/offline. Online backup, in-place upgrade, migration,
  repair automation, and production operational tooling are not complete.
- Computed/generalized correlation, nested parameters and durable nested
  views, broader cross-column or contextual CHECK expressions, direct JOIN
  ordering, multiple/nondeep JOIN blocks, and generalized JOIN/correlation
  combinations fail closed or remain unsupported.
- Replication, failover, online schema migration, and production observability
  are not included.
- JDK 25 is required. Packaging is source/Gradle based; no supported native
  installer or operating-system service package is supplied.

Unsupported SQL and boundary operations are expected to return a River status
or JDBC SQLSTATE rather than silently changing semantics. The normative
admitted surface is recorded in the
[SQL conformance profile](../compatibility/sql-conformance-profile.md), and
delivery status is recorded in the
[implementation ledger](implementation-status.md).

## Validation boundary

The release tag identifies the exact source used for validation. The SQL,
engine, backup, and remaining JDBC suites pass, as do reproducible archive
comparison, dependency verification, static design-debt policy, and repository
diff checks. The clean release verifier stops only at the documented
`M5TypeRecoveryBoundaryTest` failure above; that boundary is not represented as
accepted behavior.
