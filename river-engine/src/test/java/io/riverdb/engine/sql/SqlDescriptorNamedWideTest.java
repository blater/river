package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorNamedWideTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x574944454e414d45L, 0x4453514c54455354L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int TABLE_COLUMNS = 1_024;
  private static final int RESULT_COLUMNS = 1_664;

  @Test
  void namedWideTableUsesDescriptorRowsForCompletePointLifecycle(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(StatusCode.OK, session.execute(createSql(), result));
    assertEquals(StatusCode.OK, session.execute(insertSql(), result));
    assertEquals(1, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute(selectSql(RESULT_COLUMNS), result));
    assertEquals(RESULT_COLUMNS, result.columnCount());
    assertEquals(7, result.valueAt(0));
    assertTrue(result.isNull(1));
    assertTrue(result.isNull(1_663));
    assertEquals(-1L, result.nullWord(25));

    assertEquals(StatusCode.OK,
        session.execute("UPDATE wide_named SET c1023=TRUE WHERE c0=7", result));
    assertEquals(1, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute(
        "SELECT c1023 FROM wide_named WHERE c0=7", result));
    assertFalse(result.isNull(0));
    assertEquals(1, result.valueAt(0));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        session.execute(selectSql(RESULT_COLUMNS + 1), result));

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(StatusCode.OK, session.execute(
        "SELECT c0,c1023 FROM wide_named WHERE c0=7", result));
    assertEquals(2, result.columnCount());
    assertEquals(7, result.valueAt(0));
    assertEquals(1, result.valueAt(1));

    assertEquals(StatusCode.OK,
        session.execute("DELETE FROM wide_named WHERE c0=7", result));
    assertEquals(1, result.affectedRows());
    assertEquals(StatusCode.CONFLICT, session.execute(
        "SELECT c0 FROM wide_named WHERE c0=7", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static String createSql() {
    StringBuilder sql = new StringBuilder("CREATE TABLE wide_named (");
    for (int index = 0; index < TABLE_COLUMNS; index++) {
      if (index != 0) sql.append(',');
      sql.append('c').append(index).append(index == 0 ? " BIGINT" : " BOOLEAN");
      if (index == 0) sql.append(" PRIMARY KEY");
    }
    return sql.append(')').toString();
  }

  private static String insertSql() {
    StringBuilder sql = new StringBuilder("INSERT INTO wide_named VALUES (");
    for (int index = 0; index < TABLE_COLUMNS; index++) {
      if (index != 0) sql.append(',');
      if (index == TABLE_COLUMNS - 1) sql.append("NULL");
      else if (index == 0) sql.append(7);
      else sql.append((index & 1) == 0 ? "TRUE" : "FALSE");
    }
    return sql.append(')').toString();
  }

  private static String selectSql(int columns) {
    StringBuilder sql = new StringBuilder("SELECT c0");
    for (int index = 1; index < columns; index++) sql.append(",c1023");
    return sql.append(" FROM wide_named WHERE c0=7").toString();
  }
}
