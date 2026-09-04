package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-session evidence for composite descriptor child access and residuals. */
final class SqlDescriptorSubqueryIndexTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x445343535542494eL, 0x4445583030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void rebindsCompositePrefixAndRangeForEveryOuterRow(@TempDir Path root) {
    Fixture fixture = create(root);
    String correlated = "SELECT p.id FROM child_parent p WHERE EXISTS "
        + "(SELECT c.id FROM child_rows c WHERE c.warehouse=p.warehouse "
        + "AND c.district=p.district AND c.sequence>=p.floor)";
    fixture.assertRows(correlated, 1, 2, 3);
    fixture.assertRows(correlated.replace(")", " LIMIT 0)"));
    fixture.assertRows(correlated.replace(")", " LIMIT 1)"), 1, 2, 3);
    fixture.assertRows("SELECT p.id FROM child_parent p WHERE EXISTS "
        + "(SELECT c.id FROM child_rows c WHERE p.warehouse=c.warehouse "
        + "AND p.district=c.district AND p.floor<=c.sequence)", 1, 2, 3);
    fixture.assertRows("SELECT p.id FROM child_parent p WHERE EXISTS "
        + "(SELECT c.id FROM child_rows c WHERE c.warehouse=p.warehouse "
        + "AND c.district=p.nullable_district)", 1, 2);
    fixture.assertOuterAccess(correlated, "index", 1);
    fixture.assertEdgeCounters(correlated, 4, 4, 3, 3, 4, 3);
    fixture.close();
  }

  @Test
  void usesLiteralCompositeBoundsAndFallsBackSafelyForOr(@TempDir Path root) {
    Fixture fixture = create(root);
    fixture.assertRows("SELECT p.id FROM child_parent p WHERE EXISTS "
        + "(SELECT c.id FROM child_rows c WHERE c.warehouse=1 AND c.district=10 "
        + "AND c.sequence>=15 AND c.sequence<25)", 1, 2, 3, 4);
    String disjunction = "SELECT p.id FROM child_parent p WHERE EXISTS "
        + "(SELECT c.id FROM child_rows c WHERE c.warehouse=p.warehouse "
        + "OR c.district=p.district)";
    fixture.assertRows(disjunction, 1, 2, 3);
    fixture.assertOuterAccess(disjunction, "table", -1);
    fixture.close();
  }

  private static Fixture create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    Fixture fixture = new Fixture(database, sessions.session());
    fixture.execute("CREATE TABLE child_parent (id BIGINT PRIMARY KEY,"
        + "warehouse BIGINT,district BIGINT,floor BIGINT,nullable_district BIGINT)");
    fixture.execute("CREATE TABLE child_rows (id BIGINT PRIMARY KEY,"
        + "warehouse BIGINT,district BIGINT,sequence BIGINT)");
    fixture.execute("CREATE INDEX child_lookup ON child_rows(warehouse,district,sequence)");
    fixture.execute("INSERT INTO child_parent VALUES "
        + "(1,1,10,15,10),(2,1,20,25,20),(3,2,10,5,NULL),(4,9,9,0,NULL)");
    fixture.execute("INSERT INTO child_rows VALUES "
        + "(1,1,10,10),(2,1,10,20),(3,1,20,30),(4,2,10,6)");
    return fixture;
  }

  private static final class Fixture {
    private final RelationalDatabase database;
    private final SqlSession session;
    private final SqlExecutionResult result = new SqlExecutionResult();

    Fixture(RelationalDatabase owner, SqlSession sqlSession) {
      database = owner;
      session = sqlSession;
    }

    void execute(String sql) {
      assertEquals(StatusCode.OK, session.execute(sql, result), sql);
    }

    void assertRows(String sql, long... expected) {
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
      for (long value : expected) {
        assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
        assertEquals(value, row.valueAt(0), sql);
      }
      assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
      assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    }

    void assertOuterAccess(String sql, String operator, long detail) {
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, session.beginScan("EXPLAIN " + sql, cursor), sql);
      long actual = 0;
      long actualDetail = Long.MIN_VALUE;
      while (session.nextScan(cursor, row) == StatusCode.OK) {
        long candidate = row.valueAt(0);
        if (candidate == PackedText.pack("primary")
            || candidate == PackedText.pack("index")
            || candidate == PackedText.pack("table")) {
          actual = candidate;
          actualDetail = row.valueAt(1);
        }
      }
      assertEquals(PackedText.pack(operator), actual, sql);
      assertEquals(detail, actualDetail, sql);
      assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    }

    void assertEdgeCounters(String sql, long... expected) {
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      long[] actual = new long[6];
      int phase = 0;
      assertEquals(StatusCode.OK,
          session.beginScan("EXPLAIN ANALYZE " + sql, cursor), sql);
      while (session.nextScan(cursor, row) == StatusCode.OK) {
        long operator = row.valueAt(0);
        if (operator == PackedText.pack("exists")) phase = 1;
        if (phase > 0 && phase <= actual.length) actual[phase++ - 1] = row.valueAt(2);
      }
      assertEquals(expected.length, phase - 1, sql);
      for (int index = 0; index < expected.length; index++) {
        assertEquals(expected[index], actual[index], "phase " + index + ": " + sql);
      }
      assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    }

    void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
