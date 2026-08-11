package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverSequenceTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x53455155454e4345L, 0x454e47494e453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void allocatesDurableNonRollbackValuesAndTransactionalDefinitions(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE invoice_ids START WITH 100 INCREMENT BY 5", result));
    assertValue(100, session, "SELECT NEXT VALUE FOR invoice_ids", result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertValue(105, session, "SELECT NEXT VALUE FOR invoice_ids", result);
    assertEquals(true, result.transactionActive());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertValue(110, session, "SELECT NEXT VALUE FOR invoice_ids", result);

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE rolled_back START WITH 7", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT NEXT VALUE FOR rolled_back", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_sequence", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE saved START WITH -4 INCREMENT BY -2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT before_sequence", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT NEXT VALUE FOR saved", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("DROP SEQUENCE invoice_ids", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertValue(115, session, "SELECT NEXT VALUE FOR invoice_ids", result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE SEQUENCE descending START WITH -10 INCREMENT BY -3", result));
    assertValue(-10, session, "SELECT NEXT VALUE FOR descending", result);
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE maximum START WITH 9223372036854775807", result));
    assertValue(Long.MAX_VALUE, session, "SELECT NEXT VALUE FOR maximum", result);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        session.execute("SELECT NEXT VALUE FOR maximum", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE SEQUENCE minimum START WITH -9223372036854775808 INCREMENT BY -1",
            result));
    assertValue(Long.MIN_VALUE, session, "SELECT NEXT VALUE FOR minimum", result);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        session.execute("SELECT NEXT VALUE FOR minimum", result));

    assertEquals(StatusCode.OK, session.execute("CREATE TABLE occupied", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("CREATE SEQUENCE occupied", result));
    assertEquals(StatusCode.OK, session.execute("CREATE SEQUENCE reserved", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("CREATE TABLE reserved", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertValue(420, session, "SELECT NEXT VALUE FOR invoice_ids", result);
    assertValue(-202, session, "SELECT NEXT VALUE FOR descending", result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("DROP SEQUENCE invoice_ids", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT NEXT VALUE FOR invoice_ids", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE invoice_ids START WITH 500", result));
    assertValue(500, session, "SELECT NEXT VALUE FOR invoice_ids", result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void ordersConcurrentAllocationsWithoutDuplicates(@TempDir Path root) throws InterruptedException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult firstResult = new SessionOpenResult();
    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(firstResult));
    assertEquals(StatusCode.OK, database.createSession(secondResult));
    RiverSession first = firstResult.session();
    RiverSession second = secondResult.session();
    assertEquals(
        StatusCode.OK,
        first.execute("CREATE SEQUENCE concurrent_ids", new CommandResult()));

    long[][] allocated = new long[2][32];
    long[][] committedAt = new long[2][32];
    AtomicReference<StatusCode> failure = new AtomicReference<>(StatusCode.OK);
    CountDownLatch start = new CountDownLatch(1);
    Thread firstThread = new Thread(
        () -> allocate(first, allocated[0], committedAt[0], start, failure));
    Thread secondThread = new Thread(
        () -> allocate(second, allocated[1], committedAt[1], start, failure));
    firstThread.start();
    secondThread.start();
    start.countDown();
    firstThread.join();
    secondThread.join();
    assertEquals(StatusCode.OK, failure.get());
    boolean[] seen = new boolean[65];
    for (long[] values : allocated) {
      for (long value : values) {
        assertEquals(false, seen[(int) value]);
        seen[(int) value] = true;
      }
    }
    for (int value = 1; value <= 64; value++) {
      assertEquals(true, seen[value]);
    }
    long reservationCommit = committedAt[0][0];
    assertEquals(true, reservationCommit > 0);
    for (long[] commits : committedAt) {
      for (long commit : commits) {
        assertEquals(reservationCommit, commit);
      }
    }
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void allocate(
      RiverSession session,
      long[] destination,
      long[] committedAt,
      CountDownLatch start,
      AtomicReference<StatusCode> failure) {
    try {
      start.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(StatusCode.OK, StatusCode.CANCELLED);
      return;
    }
    CommandResult result = new CommandResult();
    for (int index = 0; index < destination.length; index++) {
      StatusCode status = session.execute("SELECT NEXT VALUE FOR concurrent_ids", result);
      if (!status.isOk()) {
        failure.compareAndSet(StatusCode.OK, status);
        return;
      }
      destination[index] = result.valueAt(0);
      committedAt[index] = result.commitSequence();
    }
  }

  private static long assertValue(
      long expected,
      RiverSession session,
      String sql,
      CommandResult result) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(true, result.rowAvailable());
    assertEquals(1, result.columnCount());
    assertEquals(expected, result.valueAt(0));
    return result.commitSequence();
  }
}
