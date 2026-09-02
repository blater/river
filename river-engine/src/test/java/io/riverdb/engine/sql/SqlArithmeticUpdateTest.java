package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlArithmeticUpdateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4152495448555044L, 0x4154453030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void updatesFromOriginalRowsAndRollsBackOverflow(@TempDir Path root) {
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
                + "(id BIGINT PRIMARY KEY, balance BIGINT, adjustment BIGINT, region BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX accounts_balance ON accounts(balance)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts VALUES "
                + "(1, 100, 100, 7), "
                + "(2, 200, 9223372036854775807, 7), "
                + "(3, 300, 10, 7), "
                + "(4, 400, -9223372036854775808, 8)",
            result));

    assertEquals(
        StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        session.execute(
            "UPDATE accounts SET balance=adjustment+25 WHERE region=7",
            result));
    assertValue(session, result, "SELECT balance FROM accounts WHERE id=1", 100);
    assertValue(session, result, "SELECT id FROM accounts WHERE balance=100", 1);
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM accounts WHERE balance=125", result));
    assertEquals(
        StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        session.execute("UPDATE accounts SET balance=adjustment-1 WHERE id=4", result));
    assertValue(
        session,
        result,
        "SELECT balance FROM accounts WHERE id=4",
        400);

    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=balance+25 WHERE id=1", result));
    assertEquals(1, result.affectedRows());
    assertValue(session, result, "SELECT id FROM accounts WHERE balance=125", 1);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE accounts SET balance=adjustment+5, adjustment=balance-5 WHERE id=3",
            result));
    assertValue(session, result, "SELECT balance FROM accounts WHERE id=3", 15);
    assertValue(session, result, "SELECT adjustment FROM accounts WHERE id=3", 295);
    assertValue(session, result, "SELECT id FROM accounts WHERE balance=15", 3);

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertValue(session, result, "SELECT balance FROM accounts WHERE id=1", 125);
    assertValue(session, result, "SELECT adjustment FROM accounts WHERE id=3", 295);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertValue(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long expected) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(expected, result.value());
  }
}
