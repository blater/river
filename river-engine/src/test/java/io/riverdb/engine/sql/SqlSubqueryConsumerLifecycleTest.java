package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract oracle for publication atomicity, terminal status, close, and reuse. */
final class SqlSubqueryConsumerLifecycleTest {

  @Test
  void pointSelectScansGraphAndOwnsFirstAcceptedUnicodeRow(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("INSERT INTO scalar_rows VALUES (4,4,40)");

    StatusCode status = fixture.session().execute(
        "SELECT o.label FROM outer_rows o WHERE o.value IN "
            + "(SELECT s.value FROM scalar_rows s "
            + "WHERE s.owner=o.id AND s.id=4)",
        fixture.result());
    assertEquals(StatusCode.OK, status);
    assertEquals(4, fixture.result().key());
    char[] text = new char[32];
    int length = fixture.result().copyTextAt(0, text, 0);
    assertEquals("東京-🌊-résumé", new String(text, 0, length));

    assertEquals(
        StatusCode.CONFLICT,
        fixture.session().execute(
            "SELECT o.id FROM outer_rows o WHERE o.id=3 AND o.value IN "
                + "(SELECT s.value FROM scalar_rows s WHERE s.id=4)",
            fixture.result()));
    assertFalse(fixture.result().hasValue());
    assertEquals(0, fixture.result().affectedRows());

    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT label FROM outer_rows WHERE id=1", fixture.result()));
    assertEquals(1, fixture.result().key());
    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT o.label FROM outer_rows o WHERE o.value IN "
                + "(SELECT s.value FROM scalar_rows s "
                + "WHERE s.owner=o.id AND s.id=4)",
            fixture.result()));
    assertEquals(4, fixture.result().key());

    assertEquals(
        StatusCode.CONFLICT,
        fixture.session().execute(
            "SELECT o.id FROM outer_rows o WHERE o.value IN "
                + "(SELECT s.value FROM scalar_rows s WHERE s.id=99)",
            fixture.result()));
    assertFalse(fixture.result().hasValue());
    assertEquals(0, fixture.result().affectedRows());

    fixture.close();
  }

  @Test
  void pointSelectUsesGenericScanForNonUniqueGraphEquality(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.session().execute(
            "SELECT id FROM outer_rows WHERE region=1", fixture.result()));
    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT o.id FROM outer_rows o WHERE o.region=1 AND EXISTS "
                + "(SELECT i.id FROM inner_rows i WHERE i.value=o.value)",
            fixture.result()));
    assertEquals(1, fixture.result().valueAt(0));

    fixture.close();
  }

  @Test
  void streamingGraphReturnsEveryAcceptedRow(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.id IN "
            + "(SELECT i.id FROM outer_rows i)",
        1, 2, 3, 4);

    fixture.close();
  }

  @Test
  void scalarAggregatesFilterBeforeMutationAndPublishAtomically(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("INSERT INTO inner_rows VALUES (14,40,3)");
    String admitted = " WHERE EXISTS (SELECT i.id FROM inner_rows i "
        + "WHERE i.region=o.region)";

    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT COUNT(*) FROM outer_rows o" + admitted,
            fixture.result()));
    assertEquals(4, fixture.result().valueAt(0));
    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT SUM(o.value) FROM outer_rows o" + admitted,
            fixture.result()));
    assertEquals(70, fixture.result().valueAt(0));
    assertEquals(
        StatusCode.OK,
        fixture.session().execute(
            "SELECT MAX(o.label) FROM outer_rows o" + admitted,
            fixture.result()));
    char[] text = new char[32];
    int length = fixture.result().copyTextAt(0, text, 0);
    assertEquals("東京-🌊-résumé", new String(text, 0, length));

    String failing = " FROM outer_rows o WHERE o.value="
        + "(SELECT s.value FROM scalar_rows s WHERE s.owner=o.id)";
    assertAggregateFailure(fixture, "SELECT COUNT(*)" + failing);
    assertAggregateFailure(fixture, "SELECT SUM(o.value)" + failing);
    fixture.assertRows("SELECT COUNT(*) FROM outer_rows", 4);

    fixture.close();
  }

  @Test
  void pointFailurePublishesNothingRepeatsAndAllowsReuse(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    String query = "SELECT o.id FROM outer_rows o WHERE o.id>1 AND o.value="
        + "(SELECT s.value FROM scalar_rows s WHERE s.owner=o.id)";

    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(
          StatusCode.CARDINALITY_VIOLATION,
          fixture.session().execute(query, fixture.result()));
      assertFalse(fixture.result().hasValue());
      assertEquals(0, fixture.result().affectedRows());
    }
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);

    fixture.close();
  }

  @Test
  void aggregateConsumesLazyTruthWithoutEvaluatingSkippedScalar(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();

    assertEquals(
        StatusCode.OK,
        fixture.session().beginScan(
            "SELECT COUNT(*) FROM outer_rows o WHERE o.id=o.id OR "
                + "o.value=(SELECT i.value FROM inner_rows i)",
            cursor));
    assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row));
    assertEquals(4, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row));
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()));

    fixture.close();
  }

  @Test
  void reportsCorrelatedCardinalityDuringMaterializationAndReuses(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    SqlScanCursor cursor = new SqlScanCursor();
    String query = "SELECT o.id FROM outer_rows o WHERE o.value="
        + "(SELECT s.value FROM scalar_rows s WHERE s.owner=o.id)";

    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().beginScan(query, cursor));
    assertFalse(cursor.isActive());
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().beginScan(query, cursor));
    assertFalse(cursor.isActive());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);

    fixture.close();
  }

  @Test
  void failedAggregatePublishesNoPartialCountAndSessionRecovers(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    SqlScanCursor cursor = new SqlScanCursor();

    StatusCode status = fixture.session().beginScan(
        "SELECT COUNT(*) FROM outer_rows o WHERE o.value="
            + "(SELECT s.value FROM scalar_rows s WHERE s.owner=o.id)",
        cursor);
    assertEquals(StatusCode.CARDINALITY_VIOLATION, status);
    assertFalse(cursor.isActive());
    assertEquals(StatusCode.OK, cursor.reset());
    fixture.assertRows("SELECT COUNT(*) FROM outer_rows", 4);

    fixture.close();
  }

  private static void assertAggregateFailure(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(
          StatusCode.CARDINALITY_VIOLATION,
          fixture.session().execute(sql, fixture.result()));
      assertFalse(fixture.result().hasValue());
      assertEquals(0, fixture.result().affectedRows());
    }
  }
}
