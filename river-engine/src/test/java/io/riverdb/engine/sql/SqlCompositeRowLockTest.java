package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TPC-C-shaped composite rows obey exact-lock ownership, handoff, and timeout semantics. */
final class SqlCompositeRowLockTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434f4d504f534954L, 0x45524f574c4f434bL);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void selectForUpdateThenUpdateReusesTheTransactionsCompositeRowLock(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root, "100ms");
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
          fixture.result));
      assertEquals(10, fixture.result.value());
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertDistrictValue(fixture, 11);
    } finally {
      fixture.close();
    }
  }

  @Test
  void blockedCompositeRowUpdateRunsAfterTheHolderCommits(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      lockDistrict(fixture);
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
            waitingResult);
      });
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertDistrictValue(fixture, 12);
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void serializableSelectForUpdateQueuesAtSourceWithoutAnUpgradeDeadlock(
      @TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK,
          fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
          fixture.result));
      assertEquals(StatusCode.OK,
          fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "SELECT d_next_o_id FROM district "
                + "WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
            waitingResult);
      });
      awaitParked(worker, waiting);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(11, waitingResult.value());
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
      assertDistrictValue(fixture, 12);
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void semanticSingletonWithANonpointOrderedAccessRemainsAScan(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE INDEX district_by_next ON district(d_next_o_id)", fixture.result));
      assertEquals(StatusCode.OK,
          fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 "
              + "ORDER BY d_next_o_id LIMIT 1 FOR UPDATE",
          cursor));
      assertEquals(StatusCode.OK, fixture.holder.nextScan(cursor, row));
      assertEquals(10, row.valueAt(0));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
    } finally {
      fixture.close();
    }
  }

  @Test
  void serializableOldestRowWorkersQueueAtTheOrderedSource(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE new_order (no_w_id BIGINT NOT NULL,no_d_id BIGINT NOT NULL,"
              + "no_o_id BIGINT NOT NULL,PRIMARY KEY(no_w_id,no_d_id,no_o_id))",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "INSERT INTO new_order VALUES (1,7,10),(1,7,11)", fixture.result));
      assertEquals(StatusCode.OK,
          fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      SqlScanCursor holderCursor = new SqlScanCursor();
      SqlScanRowResult holderRow = new SqlScanRowResult();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT no_o_id FROM new_order WHERE no_w_id=1 AND no_d_id=7 "
              + "ORDER BY no_o_id LIMIT 1 FOR UPDATE",
          holderCursor));
      assertEquals(StatusCode.OK, fixture.holder.nextScan(holderCursor, holderRow));
      assertEquals(10, holderRow.valueAt(0));
      assertEquals(StatusCode.OK,
          fixture.holder.closeScan(holderCursor, fixture.result));

      assertEquals(StatusCode.OK,
          fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlScanCursor waiterCursor = new SqlScanCursor();
      SqlScanRowResult waiterRow = new SqlScanRowResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        StatusCode status = fixture.waiter.beginScan(
            "SELECT no_o_id FROM new_order WHERE no_w_id=1 AND no_d_id=7 "
                + "ORDER BY no_o_id LIMIT 1 FOR UPDATE",
            waiterCursor);
        if (status.isOk()) status = fixture.waiter.nextScan(waiterCursor, waiterRow);
        return status;
      });
      awaitParked(worker, waiting);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "DELETE FROM new_order WHERE no_w_id=1 AND no_d_id=7 AND no_o_id=10",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(11, waiterRow.valueAt(0));
      assertEquals(StatusCode.OK,
          fixture.waiter.closeScan(waiterCursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("ROLLBACK", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void emptySerializableOldestRowScanProtectsOnlyItsPrefix(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE new_order (no_w_id BIGINT NOT NULL,no_d_id BIGINT NOT NULL,"
              + "no_o_id BIGINT NOT NULL,PRIMARY KEY(no_w_id,no_d_id,no_o_id))",
          fixture.result));
      assertEquals(StatusCode.OK,
          fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT no_o_id FROM new_order WHERE no_w_id=1 AND no_d_id=7 "
              + "ORDER BY no_o_id LIMIT 1 FOR UPDATE",
          cursor));
      assertEquals(StatusCode.CONFLICT, fixture.holder.nextScan(cursor, row));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));

      assertEquals(StatusCode.OK,
          fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "INSERT INTO new_order VALUES (1,8,10)", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));

      assertEquals(StatusCode.OK,
          fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      Future<StatusCode> matchingInsert = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "INSERT INTO new_order VALUES (1,7,10)", new SqlExecutionResult());
      });
      awaitParked(worker, matchingInsert);
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
      assertEquals(StatusCode.OK, matchingInsert.get(1, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void serializableDependentPointJoinLocksInnerKeysInCanonicalOrder(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE stock (s_w_id BIGINT NOT NULL,s_i_id BIGINT NOT NULL,"
              + "s_quantity BIGINT NOT NULL,PRIMARY KEY(s_w_id,s_i_id))",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE order_line (ol_w_id BIGINT NOT NULL,ol_d_id BIGINT NOT NULL,"
              + "ol_o_id BIGINT NOT NULL,ol_number BIGINT NOT NULL,ol_i_id BIGINT NOT NULL,"
              + "PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number))",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "INSERT INTO stock VALUES (1,1,5),(1,2,5)", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "INSERT INTO order_line VALUES (1,1,1,1,2),(1,1,2,1,1)",
          fixture.result));

      assertEquals(StatusCode.OK,
          fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE stock SET s_quantity=s_quantity+1 WHERE s_w_id=1 AND s_i_id=1",
          fixture.result));
      assertEquals(StatusCode.OK,
          fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult stockLevelResult = new SqlExecutionResult();
      Future<StatusCode> stockLevel = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
                + "INNER JOIN stock s ON s.s_w_id=ol.ol_w_id "
                + "AND s.s_i_id=ol.ol_i_id WHERE ol.ol_w_id=1 "
                + "AND ol.ol_d_id=1 AND ol.ol_o_id>=1 AND ol.ol_o_id<3 "
                + "AND s.s_quantity<10",
            stockLevelResult);
      });
      awaitParked(worker, stockLevel, stockLevelResult);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE stock SET s_quantity=s_quantity+1 WHERE s_w_id=1 AND s_i_id=2",
          fixture.result));
      assertFalse(stockLevel.isDone());
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
      assertEquals(StatusCode.OK, stockLevel.get(1, TimeUnit.SECONDS));
      assertEquals(2, stockLevelResult.value());
      assertEquals(StatusCode.OK,
          fixture.waiter.execute("COMMIT", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void unchangedIndexUpdateDoesNotUpgradePastAWaitingSerializableReader(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7",
            waitingResult);
      });
      awaitParked(worker, waiting);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(11, waitingResult.value());
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void partialCompositeScanAndPointUpdateUseOneRowQueue(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 FOR UPDATE", cursor));
      assertEquals(StatusCode.OK, fixture.holder.nextScan(cursor, row));
      assertEquals(10, row.valueAt(0));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "UPDATE district SET d_next_o_id=d_next_o_id+1 "
                + "WHERE d_w_id=1 AND d_id=7",
            waitingResult);
      });
      awaitParked(worker, waiting);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
      assertDistrictValue(fixture, 12);
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void orderedCompositeScanAndPointUpdateUseOneRowQueue(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 "
              + "ORDER BY d_next_o_id LIMIT 1 FOR UPDATE", cursor));
      assertEquals(StatusCode.OK, fixture.holder.nextScan(cursor, row));
      assertEquals(10, row.valueAt(0));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.waiter.execute(
            "UPDATE district SET d_next_o_id=d_next_o_id+1 "
                + "WHERE d_w_id=1 AND d_id=7",
            waitingResult);
      });
      awaitParked(worker, waiting);

      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
      assertDistrictValue(fixture, 12);
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void orderedForUpdateRechecksCurrentPredicateAndContinuesLimit(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE district (d_w_id BIGINT NOT NULL,d_id BIGINT NOT NULL,"
              + "d_next_o_id BIGINT NOT NULL,PRIMARY KEY(d_w_id,d_id))",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "INSERT INTO district VALUES (1,7,10),(1,8,20)", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_id,d_next_o_id FROM district WHERE d_next_o_id<30 "
              + "ORDER BY d_next_o_id LIMIT 1 FOR UPDATE", cursor));

      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "UPDATE district SET d_next_o_id=40 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlScanRowResult row = new SqlScanRowResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.holder.nextScan(cursor, row);
      });
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));

      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(8, row.valueAt(0));
      assertEquals(20, row.valueAt(1));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void orderedForUpdateSkipsDeletedCandidateAndContinuesLimit(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createTwoDistricts(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_id FROM district WHERE d_w_id=1 "
              + "ORDER BY d_next_o_id LIMIT 1 FOR UPDATE", cursor));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "DELETE FROM district WHERE d_w_id=1 AND d_id=7", fixture.result));

      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlScanRowResult row = new SqlScanRowResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.holder.nextScan(cursor, row);
      });
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));

      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(8, row.valueAt(0));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void partialCompositeScanSkipsCandidateMovedOutsideIndexBounds(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createTwoDistricts(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      SqlScanCursor cursor = new SqlScanCursor();
      assertEquals(StatusCode.OK, fixture.holder.beginScan(
          "SELECT d_id FROM district WHERE d_w_id=1 FOR UPDATE", cursor));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "UPDATE district SET d_w_id=2 WHERE d_w_id=1 AND d_id=7",
          fixture.result));

      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlScanRowResult row = new SqlScanRowResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.holder.nextScan(cursor, row);
      });
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));

      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(8, row.valueAt(0));
      assertEquals(StatusCode.OK, fixture.holder.closeScan(cursor, fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void deleteScanSkipsConcurrentDeletionAndContinues(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createTwoDistricts(fixture);
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "DELETE FROM district WHERE d_w_id=1 AND d_id=7", fixture.result));

      AtomicReference<Thread> worker = new AtomicReference<>();
      SqlExecutionResult deleteResult = new SqlExecutionResult();
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        return fixture.holder.execute(
            "DELETE FROM district WHERE d_w_id=1", deleteResult);
      });
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));

      assertEquals(StatusCode.OK, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(1, deleteResult.affectedRows());
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.CONFLICT, fixture.waiter.execute(
          "SELECT d_id FROM district WHERE d_w_id=1 AND d_id=8", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void blockedCompositeRowUpdateUsesTheConfiguredShortTimeout(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "20ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      createDistrict(fixture);
      lockDistrict(fixture);
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      SqlExecutionResult waitingResult = new SqlExecutionResult();
      AtomicLong elapsed = new AtomicLong();
      Future<StatusCode> waiting = executor.submit(() -> {
        long started = System.nanoTime();
        StatusCode status = fixture.waiter.execute(
            "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
            waitingResult);
        elapsed.set(System.nanoTime() - started);
        return status;
      });
      assertEquals(StatusCode.TIMEOUT, waiting.get(1, TimeUnit.SECONDS));
      assertTrue(elapsed.get() >= TimeUnit.MILLISECONDS.toNanos(5));
      assertTrue(elapsed.get() < TimeUnit.SECONDS.toNanos(1));
      assertTrue(waitingResult.transactionActive());
      assertEquals(StatusCode.OK, fixture.holder.execute("COMMIT", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("COMMIT", fixture.result));
      assertDistrictValue(fixture, 11);
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  @Test
  void updateOfNonForeignColumnsDoesNotWaitOnTheUnchangedParentKey(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "500ms");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE warehouse (w_id BIGINT PRIMARY KEY,w_ytd BIGINT NOT NULL)",
          fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "CREATE TABLE district (d_w_id BIGINT NOT NULL,d_id BIGINT NOT NULL,"
              + "d_next_o_id BIGINT NOT NULL,PRIMARY KEY(d_w_id,d_id),"
              + "FOREIGN KEY(d_w_id) REFERENCES warehouse(w_id))",
          fixture.result));
      assertEquals(StatusCode.OK,
          fixture.holder.execute("INSERT INTO warehouse VALUES (1,0)", fixture.result));
      assertEquals(StatusCode.OK,
          fixture.holder.execute("INSERT INTO district VALUES (1,7,10)", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute(
          "UPDATE warehouse SET w_ytd=w_ytd+1 WHERE w_id=1", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN", fixture.result));
      assertEquals(StatusCode.OK, fixture.waiter.execute(
          "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
          fixture.result));

      SqlExecutionResult updateResult = new SqlExecutionResult();
      Future<StatusCode> update = executor.submit(() -> fixture.waiter.execute(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=7",
          updateResult));
      assertEquals(StatusCode.OK, update.get(1, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, fixture.waiter.execute("ROLLBACK", fixture.result));
      assertEquals(StatusCode.OK, fixture.holder.execute("ROLLBACK", fixture.result));
    } finally {
      executor.shutdownNow();
      fixture.close();
    }
  }

  private static void createDistrict(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "CREATE TABLE district (d_w_id BIGINT NOT NULL,d_id BIGINT NOT NULL,"
            + "d_next_o_id BIGINT NOT NULL,PRIMARY KEY(d_w_id,d_id))",
        fixture.result));
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "INSERT INTO district VALUES (1,7,10)", fixture.result));
  }

  private static void createTwoDistricts(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "CREATE TABLE district (d_w_id BIGINT NOT NULL,d_id BIGINT NOT NULL,"
            + "d_next_o_id BIGINT NOT NULL,PRIMARY KEY(d_w_id,d_id))",
        fixture.result));
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "INSERT INTO district VALUES (1,7,10),(1,8,20)", fixture.result));
  }

  private static void lockDistrict(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.holder.execute("BEGIN", fixture.result));
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7 FOR UPDATE",
        fixture.result));
  }

  private static void assertDistrictValue(Fixture fixture, long expected) {
    assertEquals(StatusCode.OK, fixture.holder.execute(
        "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=7", fixture.result));
    assertEquals(expected, fixture.result.value());
  }

  private static void awaitParked(AtomicReference<Thread> worker, Future<?> future) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    Thread.State state = Thread.State.NEW;
    while (!future.isDone() && System.nanoTime() < deadline) {
      Thread thread = worker.get();
      if (thread != null) {
        state = thread.getState();
        if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) break;
      }
      Thread.onSpinWait();
    }
    assertFalse(future.isDone(), () -> "future completed with " + completedValue(future));
    assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
  }

  private static void awaitParked(
      AtomicReference<Thread> worker,
      Future<?> future,
      SqlExecutionResult result) {
    try {
      awaitParked(worker, future);
    } catch (AssertionError failure) {
      throw new AssertionError(
          failure.getMessage() + " value=" + result.value(), failure);
    }
  }

  private static Object completedValue(Future<?> future) {
    try {
      return future.get();
    } catch (Exception failure) {
      return failure;
    }
  }

  private static Fixture open(Path root, String timeout) throws Exception {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.tx.lock-wait-timeout=" + timeout + "\n",
        StandardCharsets.UTF_8);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    SqlSession holder = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    return new Fixture(opened.database(), holder, sessions.session());
  }

  private static final class Fixture {
    final RelationalDatabase database;
    final SqlSession holder;
    final SqlSession waiter;
    final SqlExecutionResult result = new SqlExecutionResult();

    Fixture(RelationalDatabase database, SqlSession holder, SqlSession waiter) {
      this.database = database;
      this.holder = holder;
      this.waiter = waiter;
    }

    void close() {
      holder.close();
      waiter.close();
      database.close();
    }
  }
}
