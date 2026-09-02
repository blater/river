package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlShapeCapacityTest {
  @Test
  void growsTableInsertAndProjectionShapesThroughLegacyBoundary() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertColumns(parser, command, 8, StatusCode.OK);
    assertColumns(parser, command, 9, StatusCode.OK);
    assertColumns(parser, command, SqlShapeLimits.MAX_TABLE_COLUMNS, StatusCode.OK);
    assertColumns(parser, command, SqlShapeLimits.MAX_TABLE_COLUMNS + 1,
        StatusCode.RESOURCE_EXHAUSTED);
    assertInsert(parser, command, SqlShapeLimits.MAX_INSERT_COLUMNS, StatusCode.OK);
    assertInsert(parser, command, SqlShapeLimits.MAX_INSERT_COLUMNS + 1,
        StatusCode.RESOURCE_EXHAUSTED);
    assertUpdate(parser, command, 8, StatusCode.OK);
    assertUpdate(parser, command, 9, StatusCode.OK);
    assertUpdate(parser, command, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS, StatusCode.OK);
    assertUpdate(parser, command, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS + 1,
        StatusCode.RESOURCE_EXHAUSTED);
    assertProjection(parser, command, 8, StatusCode.OK);
    assertProjection(parser, command, 9, StatusCode.OK);
    assertProjection(parser, command, SqlShapeLimits.MAX_RESULT_COLUMNS, StatusCode.OK);
    assertProjection(parser, command, SqlShapeLimits.MAX_RESULT_COLUMNS + 1,
        StatusCode.RESOURCE_EXHAUSTED);
  }

  @Test
  void columnConstraintFlagsCrossScalarMaskBoundaries() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    StringBuilder sql = new StringBuilder("CREATE TABLE t (id BIGINT PRIMARY KEY");
    for (int index = 0; index < SqlShapeLimits.MAX_FOREIGN_KEYS; index++) {
      sql.append(",c").append(index).append(" BIGINT REFERENCES p(id)");
    }
    sql.append(')');
    assertEquals(StatusCode.OK, parser.parse(sql, command));
    assertTrue(command.columnHasReference(32));
    assertTrue(command.columnHasReference(64));
    sql.insert(sql.length() - 1, ",overflow BIGINT REFERENCES p(id)");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(sql, command));
    assertFalse(command.isAvailable());
  }

  @Test
  void growsJoinAndPredicateShapesToIndependentLimits() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertJoin(parser, command, 8, StatusCode.OK);
    assertJoin(parser, command, 9, StatusCode.OK);
    assertJoin(parser, command, SqlShapeLimits.MAX_JOIN_ROLES, StatusCode.OK);
    assertJoin(parser, command, SqlShapeLimits.MAX_JOIN_ROLES + 1,
        StatusCode.RESOURCE_EXHAUSTED);
    assertPredicate(parser, command, 8, StatusCode.OK);
    assertPredicate(parser, command, 9, StatusCode.OK);
    assertPredicate(parser, command, SqlShapeLimits.MAX_PREDICATE_LEAVES, StatusCode.OK);
    assertPredicate(parser, command, SqlShapeLimits.MAX_PREDICATE_LEAVES + 1,
        StatusCode.RESOURCE_EXHAUSTED);
  }

  @Test
  void exactAggregateAndNodeArenasRejectPlusOneAtomicallyAndReuse() {
    SqlAggregateSet aggregates = new SqlAggregateSet();
    for (int index = 0; index < SqlShapeLimits.MAX_AGGREGATES; index++) {
      assertEquals(index, aggregates.appendInvocation(SqlAggregateKind.COUNT, -1));
      assertTrue(aggregates.appendOutput(index));
    }
    assertEquals(-1, aggregates.appendInvocation(SqlAggregateKind.COUNT, -1));
    assertEquals(SqlShapeLimits.MAX_AGGREGATES, aggregates.invocationCount());
    aggregates.reset();
    assertEquals(0, aggregates.appendInvocation(SqlAggregateKind.COUNT, -1));

    SqlScalarExpression expression = new SqlScalarExpression();
    for (int node = 0; node < SqlShapeLimits.MAX_EXPRESSION_NODES; node++) {
      assertTrue(expression.append(
          SqlScalarExpression.LITERAL, node, SqlTypeDescriptor.BIGINT));
    }
    assertFalse(expression.append(
        SqlScalarExpression.LITERAL, 0, SqlTypeDescriptor.BIGINT));
    assertEquals(SqlShapeLimits.MAX_EXPRESSION_NODES, expression.nodeCount());
    expression.reset();
    assertTrue(expression.append(
        SqlScalarExpression.LITERAL, 1, SqlTypeDescriptor.BIGINT));
  }

  @Test
  void wideQueryCopyPublishesOnlyAfterCompleteCopy() {
    SqlParser parser = new SqlParser();
    SqlCommand source = new SqlCommand();
    SqlCommand destination = new SqlCommand();
    assertProjection(parser, source, SqlShapeLimits.MAX_RESULT_COLUMNS, StatusCode.OK);

    assertEquals(StatusCode.OK, destination.copyBlockFrom(source));
    assertTrue(destination.isAvailable());
    assertEquals(SqlShapeLimits.MAX_RESULT_COLUMNS, destination.columnCount());
    assertEquals(
        SqlScalarExpression.COLUMN,
        destination.projectionExpression(SqlShapeLimits.MAX_RESULT_COLUMNS - 1).operator(0));

    source.columnCount = SqlShapeLimits.MAX_RESULT_COLUMNS + 1;
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, destination.copyBlockFrom(source));
    assertFalse(destination.isAvailable());
    assertEquals(0, destination.columnCount());
  }

  @Test
  void selectAllExpansionValidatesAndAdmitsTheWholeShapeBeforePublication() {
    SqlParser parser = new SqlParser();
    SqlCommand source = new SqlCommand();
    SqlCommand target = new SqlCommand();
    assertProjection(parser, source, SqlShapeLimits.MAX_RESULT_COLUMNS, StatusCode.OK);
    assertEquals(StatusCode.OK, parser.parse("SELECT * FROM t", target));

    assertEquals(StatusCode.OK, target.expandSelectAllFrom(source));
    assertFalse(target.isSelectAll());
    assertEquals(SqlShapeLimits.MAX_RESULT_COLUMNS, target.columnCount());

    target.reset();
    assertEquals(StatusCode.OK, parser.parse("SELECT * FROM t", target));
    source.columnNames[0].reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, target.expandSelectAllFrom(source));
    assertTrue(target.isSelectAll());
    assertEquals(0, target.columnCount());
  }

  private static void assertColumns(
      SqlParser parser, SqlCommand command, int count, StatusCode expected) {
    StringBuilder sql = new StringBuilder("CREATE TABLE t (c0 BIGINT PRIMARY KEY");
    for (int index = 1; index < count; index++) sql.append(",c").append(index).append(" BIGINT");
    sql.append(')');
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(count, command.columnCount());
  }

  private static void assertInsert(
      SqlParser parser, SqlCommand command, int count, StatusCode expected) {
    StringBuilder sql = new StringBuilder("INSERT INTO t VALUES (");
    for (int index = 0; index < count; index++) {
      if (index > 0) sql.append(',');
      sql.append(index);
    }
    sql.append(')');
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(count, command.insertColumnCount());
  }

  private static void assertProjection(
      SqlParser parser, SqlCommand command, int count, StatusCode expected) {
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int index = 0; index < count; index++) {
      if (index > 0) sql.append(',');
      sql.append('c').append(index);
    }
    sql.append(" FROM t");
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(count, command.columnCount());
  }

  private static void assertUpdate(
      SqlParser parser, SqlCommand command, int count, StatusCode expected) {
    StringBuilder sql = new StringBuilder("UPDATE t SET ");
    for (int index = 0; index < count; index++) {
      if (index > 0) sql.append(',');
      sql.append('c').append(index).append('=').append(index);
    }
    sql.append(" WHERE id=1");
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(count, command.updateColumnCount());
  }

  private static void assertJoin(
      SqlParser parser, SqlCommand command, int roles, StatusCode expected) {
    StringBuilder sql = new StringBuilder("SELECT t0.c FROM t0 t0");
    for (int role = 1; role < roles; role++) {
      sql.append(" JOIN t").append(role).append(" t").append(role)
          .append(" ON t").append(role - 1).append(".c=t").append(role).append(".c");
    }
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(roles, command.joinChain().roleCount());
  }

  private static void assertPredicate(
      SqlParser parser, SqlCommand command, int leaves, StatusCode expected) {
    StringBuilder sql = new StringBuilder("SELECT c FROM t WHERE ");
    for (int leaf = 0; leaf < leaves; leaf++) {
      if (leaf > 0) sql.append(" AND ");
      sql.append('c').append('=').append(leaf);
    }
    assertEquals(expected, parser.parse(sql, command));
    if (expected.isOk()) assertEquals(leaves, command.wherePredicates().leafCount());
  }
}
