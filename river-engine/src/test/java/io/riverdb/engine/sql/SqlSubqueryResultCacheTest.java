package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class SqlSubqueryResultCacheTest {
  @Test
  void retainsOnlyPayloadRequiredByStatementShape() throws Exception {
    SqlSubqueryResultCache idle = cache(
        "SELECT id FROM outer_rows", 0);
    assertPayload(idle, false, false, false);

    SqlSubqueryResultCache scalarText = cache(
        "SELECT o.id FROM outer_rows o WHERE o.label="
            + "(SELECT i.label FROM inner_rows i)",
        SqlTypeDescriptor.varchar(32));
    assertPayload(scalarText, false, false, true);

    SqlSubqueryResultCache fixedMembership = cache(
        "SELECT o.id FROM outer_rows o WHERE o.id IN "
            + "(SELECT i.id FROM inner_rows i)",
        SqlTypeDescriptor.BIGINT);
    assertPayload(fixedMembership, true, false, false);

    SqlSubqueryResultCache textMembership = cache(
        "SELECT o.id FROM outer_rows o WHERE o.label IN "
            + "(SELECT i.label FROM inner_rows i)",
        SqlTypeDescriptor.varchar(32));
    assertPayload(textMembership, true, true, false);

    SqlSubqueryResultCache correlatedMembership = cache(
        "SELECT o.id FROM outer_rows o WHERE o.id IN "
            + "(SELECT i.id FROM inner_rows i WHERE i.id=o.id)",
        SqlTypeDescriptor.BIGINT,
        true);
    assertPayload(correlatedMembership, false, false, false);
  }

  @Test
  void preservesDescendantReplayWhenParentAbandonsInterleavedValues() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery syntax = new SqlQuery();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT o.id FROM outer_rows o WHERE o.id IN "
                + "(SELECT a.id FROM cache_a a WHERE a.id IN "
                + "(SELECT b.id FROM cache_b b))",
            syntax,
            command));
    BoundSqlQuery query = new BoundSqlQuery();
    assertEquals(StatusCode.OK, query.capture(command, syntax));
    int parent = edgeWithParent(query, 0);
    int descendant = parent == 0 ? 1 : 0;
    query.block(query.edgeChild(parent)).setProjection(0, SqlTypeDescriptor.BIGINT);
    query.block(query.edgeChild(descendant)).setProjection(0, SqlTypeDescriptor.BIGINT);

    SqlSubqueryResultCache cache =
        new SqlSubqueryResultCache(query, new SqlExpressionEvaluator());
    SqlPredicateOperand value = new SqlPredicateOperand();
    cache.prepare();
    cache.start(parent);
    for (int index = 0; index < 600; index++) {
      value.setValue(index, SqlTypeDescriptor.BIGINT, false);
      assertTrue(cache.append(parent, value));
    }
    cache.start(descendant);
    for (int index = 0; index < 424; index++) {
      value.setValue(10_000 + index, SqlTypeDescriptor.BIGINT, false);
      assertTrue(cache.append(descendant, value));
    }
    cache.completeValues(descendant, 424);
    value.setValue(600, SqlTypeDescriptor.BIGINT, false);
    assertFalse(cache.append(parent, value));
    cache.abandon(parent);

    assertFalse(cache.enabled(parent));
    assertFalse(cache.available(parent));
    assertTrue(cache.enabled(descendant));
    assertTrue(cache.available(descendant));
    value.setValue(10_123, SqlTypeDescriptor.BIGINT, false);
    assertEquals(SqlBooleanPredicateEvaluator.TRUE, cache.truth(descendant, value));
  }

  @Test
  void erasesLongUnicodeBeforeAbandonAndShorterReuse() throws Exception {
    char[] longText = "漢🙂字🌍-long-value".toCharArray();
    char[] shortText = "多🙂".toCharArray();
    int descriptor = SqlTypeDescriptor.varchar(32);
    SqlPredicateOperand value = new SqlPredicateOperand();

    SqlSubqueryResultCache membership = cache(
        "SELECT o.id FROM outer_rows o WHERE o.label IN "
            + "(SELECT i.label FROM inner_rows i)",
        descriptor);
    value.setTextCharacters(longText, 0, longText.length, descriptor);
    membership.start(0);
    assertTrue(membership.append(0, value));
    char[] arena = (char[]) field(membership, "membershipText");
    assertCharacters(arena, longText);
    membership.abandon(0);
    assertZero(arena, longText.length);
    membership.prepare();
    membership.start(0);
    value.setTextCharacters(shortText, 0, shortText.length, descriptor);
    assertTrue(membership.append(0, value));
    assertCharacters(arena, shortText);
    membership.clear();
    assertZero(arena, longText.length);

    SqlSubqueryResultCache scalar = cache(
        "SELECT o.id FROM outer_rows o WHERE o.label="
            + "(SELECT i.label FROM inner_rows i)",
        descriptor);
    scalar.start(0);
    value.setTextCharacters(longText, 0, longText.length, descriptor);
    assertTrue(scalar.append(0, value));
    char[][] scalars = (char[][]) field(scalar, "scalarText");
    assertCharacters(scalars[0], longText);
    value.setTextCharacters(shortText, 0, shortText.length, descriptor);
    assertTrue(scalar.append(0, value));
    assertCharacters(scalars[0], shortText);
    for (int index = shortText.length; index < longText.length; index++) {
      assertEquals(0, scalars[0][index]);
    }
    scalar.clear();
    assertZero(scalars[0], longText.length);
  }

  private static int edgeWithParent(BoundSqlQuery query, int parent) {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      if (query.edgeParent(edge) == parent) return edge;
    }
    return -1;
  }

  private static SqlSubqueryResultCache cache(String sql, int projectionType) {
    return cache(sql, projectionType, false);
  }

  private static SqlSubqueryResultCache cache(
      String sql, int projectionType, boolean correlated) {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery syntax = new SqlQuery();
    assertEquals(StatusCode.OK, parser.parseQuery(sql, syntax, command));
    BoundSqlQuery query = new BoundSqlQuery();
    assertEquals(StatusCode.OK, query.capture(command, syntax));
    if (query.edgeCount() > 0) {
      query.block(query.edgeChild(0)).setProjection(0, projectionType);
      if (correlated) query.markCorrelated(query.edgeChild(0), 0);
    }
    SqlSubqueryResultCache cache =
        new SqlSubqueryResultCache(query, new SqlExpressionEvaluator());
    cache.prepare();
    return cache;
  }

  private static void assertPayload(
      SqlSubqueryResultCache cache,
      boolean fixedMembership,
      boolean textMembership,
      boolean scalarText) throws Exception {
    assertEquals(fixedMembership, field(cache, "values") != null);
    Object membership = field(cache, "membershipText");
    assertEquals(textMembership, membership != null);
    if (membership != null) {
      assertEquals(1_024 * 510, ((char[]) membership).length);
    }
    char[][] scalars = (char[][]) field(cache, "scalarText");
    boolean foundScalar = false;
    for (char[] value : scalars) {
      if (value == null) continue;
      assertEquals(510, value.length);
      foundScalar = true;
    }
    assertEquals(scalarText, foundScalar);
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void assertCharacters(char[] actual, char[] expected) {
    for (int index = 0; index < expected.length; index++) {
      assertEquals(expected[index], actual[index]);
    }
  }

  private static void assertZero(char[] actual, int length) {
    for (int index = 0; index < length; index++) assertEquals(0, actual[index]);
  }

}
