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

final class SqlCompositePrimaryKeyTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434f4d505052494dL, 0x4152594b45593031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void mutatesMixedTypeCompositePrimaryKeyAndReopens(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE typed_keys (tenant BIGINT,code VARCHAR(8),amount DECIMAL(8,2),"
            + "day DATE,observed TIMESTAMP(6),flag BOOLEAN,value BIGINT,"
            + "PRIMARY KEY (tenant,code,amount,day,observed,flag))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO typed_keys VALUES "
            + "(7,'alpha',12.34,DATE '2024-03-01',TIMESTAMP '2024-03-01 10:11:12.123456',TRUE,99)",
        result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO typed_keys VALUES "
            + "(7,'alpha',12.34,DATE '2024-03-01',TIMESTAMP '2024-03-01 10:11:12.123456',TRUE,100)",
        result));
    assertValue(session, 99, predicate("alpha"));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE typed_keys SET code='beta',value=101 WHERE " + predicate("alpha"), result));
    assertEquals(StatusCode.CONFLICT, session.execute(
        "SELECT value FROM typed_keys WHERE " + predicate("alpha"), result));
    assertValue(session, 101, predicate("beta"));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM typed_keys WHERE " + predicate("beta"), result));
    assertEquals(StatusCode.CONFLICT, session.execute(
        "SELECT value FROM typed_keys WHERE " + predicate("beta"), result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO typed_keys VALUES "
            + "(8,'persist',98.76,DATE '2025-04-02',TIMESTAMP '2025-04-02 01:02:03.000004',FALSE,202)",
        result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertValue(session, 202,
        "tenant=8 AND code='persist' AND amount=98.76 AND day=DATE '2025-04-02' "
            + "AND observed=TIMESTAMP '2025-04-02 01:02:03.000004' AND flag=FALSE");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static String predicate(String code) {
    return "flag=TRUE AND observed=TIMESTAMP '2024-03-01 10:11:12.123456' "
        + "AND tenant=7 AND day=DATE '2024-03-01' AND code='" + code + "' AND amount=12.34";
  }

  private static void assertValue(SqlSession session, long expected, String predicate) {
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("SELECT value FROM typed_keys WHERE " + predicate, result));
    assertEquals(expected, result.value());
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
