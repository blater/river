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

final class EmbeddedRiverDropTableTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x44524f505441424cL, 0x45454e47494e4531L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void dropsRowsIndexesAndCatalogThenReusesNames(@TempDir Path root) {
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
            "CREATE TABLE items "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7), category BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO items VALUES (1, 'alpha', 7), (2, 'beta', 7)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX items_code ON items(code)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX items_category ON items(category)", result));

    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(secondResult));
    RiverSession second = secondResult.session();
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(StatusCode.RETRY, session.execute("DROP TABLE items", result));
    assertEquals(StatusCode.OK, second.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, second.close());

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE items", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO items VALUES (3, 'gamma', 8)", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO items VALUES (3, 'alpha', 8)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_drop", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE items", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT before_drop", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO items VALUES (3, 'alpha', 8)", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE items", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.CONFLICT, session.execute("DROP TABLE items", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO items VALUES (3, 'gamma', 8)", result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE items "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7), category BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX items_code ON items(code)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX items_category ON items(category)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO items VALUES (3, 'gamma', 8)", result));
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
        StatusCode.CONFLICT,
        session.execute("INSERT INTO items VALUES (4, 'gamma', 9)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
