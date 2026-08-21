package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract oracle for admitted sibling, recursive, lazy, and three-valued truth. */
final class SqlSubqueryTruthAcceptanceTest {

  @Test
  void evaluatesSiblingAndRecursiveLeavesWithoutLosingContinuation(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE "
            + "EXISTS (SELECT i.id FROM inner_rows i WHERE i.region=o.region) "
            + "AND o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=o.region) "
            + "AND o.value=(SELECT i.value FROM inner_rows i WHERE i.id=11)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT m.id FROM inner_rows m WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i "
            + "WHERE i.id=m.id AND i.region=o.region) "
            + "AND m.value=o.value) AND o.id>0",
        1);

    fixture.close();
  }

  @Test
  void preservesCompleteMembershipThreeValuedLogic(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=99)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value NOT IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=99)",
        1, 2, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=1)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value NOT IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=1)");

    fixture.close();
  }

  @Test
  void skipsUnreachedScalarCardinalityFailures(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.id=o.id OR "
            + "o.value=(SELECT i.value FROM inner_rows i)",
        1, 2, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.id<>o.id AND "
            + "o.value=(SELECT i.value FROM inner_rows i)");
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        fixture.session().beginScan(
            "SELECT o.id FROM outer_rows o WHERE o.value="
                + "(SELECT i.value FROM inner_rows i)",
            cursor));
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().nextScan(cursor, row));
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()));
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);

    fixture.close();
  }
}
