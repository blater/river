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

final class EmbeddedRiverIdentityTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4944454e54495459L, 0x454e47494e453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void generatesNonRollbackKeysAndRestartsBeyondDurableReservation(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE rolled_identity "
                + "(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payload BIGINT)",
            result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO rolled_identity(payload) VALUES (1)", result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                + "payload BIGINT NOT NULL)",
            result));
    assertGenerated(1, "INSERT INTO events(payload) VALUES (10)", session, result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertGenerated(2, "INSERT INTO events(payload) VALUES (20)", session, result);
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertGenerated(3, "INSERT INTO events(payload) VALUES (30)", session, result);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("INSERT INTO events VALUES (9, 90)", result));
    assertGenerated(4, "INSERT INTO events VALUES (DEFAULT, 40)", session, result);
    assertGenerated(
        5,
        2,
        "INSERT INTO events(payload) VALUES (50), (60)",
        session,
        result);
    assertRow(10, "SELECT payload FROM events WHERE id=1", session, result);
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT payload FROM events WHERE id=2", result));
    assertRow(30, "SELECT payload FROM events WHERE id=3", session, result);
    assertRow(40, "SELECT payload FROM events WHERE id=4", session, result);
    assertRow(50, "SELECT payload FROM events WHERE id=5", session, result);
    assertRow(60, "SELECT payload FROM events WHERE id=6", session, result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE events", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertGenerated(7, "INSERT INTO events(payload) VALUES (70)", session, result);

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertGenerated(65, "INSERT INTO events(payload) VALUES (650)", session, result);
    assertEquals(StatusCode.OK, session.execute("DROP TABLE events", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payload BIGINT)",
            result));
    assertGenerated(1, "INSERT INTO events(payload) VALUES (700)", session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertGenerated(
      long expected,
      String sql,
      RiverSession session,
      CommandResult result) {
    assertGenerated(expected, 1, sql, session, result);
  }

  private static void assertGenerated(
      long expected,
      int affectedRows,
      String sql,
      RiverSession session,
      CommandResult result) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(affectedRows, result.affectedRows());
    assertEquals(expected, result.key());
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
