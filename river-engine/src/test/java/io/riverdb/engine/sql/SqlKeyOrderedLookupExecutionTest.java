package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.sql.SqlCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Semantic and scale boundaries for canonical dependent primary-key lookup order. */
final class SqlKeyOrderedLookupExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4b45594f52444552L, 0x45444c4f4f4b5550L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void pagesAndExternallyOrdersRowsBeyondTheFormerPrototypeCardinality(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root, true);
    try {
      createTables(fixture);
      int rows = 2_200;
      insertRows(fixture, rows);

      assertEquals(StatusCode.OK,
          fixture.session.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.session.execute(query(), fixture.result));
      assertEquals(rows, fixture.result.value());
      assertEquals(StatusCode.OK, fixture.session.execute("COMMIT", fixture.result));
      assertEquals(0, fixture.database.activeTransactionCount());
      assertEquals(0, fixture.database.activeLockCount());
    } finally {
      fixture.close();
    }
  }

  @Test
  void preservesDuplicateMissingNullResidualAndSignedKeySemantics(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root, false);
    try {
      createTables(fixture);
      assertOk(fixture, "INSERT INTO stock VALUES (1,-2,5),(1,1,5),(1,2,20)");
      assertOk(fixture,
          "INSERT INTO order_line VALUES "
              + "(1,1,1,1,1,10,'keep'),"
              + "(1,1,2,1,1,10,'keep'),"
              + "(1,1,3,1,-2,10,'keep'),"
              + "(1,1,4,1,99,10,'keep'),"
              + "(1,1,5,1,NULL,10,'keep'),"
              + "(1,1,6,1,2,10,'keep'),"
              + "(1,1,7,1,-2,10,'drop')");

      assertEquals(StatusCode.OK,
          fixture.session.execute("BEGIN SERIALIZABLE", fixture.result));
      assertEquals(StatusCode.OK, fixture.session.execute(
          "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
              + "INNER JOIN stock s ON s.s_w_id=1 AND s.s_i_id=ol.ol_i_id "
              + "AND ol.ol_marker='keep' WHERE ol.ol_w_id=1 AND ol.ol_d_id=1 "
              + "AND s.s_quantity<ol.ol_limit",
          fixture.result));
      assertEquals(2, fixture.result.value());
      assertEquals(StatusCode.OK, fixture.session.execute(
          "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
              + "INNER JOIN stock s ON s.s_w_id=1 AND s.s_i_id=ol.ol_i_id "
              + "WHERE ol.ol_w_id=1 AND s.s_quantity<30",
          fixture.result));
      assertEquals(3, fixture.result.value());
      assertEquals(StatusCode.OK, fixture.session.execute("ROLLBACK", fixture.result));
      assertEquals(0, fixture.database.activeTransactionCount());
      assertEquals(0, fixture.database.activeLockCount());
    } finally {
      fixture.close();
    }
  }

  private static void createTables(Fixture fixture) {
    assertOk(fixture,
        "CREATE TABLE stock (s_w_id BIGINT NOT NULL,s_i_id BIGINT NOT NULL,"
            + "s_quantity BIGINT NOT NULL,PRIMARY KEY(s_w_id,s_i_id))");
    assertOk(fixture,
        "CREATE TABLE order_line (ol_w_id BIGINT NOT NULL,ol_d_id BIGINT NOT NULL,"
            + "ol_o_id BIGINT NOT NULL,ol_number BIGINT NOT NULL,ol_i_id BIGINT,"
            + "ol_limit BIGINT NOT NULL,ol_marker VARCHAR(8) NOT NULL,"
            + "PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number))");
  }

  private static void insertRows(Fixture fixture, int rows) {
    for (int first = 1; first <= rows; first += SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS) {
      int last = Math.min(rows, first + SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS - 1);
      StringBuilder stock = new StringBuilder("INSERT INTO stock VALUES ");
      StringBuilder orderLine = new StringBuilder("INSERT INTO order_line VALUES ");
      for (int row = first; row <= last; row++) {
        if (row > first) {
          stock.append(',');
          orderLine.append(',');
        }
        stock.append("(1,").append(row).append(",5)");
        orderLine.append("(1,1,").append(row).append(",1,")
            .append(row).append(",10,'keep')");
      }
      assertOk(fixture, stock.toString());
      assertOk(fixture, orderLine.toString());
    }
  }

  private static String query() {
    return "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
        + "INNER JOIN stock s ON s.s_w_id=ol.ol_w_id AND s.s_i_id=ol.ol_i_id "
        + "WHERE ol.ol_w_id=1 AND ol.ol_d_id=1 AND s.s_quantity<ol.ol_limit";
  }

  private static void assertOk(Fixture fixture, String sql) {
    assertEquals(StatusCode.OK, fixture.session.execute(sql, fixture.result), sql);
  }

  private static Fixture open(Path root, boolean forceExternalOrder) throws Exception {
    if (forceExternalOrder) {
      Files.writeString(
          root.resolve(RiverRuntimeConfig.FILE_NAME),
          "river.sql.materialized.page=8KB\n"
              + "river.sql.materialized.cache=1MB\n"
              + "river.sql.materialized.sort-run=16KB\n",
          StandardCharsets.UTF_8);
    }
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    SqlSessionOpenResult session = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), session));
    return new Fixture(opened.database(), session.session());
  }

  private static final class Fixture {
    final RelationalDatabase database;
    final SqlSession session;
    final SqlExecutionResult result = new SqlExecutionResult();

    Fixture(RelationalDatabase relationalDatabase, SqlSession sqlSession) {
      database = relationalDatabase;
      session = sqlSession;
    }

    void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
