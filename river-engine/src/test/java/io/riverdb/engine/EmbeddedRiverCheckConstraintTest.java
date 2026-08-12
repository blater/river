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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverCheckConstraintTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434845434b434f4eL, 0x53545241494e5431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void enforcesChecksOnInsertUpdateRollbackAndRestart(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT CHECK (id > 0) PRIMARY KEY, "
                + "balance BIGINT CHECK (balance >= 0), "
                + "optional BIGINT CHECK (optional < 10))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (1, 100, NULL)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (-1, 100, 1)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (2, -1, 1)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (2, 20, 10)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (2, 20, 9)", result));

    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("UPDATE accounts SET balance=-5 WHERE id=1", result));
    assertRow(100, "SELECT balance FROM accounts WHERE id=1", session, result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("UPDATE accounts SET optional=11 WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=25 WHERE id=2", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertRow(25, "SELECT balance FROM accounts WHERE id=2", session, result);

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (3, -3, NULL)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (3, 30, NULL)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRow(
      long expected,
      String sql,
      RiverSession session,
      CommandResult result) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(true, result.rowAvailable());
    assertEquals(expected, result.valueAt(0));
  }
}
