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
  void latchesCorrelatedCardinalityAndReusesAfterClose(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    String query = "SELECT o.id FROM outer_rows o WHERE o.value="
        + "(SELECT s.value FROM scalar_rows s WHERE s.owner=o.id)";

    assertEquals(StatusCode.OK, fixture.session().beginScan(query, cursor));
    assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()));
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
}
