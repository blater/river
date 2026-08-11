package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlNotNullConstraintTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e4f544e554c4c43L, 0x4f4e53545241494eL);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void enforcesPersistedNotNullConstraintsAcrossStatementsAndRestart(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, tenant BIGINT NOT NULL, "
                + "balance BIGINT NOT NULL, note BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX accounts_tenant ON accounts(tenant)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (1, 10, 100, NULL)", result));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "INSERT INTO accounts VALUES "
                + "(2, 20, 200, NULL), (3, 30, NULL, 300)",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT tenant FROM accounts WHERE id=2", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (2, 20, 200, 7)", result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("UPDATE accounts SET balance=NULL WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", result));
    assertEquals(200, result.value());
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));

    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET note=NULL WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT note FROM accounts WHERE id=2", result));
    assertTrue(result.isNull(0));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT tenant FROM accounts WHERE id=2", result));
    assertEquals(20, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, tenant FROM accounts WHERE tenant=20", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("UPDATE accounts SET balance=NULL WHERE tenant=20", result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("INSERT INTO accounts VALUES (3, 30, NULL, 300)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT tenant FROM accounts WHERE id=2", result));
    assertEquals(20, result.value());

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
