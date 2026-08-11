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

final class EmbeddedRiverUniqueConstraintTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x554e49515545434fL, 0x4e53545241494e54L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void enforcesUniqueColumnsAcrossStatementsTransactionsAndRestart(@TempDir Path root) {
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
            "CREATE TABLE contacts "
                + "(id BIGINT PRIMARY KEY, email BIGINT UNIQUE, optional BIGINT UNIQUE)",
            result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX _river_unique_1_1 ON contacts", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO contacts VALUES (1, 100, NULL)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO contacts VALUES (2, 200, NULL)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO contacts VALUES (3, 100, 30)", result));
    assertMissing(3, session, result);

    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "INSERT INTO contacts VALUES (3, 300, 30), (4, 400, 30)", result));
    assertMissing(3, session, result);
    assertMissing(4, session, result);
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO contacts VALUES (3, 300, 30)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("UPDATE contacts SET email=200 WHERE id=3", result));
    assertValue(300, "SELECT email FROM contacts WHERE id=3", session, result);
    assertValue(2, "SELECT id FROM contacts WHERE email=200", session, result);

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE rolled_back "
                + "(id BIGINT PRIMARY KEY, code BIGINT UNIQUE)",
            result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM rolled_back WHERE id=1", result));

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
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO contacts VALUES (4, 300, 40)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO contacts VALUES (4, 400, 40)", result));
    assertValue(4, "SELECT id FROM contacts WHERE email=400", session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertMissing(
      long key,
      RiverSession session,
      CommandResult result) {
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM contacts WHERE id=" + key, result));
  }

  private static void assertValue(
      long expected,
      String sql,
      RiverSession session,
      CommandResult result) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(true, result.rowAvailable());
    assertEquals(expected, result.valueAt(0));
  }
}
