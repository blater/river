package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDynamicExecutionShapeTest {
  @Test
  void boundQueryAdmitsActualCountTopologyAtBothLimits() {
    assertBoundShape(nestedQuery(SqlQuery.MAXIMUM_QUERY_BLOCKS),
        SqlQuery.MAXIMUM_QUERY_BLOCKS, SqlQuery.MAXIMUM_EDGES, 1);
    assertBoundShape(joinQuery(SqlShapeLimits.MAX_JOIN_ROLES),
        1, 0, SqlShapeLimits.MAX_JOIN_ROLES);
  }

  @Test
  void physicalResultShapeCarriesNullableLaneAcrossAllWords() {
    SqlPhysicalPlan plan = new SqlPhysicalPlan();
    assertEquals(StatusCode.OK, plan.beginResult(SqlShapeLimits.MAX_RESULT_COLUMNS));
    for (int index = 0; index < SqlShapeLimits.MAX_RESULT_COLUMNS; index++) {
      plan.setResultColumn(index, index, SqlTypeDescriptor.BIGINT, "c");
    }
    plan.setResultNullable(64, true);
    plan.setResultNullable(1_663, true);

    assertEquals(SqlShapeLimits.MAX_RESULT_COLUMNS, plan.resultColumnCount());
    assertEquals(26, plan.resultNullableWordCount());
    assertTrue(plan.resultNullable(64));
    assertTrue(plan.resultNullable(1_663));
    assertFalse(plan.resultNullable(63));
    assertEquals(1L, plan.resultNullableWord(1));
    assertEquals(1L << 63, plan.resultNullableWord(25));
  }

  @Test
  void physicalAndBlockShapesReject1665WithoutPublishingIt() {
    SqlPhysicalPlan plan = new SqlPhysicalPlan();
    assertEquals(StatusCode.OK, plan.beginResult(8));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, plan.beginResult(1_665));
    assertEquals(8, plan.resultColumnCount());

    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(8);
    assertEquals(StatusCode.OK, schema.status());
    schema.set(1_665);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, schema.status());
    assertEquals(8, schema.count());
  }

  @Test
  void planStepsGrowToSharedBound() {
    SqlPhysicalPlan plan = new SqlPhysicalPlan();
    for (int index = 0; index < SqlPhysicalPlan.MAXIMUM_STEPS; index++) {
      assertEquals(StatusCode.OK, plan.addStep(index, -index));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, plan.addStep(257, -257));
  }

  @Test
  void materializedBlockOutputPublishesLane1664AndWord25(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(SqlShapeLimits.MAX_RESULT_COLUMNS);
    SqlBlockRow source = new SqlBlockRow();
    assertEquals(StatusCode.OK, source.reset(SqlShapeLimits.MAX_RESULT_COLUMNS));
    source.setKey(7);
    for (int index = 0; index < SqlShapeLimits.MAX_RESULT_COLUMNS; index++) {
      schema.setColumn(index, "c", SqlTypeDescriptor.BIGINT, index == 1_663);
      source.setValue(index, index);
    }
    source.setNull(1_663);
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    assertEquals(StatusCode.OK, store.begin(schema, -1, false));
    assertEquals(StatusCode.OK, store.append(source));
    assertEquals(StatusCode.OK, store.finish());
    SqlBlockOutputShape output = new SqlBlockOutputShape();
    assertEquals(StatusCode.OK, output.prepare(schema));
    SqlScanRowResult result = new SqlScanRowResult();

    assertEquals(
        StatusCode.OK,
        SqlBlockOutputPublisher.next(store, source, schema, output, result));
    assertEquals(SqlShapeLimits.MAX_RESULT_COLUMNS, result.columnCount());
    assertEquals(1_662, result.valueAt(1_662));
    assertTrue(result.isNull(1_663));
    assertEquals(1L << 63, result.nullWord(25));
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  @Test
  void materializedBlockOutputRejects1665Atomically(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1_665);
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, schema.status());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.begin(schema, -1, false));
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  private static void assertBoundShape(
      String sql, int blocks, int edges, int roles) {
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    assertEquals(StatusCode.OK, new SqlParser().parseQuery(sql, query, command));
    BoundSqlQuery bound = new BoundSqlQuery();
    assertEquals(StatusCode.OK, bound.capture(command, query));
    assertEquals(blocks, bound.blockCount());
    assertEquals(edges, bound.edgeCount());
    assertEquals(roles, bound.root().roleCount());
  }

  private static String nestedQuery(int blocks) {
    String sql = "SELECT id FROM t" + (blocks - 1);
    for (int block = blocks - 2; block >= 0; block--) {
      sql = "SELECT id FROM t" + block + " WHERE EXISTS (" + sql + ")";
    }
    return sql;
  }

  private static String joinQuery(int roles) {
    StringBuilder sql = new StringBuilder("SELECT t0.id FROM t0");
    for (int role = 1; role < roles; role++) {
      sql.append(" JOIN t").append(role)
          .append(" ON t").append(role).append(".id=t0.id");
    }
    return sql.toString();
  }
}
