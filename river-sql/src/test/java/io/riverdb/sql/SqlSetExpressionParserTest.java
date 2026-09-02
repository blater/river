package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlSetExpressionParserTest {
  private final SqlParser parser = new SqlParser();
  private final SqlQuery query = new SqlQuery();
  private final SqlCommand result = new SqlCommand();

  @Test
  void modelsUnionAllAsAStableBinaryTopology() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM a UNION ALL SELECT id FROM b", query, result));

    assertTrue(query.hasSetExpression());
    assertEquals(2, query.blockCount());
    assertEquals(3, query.setNodeCount());
    assertEquals(SqlQuery.SET_UNION_ALL, query.setNodeKind(query.setRootNode()));
    assertLeaf(query.setLeftNode(query.setRootNode()), 0);
    assertLeaf(query.setRightNode(query.setRootNode()), 1);
    assertTrue(result.isAvailable());
  }

  @Test
  void defaultsBareUnionToDistinctAndAssociatesFromTheLeft() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM a UNION DISTINCT SELECT id FROM b "
                + "UNION SELECT id FROM c",
            query,
            result));

    int root = query.setRootNode();
    assertEquals(SqlQuery.SET_UNION_DISTINCT, query.setNodeKind(root));
    assertLeaf(query.setRightNode(root), 2);
    int left = query.setLeftNode(root);
    assertEquals(SqlQuery.SET_UNION_DISTINCT, query.setNodeKind(left));
    assertLeaf(query.setLeftNode(left), 0);
    assertLeaf(query.setRightNode(left), 1);
  }

  @Test
  void preservesNestedParenthesizedSetExpressions() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "(SELECT id FROM a UNION ALL SELECT id FROM b) UNION DISTINCT "
                + "(SELECT id FROM c UNION ALL SELECT id FROM d)",
            query,
            result));

    int root = query.setRootNode();
    assertEquals(SqlQuery.SET_UNION_DISTINCT, query.setNodeKind(root));
    assertEquals(
        SqlQuery.SET_UNION_ALL,
        query.setNodeKind(query.setLeftNode(root)));
    assertEquals(
        SqlQuery.SET_UNION_ALL,
        query.setNodeKind(query.setRightNode(root)));
    assertEquals(4, query.blockCount());
    assertEquals(7, query.setNodeCount());
  }

  @Test
  void ownsWholeSetOrderingAndLimitWithoutChangingLeafCardinality() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id AS item FROM a UNION ALL SELECT id FROM b "
                + "ORDER BY item DESC LIMIT 7",
            query,
            result));

    assertEquals(1, query.setOrderExpressionCount());
    assertName("item", query.setOrderColumnName(0));
    assertTrue(query.isSetOrderDescending(0));
    assertEquals(7, query.setRowLimit());
    assertFalse(query.block(0).isOrdered());
    assertFalse(query.block(1).isOrdered());
    assertEquals(Long.MAX_VALUE, query.block(0).rowLimit());
    assertEquals(Long.MAX_VALUE, query.block(1).rowLimit());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "(SELECT id FROM a UNION SELECT id FROM b ORDER BY id LIMIT 3)",
            query,
            result));
    assertEquals(1, query.setOrderExpressionCount());
    assertEquals(3, query.setRowLimit());
  }

  @Test
  void distinguishesParenthesizedOperandTailFromWholeSetTail() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM a UNION ALL "
                + "(SELECT id FROM b ORDER BY id DESC LIMIT 2) "
                + "ORDER BY id LIMIT 5",
            query,
            result));

    assertTrue(query.block(1).isOrdered());
    assertTrue(query.block(1).isDescendingOrder());
    assertEquals(2, query.block(1).rowLimit());
    assertEquals(1, query.setOrderExpressionCount());
    assertEquals(5, query.setRowLimit());
  }

  @Test
  void snapshotsPredicateSubqueryTopologyPerOperand() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM a WHERE EXISTS "
                + "(SELECT id FROM b WHERE b.id=a.id) UNION ALL "
                + "SELECT id FROM c WHERE id IN "
                + "(SELECT id FROM d WHERE d.id=c.id)",
            query,
            result));

    assertEquals(4, query.blockCount());
    assertEquals(0, query.edgeCount());
    assertLeaf(query.setLeftNode(query.setRootNode()), 0);
    assertLeaf(query.setRightNode(query.setRootNode()), 2);
    assertCopiedLeaf(0, "a", "b");
    assertCopiedLeaf(2, "c", "d");
  }

  @Test
  void admitsGroupedHavingOperandsAndExplainModifiers() {
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "EXPLAIN ANALYZE SELECT tenant,COUNT(*) AS n FROM a "
                + "GROUP BY tenant HAVING COUNT(*)>1 UNION ALL "
                + "SELECT tenant,COUNT(*) AS n FROM b "
                + "GROUP BY tenant HAVING COUNT(*)>2 ORDER BY tenant LIMIT 3",
            query,
            result));

    assertTrue(query.isExplain());
    assertTrue(query.isAnalyze());
    assertEquals(2, query.blockCount());
    assertEquals(1, query.setOrderExpressionCount());
    assertEquals(3, query.setRowLimit());
  }

  @Test
  void rejectsInvalidTailAndExhaustedSetShapesAtomically() {
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT id FROM a UNION ALL SELECT id FROM b ORDER BY missing",
            query,
            result));
    assertFalse(query.hasSetExpression());
    assertFalse(result.isAvailable());

    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "(SELECT id FROM a UNION SELECT id FROM b ORDER BY id) "
                + "UNION SELECT id FROM c",
            query,
            result));

    StringBuilder sql = new StringBuilder("SELECT id FROM t0");
    for (int index = 1; index <= SqlQuery.MAXIMUM_QUERY_BLOCKS; index++) {
      sql.append(" UNION ALL SELECT id FROM t").append(index);
    }
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(sql, query, result));
    assertEquals(0, query.blockCount());
    assertFalse(result.isAvailable());
  }

  private void assertLeaf(int node, int block) {
    assertEquals(SqlQuery.SET_LEAF, query.setNodeKind(node));
    assertEquals(block, query.setLeafBlock(node));
  }

  private void assertCopiedLeaf(
      int block, String rootTable, String childTable) {
    SqlQuery copied = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, query.copySetLeafQuery(block, copied, command));
    assertEquals(2, copied.blockCount());
    assertEquals(1, copied.edgeCount());
    assertName(rootTable, copied.block(0).tableName());
    assertName(childTable, copied.block(1).tableName());
    assertEquals(0, copied.edgeParent(0));
    assertEquals(1, copied.edgeChild(0));
  }

  private static void assertName(String expected, SqlIdentifier actual) {
    assertEquals(expected.length(), actual.length());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), actual.charAt(index));
    }
  }
}
