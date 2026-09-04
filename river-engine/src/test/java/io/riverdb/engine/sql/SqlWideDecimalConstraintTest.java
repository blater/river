package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlWideDecimalConstraintTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5749444544454349L, 0x4d414c434f4e5354L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String DEFAULT = "1234.567890123456789012";

  @Test
  void decimal22DefaultAndCheckSurviveCheckpointAndReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE wide_constraints (id BIGINT PRIMARY KEY,"
            + "amount DECIMAL(22,18) DEFAULT " + DEFAULT
            + " CHECK(amount>=0.000000000000000001))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_constraints (id) VALUES (1)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_constraints VALUES (2,DEFAULT)", result));
    assertEquals(StatusCode.CHECK_VIOLATION, session.execute(
        "INSERT INTO wide_constraints VALUES (3,-0.000000000000000001)", result));
    assertEquals(StatusCode.CHECK_VIOLATION, session.execute(
        "UPDATE wide_constraints SET amount=-1.000000000000000000 WHERE id=1", result));
    assertAmount(session, result, 1, DEFAULT);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertAmount(session, result, 1, DEFAULT);
    assertEquals(StatusCode.CHECK_VIOLATION, session.execute(
        "INSERT INTO wide_constraints VALUES (4,-9999.999999999999999999)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_constraints (id) VALUES (5)", result));
    assertAmount(session, result, 5, DEFAULT);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertAmount(
      SqlSession session, SqlExecutionResult result, long id, String expected) {
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM wide_constraints WHERE id=" + id, result));
    BigInteger unscaled = new BigDecimal(expected).unscaledValue();
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(), result.highValueAt(0));
    assertEquals(unscaled.longValue(), result.valueAt(0));
    assertEquals(SqlTypeDescriptor.decimal(22, 18), result.typeDescriptorAt(0));
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
