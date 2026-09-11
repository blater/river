---
id: tic-c8d1
status: in_progress
type: epic
priority: 1
created: 2026-09-11
owner: root
---
# Bring every scored River source file below 90

Scope: all repository source files recognized by the installed slopwatch scorer,
including test sources. Baseline: 2,522 files, 82 at or above 90 (14 test files).
Evidence: `/private/tmp/river-score-20260911/baseline.json`. Slopwatch is the watch
entry point for the same scoring engine as slopmark; use `-follow=false` for a
finite scan. Do not change the scorer, its configuration, exclusions or thresholds.
Unscored languages are not implicitly certified by this result.

## Delivery

Each child names one original file and one independently reviewed change. New
files must also remain below 90. Simplify actual responsibilities; preserve one
owner for transaction, lifetime, validation and cleanup policy. Do not create
interfaces, wrapper chains or duplicate state just to reduce a score. Root owns
the architecture and integration; Luna/high agents code and Sol/high reviews.
Review findings across files before accepting local fixes.

Use disjoint feature worktrees, serial host builds/workloads and Gradle
`--no-daemon`. Focused tests prove each affected boundary. Use a short JVM
sample/all harness run per ticket with a rolling adjacent control, fixed seed and
configuration; lengthen/interleave only for a surprising result. For test-only
changes, record focused test runtime and a short harness smoke. This campaign uses
light per-ticket checks as requested, with full integration checks and standalone
smokes at meaningful subsystem checkpoints and once at completion, not a full
native/build matrix per file. Record each score, review, test and performance
result in its child ticket. Keep raw artifacts outside Git.

Final acceptance: a complete unchanged full scan has zero files scoring 90 or
above, all child changes reviewed and validated, integration tests and the actual
installed-server path pass, changes merged/tagged/pushed, and local artifacts
refreshed. New findings created by extraction stay in their originating ticket.

## Measurement baseline

JVM source `75ae39d6`, GraalVM 25.0.4, `-Xmx1g`; baseline samples 240.07 and
294.15 committed TPS, both passed with zero failed/unknown outcomes, successful
invariants and graceful cleanup. Evidence: `baseline-samples.json` under the
artifact directory above. These short samples are diagnostics, not a speed claim.

Per-ticket command: `benchmark run river tpcc sample all
--river-executable=JVM_LAUNCHER --river-version=TICKET-COMMIT-VARIATION
--warmup=5s --duration=10s --workers=4 --warehouses=1 --seed=42 --max-retries=20`.
Record startup/stop elapsed time as well for lifecycle changes. Use the prior
accepted build as the rolling control; keep candidate/control measurements apart
from compilation, tests and broad scoring scans. Benchmark SQL, durability,
isolation and retry policy are unchanged.

## File tickets

| Ticket | Original file | Baseline |
| --- | --- | ---: |
| [tic-b251](tic-b251.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonIdentity.java` | 931.632 |
| [tic-7c86](tic-7c86.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonCredentials.java` | 385.589 |
| [tic-013d](tic-013d.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonRuntimeRecords.java` | 384.232 |
| [tic-bbfc](tic-bbfc.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonStop.java` | 341.692 |
| [tic-d369](tic-d369.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlSessionExecutionCoordinator.java` | 285.853 |
| [tic-e67d](tic-e67d.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverdCommandParser.java` | 255.590 |
| [tic-c886](tic-c886.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonInstance.java` | 221.321 |
| [tic-98f2](tic-98f2.md) | `river-protocol/src/main/java/io/riverdb/protocol/ProtocolResponseEncoder.java` | 216.949 |
| [tic-396a](tic-396a.md) | `river-engine/src/test/java/io/riverdb/engine/table/IndexedRelationalWalHarnessTest.java` | 212.312 |
| [tic-c726](tic-c726.md) | `river-engine/src/test/java/io/riverdb/engine/sql/SqlJoinBlockPipelineTest.java` | 207.510 |
| [tic-e1f7](tic-e1f7.md) | `river-client/src/main/java/io/riverdb/client/RiverClientConnection.java` | 195.799 |
| [tic-b169](tic-b169.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonPaths.java` | 192.224 |
| [tic-e5af](tic-e5af.md) | `river-engine/src/main/java/io/riverdb/engine/relational/CatalogTableDecoder.java` | 190.238 |
| [tic-6d7c](tic-6d7c.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonTargets.java` | 181.796 |
| [tic-c55f](tic-c55f.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverdForeground.java` | 174.887 |
| [tic-3a4f](tic-3a4f.md) | `river-jdbc/src/test/java/io/riverdb/jdbc/RiverDriverTest.java` | 166.674 |
| [tic-a3a5](tic-a3a5.md) | `river-engine/src/main/java/io/riverdb/engine/EmbeddedDatabase.java` | 164.297 |
| [tic-7339](tic-7339.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockAggregateBinder.java` | 161.174 |
| [tic-6c32](tic-6c32.md) | `river-tx/src/main/java/io/riverdb/tx/TransactionManager.java` | 160.555 |
| [tic-6a7f](tic-6a7f.md) | `river-wal/src/main/java/io/riverdb/wal/local/LocalWal.java` | 159.675 |
| [tic-9e2f](tic-9e2f.md) | `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStore.java` | 157.040 |
| [tic-68d6](tic-68d6.md) | `river-client/src/main/java/io/riverdb/client/RiverClientConfiguration.java` | 154.401 |
| [tic-97c6](tic-97c6.md) | `river-client/src/test/java/io/riverdb/client/RiverClientConnectionTest.java` | 141.678 |
| [tic-ed05](tic-ed05.md) | `river-sql/src/test/java/io/riverdb/sql/SqlParserTest.java` | 140.493 |
| [tic-b089](tic-b089.md) | `river-engine/src/main/java/io/riverdb/engine/table/IndexedHybridLogicalSizing.java` | 139.662 |
| [tic-25cf](tic-25cf.md) | `river-storage/src/main/java/io/riverdb/storage/btree/TupleBTreeLeafSplit.java` | 139.126 |
| [tic-e334](tic-e334.md) | `river-platform/src/main/java/io/riverdb/platform/riverd/ntfs/WindowsRiverDirectory.java` | 139.081 |
| [tic-55e0](tic-55e0.md) | `river-platform/src/main/java/io/riverdb/platform/riverd/linux/LinuxRiverDirectory.java` | 137.831 |
| [tic-0788](tic-0788.md) | `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccMetrics.java` | 135.044 |
| [tic-70e3](tic-70e3.md) | `river-protocol/src/main/java/io/riverdb/protocol/ProtocolResponsePayloadDecoder.java` | 134.340 |
| [tic-3fc3](tic-3fc3.md) | `river-tx/src/test/java/io/riverdb/tx/TransactionManagerTest.java` | 132.029 |
| [tic-5049](tic-5049.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlBoundBlockPlans.java` | 131.474 |
| [tic-f2bf](tic-f2bf.md) | `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccPromotionGates.java` | 130.145 |
| [tic-055b](tic-055b.md) | `river-sql/src/main/java/io/riverdb/sql/SqlCommand.java` | 128.210 |
| [tic-69c5](tic-69c5.md) | `river-tx/src/main/java/io/riverdb/tx/LockDeadlockDiagnosticsSnapshot.java` | 126.216 |
| [tic-0b25](tic-0b25.md) | `river-engine/src/test/java/io/riverdb/engine/schema/catalog/CatalogLifecycleRemediationTest.java` | 125.095 |
| [tic-04aa](tic-04aa.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonTarget.java` | 122.981 |
| [tic-90d4](tic-90d4.md) | `river-engine/src/test/java/io/riverdb/engine/testsupport/fault/FaultingDurableDirectory.java` | 120.929 |
| [tic-e12b](tic-e12b.md) | `river-platform/src/main/java/io/riverdb/platform/riverd/apfs/DarwinFileBridge.java` | 120.888 |
| [tic-6c82](tic-6c82.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlPointCommandExecutor.java` | 119.659 |
| [tic-6dc4](tic-6dc4.md) | `river-wal/src/test/java/io/riverdb/wal/local/LocalWalTest.java` | 118.104 |
| [tic-cf2a](tic-cf2a.md) | `river-storage/src/test/java/io/riverdb/storage/btree/TupleBTreeTestPageProvider.java` | 118.054 |
| [tic-4944](tic-4944.md) | `river-wal/src/main/java/io/riverdb/wal/local/LocalWalQuorumAdmission.java` | 117.430 |
| [tic-cc41](tic-cc41.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlUniversalDescriptorIndexLeaf.java` | 116.364 |
| [tic-effc](tic-effc.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlRowExpressionEvaluator.java` | 113.689 |
| [tic-3a3d](tic-3a3d.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcResultSet.java` | 113.508 |
| [tic-4fd6](tic-4fd6.md) | `river-server/src/main/java/io/riverdb/server/LoopbackRiverServer.java` | 113.333 |
| [tic-2de1](tic-2de1.md) | `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonIdentityRecords.java` | 113.231 |
| [tic-3142](tic-3142.md) | `river-tx/src/test/java/io/riverdb/tx/LockTupleIntervalTableTest.java` | 111.571 |
| [tic-1ca8](tic-1ca8.md) | `river-protocol/src/main/java/io/riverdb/protocol/ProtocolSqlRequestEncoder.java` | 110.622 |
| [tic-7ca1](tic-7ca1.md) | `river-engine-api/src/main/java/io/riverdb/engine/api/TransactionValueArena.java` | 106.723 |
| [tic-348c](tic-348c.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlProjectionBinder.java` | 105.642 |
| [tic-ccb9](tic-ccb9.md) | `river-tx/src/main/java/io/riverdb/tx/LockDeadlockDiagnostics.java` | 105.471 |
| [tic-35dd](tic-35dd.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcConnection.java` | 103.790 |
| [tic-c965](tic-c965.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlAggregateAccumulatorSet.java` | 102.407 |
| [tic-5f87](tic-5f87.md) | `river-platform/src/main/java/io/riverdb/platform/riverd/ntfs/WindowsFileBridge.java` | 102.192 |
| [tic-a459](tic-a459.md) | `river-tx/src/main/java/io/riverdb/tx/LockExactGrantDecision.java` | 101.800 |
| [tic-3c7d](tic-3c7d.md) | `river-engine/src/main/java/io/riverdb/engine/relational/RelationalSession.java` | 101.348 |
| [tic-e62d](tic-e62d.md) | `river-engine/src/test/java/io/riverdb/engine/runtime/DatabaseResourceGovernorTest.java` | 101.255 |
| [tic-91e1](tic-91e1.md) | `river-engine/src/main/java/io/riverdb/engine/table/IndexedGroupCommitTelemetry.java` | 100.832 |
| [tic-0420](tic-0420.md) | `river-base/src/main/java/io/riverdb/base/type/ExactDecimal.java` | 99.212 |
| [tic-229c](tic-229c.md) | `river-backup/src/main/java/io/riverdb/backup/OfflineBackupCatalog.java` | 98.385 |
| [tic-cecc](tic-cecc.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverPrimaryKeyResultSet.java` | 96.997 |
| [tic-814d](tic-814d.md) | `river-sql/src/main/java/io/riverdb/sql/SqlDerivedReferenceValidator.java` | 96.971 |
| [tic-f805](tic-f805.md) | `river-sql/src/main/java/io/riverdb/sql/SqlBooleanWhereParser.java` | 95.979 |
| [tic-f85e](tic-f85e.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverIndexInfoResultSet.java` | 95.622 |
| [tic-650c](tic-650c.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverCatalogResultSet.java` | 94.624 |
| [tic-8b99](tic-8b99.md) | `river-engine/src/main/java/io/riverdb/engine/relational/TableDefinitionColumnView.java` | 94.471 |
| [tic-5b20](tic-5b20.md) | `river-platform/src/main/java/io/riverdb/platform/riverd/linux/LinuxFileBridge.java` | 94.471 |
| [tic-03aa](tic-03aa.md) | `river-sql/src/main/java/io/riverdb/sql/SqlParser.java` | 94.379 |
| [tic-8c5e](tic-8c5e.md) | `river-bench/src/main/java/io/riverdb/bench/harness/BenchmarkSchemaValidator.java` | 94.224 |
| [tic-99e4](tic-99e4.md) | `river-engine/src/main/java/io/riverdb/engine/sql/SqlBooleanScalarBinder.java` | 93.915 |
| [tic-0cd4](tic-0cd4.md) | `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcStatement.java` | 93.080 |
| [tic-2792](tic-2792.md) | `river-engine/src/main/java/io/riverdb/engine/TransactionProgramSteps.java` | 92.804 |
| [tic-95d9](tic-95d9.md) | `river-engine/src/test/java/io/riverdb/engine/checkpoint/CheckpointControlStoreTest.java` | 92.773 |
| [tic-3b68](tic-3b68.md) | `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStoreFactory.java` | 91.896 |
| [tic-01f7](tic-01f7.md) | `river-engine/src/main/java/io/riverdb/engine/relational/CatalogIndexCodec.java` | 91.625 |
| [tic-7c5c](tic-7c5c.md) | `river-engine/src/test/java/io/riverdb/engine/schema/catalog/CatalogSchemaPayloadCodecTest.java` | 91.376 |
| [tic-486d](tic-486d.md) | `river-platform/src/main/java/io/riverdb/platform/file/nio/NioDurableDirectory.java` | 91.131 |
| [tic-a39e](tic-a39e.md) | `river-sql/src/main/java/io/riverdb/sql/SqlQuery.java` | 90.981 |
| [tic-428c](tic-428c.md) | `river-tx/src/main/java/io/riverdb/tx/LockExactTable.java` | 90.204 |
| [tic-50be](tic-50be.md) | `river-engine/src/main/java/io/riverdb/engine/relational/RelationalSchemaLifecycle.java` | 90.114 |

## Progress

First three tickets (`tic-3a4f`, `tic-d369`, `tic-98f2`) are closed at pushed
checkpoint `perf-checkpoint-20260911-score-first3`. The unchanged full scan
analyzes 2,533 Java files; 79 remain at or above 90. All new files in the accepted
slices are below 90. Individual ticket pages and performance checkpoints retain
validation evidence.

`tic-e67d` is closed at pushed checkpoint `perf-checkpoint-20260911-score-parser`.
Four original files are delivered; the integrated scan includes 2,536 Java files
and 78 remain at or above 90.


Eight original files are delivered at pushed checkpoint
`perf-checkpoint-20260911-score-first8`: credentials, identity, Java
client lifetime and exact-lock admission join the first four. The unchanged full
scan covers 2,556 Java files; **74 remain at or above 90**. All files added by
these tickets are below 90. The clean integration check passed in 4m23s;
the standalone smoke and integrated JVM sample passed. All eight tickets are closed.


Protocol request encoding (`tic-1ca8`), schema admission (`tic-50be`) and catalog
index decoding (`tic-01f7`) bring the accepted total to 11. Combined affected
checks passed; unchanged scan: 2,558 Java files, 71 remaining at or above 90.
Checkpoint: `perf-checkpoint-20260911-score-first11`.


Session grammar (`tic-03aa`) and runtime records (`tic-013d`) bring the accepted
total to 13. Combined affected checks passed; unchanged scan: 2,566 Java files,
69 remaining at or above 90. Checkpoint: `perf-checkpoint-20260911-score-first13`.


Relational WAL tests (`tic-396a`) and catalog decoding (`tic-e5af`) bring the
accepted total to 15. Clean full checks passed in 4m21s; unchanged scan: 2,575
files, 67 remaining at or above 90 and no new offenders. Per-ticket light
workloads and the catalog's longer adjacent control passed.
Checkpoint: `perf-checkpoint-20260911-score-first15`.


Aggregate binding, stop lifecycle, join tests and client configuration bring the
accepted total to 19. Affected checks and the rebuilt standalone CLI's real
start/authenticate/SQL/stop path passed. Unchanged scan: 2,588 files, 63 remaining
at or above 90, no new offenders. Checkpoint:
`perf-checkpoint-20260911-score-first19`.


Six further reviewed and validated tickets bring the accepted total to 25.
Clean full integration checks passed; the unchanged scan now has 57 remaining
files at or above 90 out of 2,593, with no new offenders. Checkpoint:
`perf-checkpoint-20260911-score-first25`; per-ticket validation and
`docs/performance-checkpoints.md` retain evidence.
