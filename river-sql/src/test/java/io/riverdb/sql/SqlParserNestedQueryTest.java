package io.riverdb.sql;

import static io.riverdb.sql.SqlParserPredicateTestSupport.assertBooleanNotOverLeaf;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertPostfix;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertSubqueryEdge;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertText;
import static io.riverdb.sql.SqlParserPredicateTestSupport.isColumnPredicate;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipHasNull;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateColumnName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateDescriptor;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateExpression;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateLower;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateProgramDescriptor;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateTableName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateUpper;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValueColumnName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValueTableName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlParserParameterTextSupport.TestParameters;
import org.junit.jupiter.api.Test;

final class SqlParserNestedQueryTest {
  @Test
  void composesComputedProjectionProgramsAcrossViewAndDerivedEdges() {
    SqlParser parser = new SqlParser();
    SqlCommand source = new SqlCommand();
    SqlCommand copied = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT EXTRACT(DAY FROM happened) AS happened_day, id "
                + "FROM events",
            source));
    copied.copyQueryFrom(source);
    assertEquals(2, copied.projectionExpression(0).nodeCount());
    assertEquals(
        SqlScalarExpression.EXTRACT,
        copied.projectionExpression(0).operator(1));
    int copiedId = copied.directProjectionSymbol(1);
    assertName("id", copied.projections().symbolName(copiedId));

    SqlCommand outer = new SqlCommand();
    SqlCommand view = new SqlCommand();
    SqlCommand compiled = new SqlCommand();
    SqlQuery query = new SqlQuery();
    assertEquals(StatusCode.OK, parser.parse("SELECT d FROM day_view", outer));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT day AS d FROM moments", view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    int day = compiled.directProjectionSymbol(0);
    assertTrue(compiled.projectionExpression(0).isDirectColumnReference());
    assertName("day", compiled.projections().symbolName(day));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(DAY FROM observed) AS d FROM moments", view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    assertPostfix(
        compiled.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    int observed = (int) compiled.projectionExpression(0).operand(0);
    assertName("observed", compiled.projections().symbolName(observed));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT EXTRACT(DAY FROM shifted) AS result FROM "
                + "(SELECT day+1 AS shifted FROM moments) q",
            query,
            compiled));
    assertPostfix(
        compiled.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD,
        SqlScalarExpression.EXTRACT);
    assertName("result", compiled.columnOutputName(0));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT CAST(captured AS VARCHAR(32)) AS rendered FROM "
                + "(SELECT observed AT TIME ZONE 'Europe/London' AS captured "
                + "FROM moments) q",
            query,
            compiled));
    assertPostfix(
        compiled.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.CAST);
    assertText("Europe/London", compiled,
        compiled.projectionExpression(0).operand(1));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT EXTRACT(DAY FROM final_day) AS answer FROM "
                + "(SELECT shifted+1 AS final_day FROM "
                + "(SELECT day+1 AS shifted FROM moments) first) second",
            query,
            compiled));
    assertPostfix(
        compiled.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD,
        SqlScalarExpression.EXTRACT);

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT EXTRACT(DAY FROM day) AS d FROM moments) q "
                + "WHERE d=29",
            query,
            compiled));
    assertPostfix(
        predicateExpression(compiled, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(29, predicateValue(compiled, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM "
                + "(SELECT EXTRACT(YEAR FROM observed) AS d,id FROM moments) q "
                + "WHERE d=2024 AND id=1",
            query,
            compiled));
    assertEquals(2, compiled.wherePredicates().leafCount());
    assertPostfix(
        predicateExpression(compiled, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertNull(predicateExpression(compiled, 1));
    assertName("id", predicateColumnName(compiled, 1));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT day+1 AS d,id FROM moments "
                + "WHERE id=1) q WHERE EXTRACT(DAY FROM d)=29",
            query,
            compiled));
    assertEquals(2, compiled.wherePredicates().leafCount());
    assertNull(predicateExpression(compiled, 0));
    assertPostfix(
        predicateExpression(compiled, 1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD,
        SqlScalarExpression.EXTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT day+1 AS d FROM moments) q "
                + "WHERE EXTRACT(DAY FROM d)=1 AND d=DATE '2024-03-01'",
            query,
            compiled));
    assertEquals(2, compiled.wherePredicates().leafCount());
    assertPostfix(
        predicateExpression(compiled, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD,
        SqlScalarExpression.EXTRACT);
    assertPostfix(
        predicateExpression(compiled, 1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d,id FROM (SELECT day+1 AS d,id FROM moments) q "
                + "WHERE d=DATE '2024-03-01' OR id=1",
            query,
            compiled));
    assertEquals(
        SqlBooleanPredicateProgram.BOOLEAN_OR,
        compiled.wherePredicates().booleanOperator(
            compiled.wherePredicates().root()));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d,other FROM (SELECT day+1 AS d,day AS other FROM moments) q "
                + "WHERE d=other",
            query,
            compiled));
    assertEquals(1, compiled.wherePredicates().leafCount());
    assertEquals(
        1,
        compiled.wherePredicates().programNodeCount(
            0, SqlBooleanPredicateProgram.PROGRAM_RIGHT));
    assertEquals(
        SqlScalarExpression.COLUMN,
        compiled.wherePredicates().programOperator(
            0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT DISTINCT d FROM (SELECT day+1 AS d FROM moments) q",
            query,
            compiled));
    assertEquals(SqlCommandType.DISTINCT_SCAN, query.block(0).type());
    assertEquals(2, query.blockCount());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d,COUNT(*) FROM (SELECT day+1 AS d FROM moments) q GROUP BY d",
            query,
            compiled));
    assertEquals(SqlCommandType.GROUP_COUNT, query.block(0).type());
    assertEquals(2, query.blockCount());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT day AS d,COUNT(*) AS n FROM moments "
                + "GROUP BY day ORDER BY day) q",
            query,
            compiled));
    assertTrue(query.isBlockPipeline());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT day+1 AS d FROM moments ORDER BY d) q",
            query,
            compiled));
    assertTrue(query.isBlockPipeline());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d FROM (SELECT EXTRACT(DAY FROM day) AS d FROM moments) q "
                + "ORDER BY d",
            query,
            compiled));
    assertName("d", compiled.orderBy().name(0));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM (SELECT id,label FROM labels "
                + "WHERE label BETWEEN 'a' AND 'z' "
                + "AND label IN ('a','z',NULL)) q WHERE label='a'",
            query,
            compiled));
    assertEquals(3, compiled.wherePredicates().leafCount());
    assertText("a", compiled, predicateLower(compiled, 0));
    assertText("z", compiled, predicateUpper(compiled, 0));
    assertText("a", compiled, membershipValue(compiled, 1, 0));
    assertText("z", compiled, membershipValue(compiled, 1, 1));
    assertEquals(true, membershipHasNull(compiled, 1));
    assertText("a", compiled, predicateValue(compiled, 2));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM (SELECT id FROM moments "
                + "WHERE id=-9223372036854775808 "
                + "AND id IN (-9223372036854775808,0)) q",
            query,
            compiled));
    assertEquals(Long.MIN_VALUE, predicateValue(compiled, 0));
    assertEquals(Long.MIN_VALUE, membershipValue(compiled, 1, 0));

    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT id FROM (SELECT id,EXTRACT(DAY FROM id) AS bad "
                + "FROM moments) q",
            query,
            compiled));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT e+e AS f FROM (SELECT d+d AS e FROM "
                + "(SELECT c+c AS d FROM (SELECT b+b AS c FROM "
                + "(SELECT day+1 AS b FROM moments) one) two) three) four",
            query,
            compiled));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM moments WHERE id=(SELECT EXTRACT(DAY FROM day) "
                + "FROM moments WHERE id=1)",
            query,
            compiled));
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_SCALAR,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    assertPostfix(
        query.block(query.edgeChild(0)).projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
  }

  @Test
  void composesStoredViewDefinitionsWithOuterQueries() {
    SqlParser parser = new SqlParser();
    SqlCommand outer = new SqlCommand();
    SqlCommand view = new SqlCommand();
    SqlCommand compiled = new SqlCommand();
    SqlQuery query = new SqlQuery();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id, amount FROM valuable WHERE kind=7 ORDER BY id",
            outer));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id, category AS kind, amount FROM events WHERE amount>=100",
            view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    assertName("events", compiled.tableName());
    assertName("id", compiled.columnName(0));
    assertName("amount", compiled.columnName(1));
    assertName("amount", predicateColumnName(compiled, 0));
    assertName("category", predicateColumnName(compiled, 1));
    assertName("id", compiled.orderBy().name(0));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT * FROM valuable LIMIT 1", outer));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    assertEquals(3, compiled.columnCount());
    assertName("id", compiled.columnName(0));
    assertName("category", compiled.columnName(1));
    assertName("kind", compiled.columnOutputName(1));
    assertName("amount", compiled.columnName(2));
    assertEquals(1, compiled.rowLimit());
  }

  @Test
  void preservesTemporalPredicateDescriptorsWhenCompilingViews() {
    SqlParser parser = new SqlParser();
    SqlCommand outer = new SqlCommand();
    SqlCommand view = new SqlCommand();
    SqlCommand compiled = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT captured FROM current_temporal WHERE day=DATE '2024-02-29'",
            outer));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT day, captured FROM temporal_left "
                + "WHERE captured>=TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00.123+00:00'",
            view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    assertEquals(2, compiled.wherePredicates().leafCount());
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(3),
        predicateDescriptor(compiled, 0));
    assertEquals(SqlTypeDescriptor.DATE, predicateDescriptor(compiled, 1));
  }

  @Test
  void parsesAndCompilesBoundedDerivedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM "
                + "(SELECT id, region FROM accounts WHERE accounts.region=7) d "
                + "WHERE d.id >= 1 AND d.id < 5 ORDER BY region DESC LIMIT 2",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertName("accounts", command.tableName());
    assertName("id", command.firstColumnName());
    assertEquals(3, command.wherePredicates().leafCount());
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertName("id", predicateColumnName(command, 1));
    assertEquals(1, predicateValue(command, 1));
    assertName("id", predicateColumnName(command, 2));
    assertEquals(5, predicateValue(command, 2));
    assertName("region", command.orderBy().name(0));
    assertTrue(command.isDescendingOrder());
    assertEquals(2, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.account_id AS result_id FROM "
                + "(SELECT id AS account_id, balance funds FROM accounts "
                + "WHERE balance >= 100 AND balance < 300) d "
                + "WHERE d.account_id=2 ORDER BY result_id",
            query,
            command));
    assertName("id", command.firstColumnName());
    assertName("result_id", command.columnOutputName(0));
    assertName("balance", predicateColumnName(command, 0));
    assertName("balance", predicateColumnName(command, 1));
    assertName("id", predicateColumnName(command, 2));
    assertName("id", command.orderBy().name(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT second.final_id FROM "
                + "(SELECT first.account_id AS final_id FROM "
                + "(SELECT id AS account_id FROM accounts) first) second",
            query,
            command));
    assertName("id", command.firstColumnName());
    assertName("final_id", command.columnOutputName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedQuery(33), query, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT id FROM accounts LIMIT 1) d",
            query,
            command));
    assertTrue(query.isBlockPipeline());
    assertEquals(1, query.block(1).rowLimit());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT wrong.id FROM (SELECT id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.region FROM (SELECT id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT other.id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.duplicate FROM "
                + "(SELECT id AS duplicate, region AS duplicate FROM accounts) d",
            query,
            command));
  }

  @Test
  void measuresDerivedJoinDepthWithoutAdmittingPredicateSubqueries() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        2,
        parser.queryBlockDepth(
            "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id) joined"));
    assertEquals(
        -1,
        parser.queryBlockDepth(
            "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
                + "WHERE EXISTS (SELECT id FROM right_rows)) joined"));
    assertEquals(
        -1,
        parser.queryBlockDepth(
            "SELECT lid FROM (SELECT id AS lid FROM left_rows) joined "
                + "WHERE lid IN (SELECT left_id FROM right_rows)"));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery("SELECT id FROM outer_rows", query, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQueryAppend(
            "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id) joined",
            query,
            command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQueryAppend(
            "SELECT lid FROM (SELECT id AS lid FROM left_rows) joined "
                + "WHERE lid IN (SELECT left_id FROM right_rows)",
            query,
            command));
  }

  @Test
  void parsesExplainWithoutCopyingTheNestedQueryText() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "EXPLAIN SELECT id FROM events WHERE category=7",
            query,
            command));
    assertTrue(query.isExplain());
    assertFalse(query.isAnalyze());
    assertName("events", command.tableName());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            " EXPLAIN ANALYZE SELECT e.id FROM "
                + "(SELECT id, category FROM events) e WHERE e.category=7 ",
            query,
            command));
    assertTrue(query.isExplain());
    assertTrue(query.isAnalyze());
    assertEquals(2, query.blockCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("EXPLAIN", query, command));
  }

  @Test
  void parsesScalarPredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id, balance FROM accounts WHERE region=7 AND balance = "
                + "(SELECT balance FROM lookup WHERE lookup.id=7)",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertEquals(1, query.edgeCount());
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_SCALAR,
        0,
        1,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    assertEquals(SqlComparison.EQUAL, query.block(0).wherePredicates().comparison(1));
    assertName("accounts", command.tableName());
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertName("balance", predicateColumnName(command, 1));
    assertEquals(0, predicateValue(command, 1));
    SqlCommand scalar = query.block(query.edgeChild(0));
    assertName("lookup", scalar.tableName());
    assertName("balance", scalar.firstColumnName());
    assertName("id", predicateColumnName(scalar, 0));
    assertEquals(7, predicateValue(scalar, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region="
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_SCALAR,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    scalar = query.block(query.edgeChild(0));
    assertTrue(isColumnPredicate(scalar, 0));
    assertName("regions", predicateTableName(scalar, 0));
    assertName("id", predicateColumnName(scalar, 0));
    assertName("accounts", predicateValueTableName(scalar, 0));
    assertName("region", predicateValueColumnName(scalar, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedScalarQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertNestedSubqueryChain(query, 3, SqlQuery.SUBQUERY_SCALAR,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedScalarQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertNestedSubqueryChain(query, 32, SqlQuery.SUBQUERY_SCALAR,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedScalarQuery(33), query, command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE balance = "
                + "(SELECT id, balance FROM lookup WHERE id=7)",
            query,
            command));
  }

  @Test
  void parsesScalarSubqueryOnEitherComparisonOperand() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    String[] operators = {"=", "!=", "<", "<=", ">", ">="};
    SqlComparison[] normalized = {
        SqlComparison.EQUAL,
        SqlComparison.NOT_EQUAL,
        SqlComparison.GREATER_THAN,
        SqlComparison.GREATER_OR_EQUAL,
        SqlComparison.LESS_THAN,
        SqlComparison.LESS_OR_EQUAL
    };
    for (int index = 0; index < operators.length; index++) {
      assertEquals(
          StatusCode.OK,
          parser.parseQuery(
              "SELECT id FROM accounts WHERE "
                  + "(SELECT balance FROM lookup WHERE lookup.id=7) "
                  + operators[index] + " balance+1",
              query,
              command));
      assertSubqueryEdge(
          query,
          0,
          SqlQuery.SUBQUERY_SCALAR,
          0,
          0,
          1,
          SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
      assertEquals(normalized[index], command.wherePredicates().comparison(0));
      assertEquals(3, command.wherePredicates().programNodeCount(
          0, SqlBooleanPredicateProgram.PROGRAM_LEFT));
      assertEquals(SqlScalarExpression.ADD, command.wherePredicates().programOperator(
          0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 2));
    }
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE (SELECT id FROM left_side)="
                + "(SELECT id FROM right_side)",
            query,
            command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE balance>"
                + "(SELECT balance FROM lookup WHERE lookup.id=7)",
            query,
            command));
    assertEquals(SqlComparison.GREATER_THAN, command.wherePredicates().comparison(0));
  }

  @Test
  void assignsNestedTypedMarkersInOriginalLexicalOrder() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    TestParameters parameters = new TestParameters(
        new int[] {
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.varchar(8),
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.DATE,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT
        },
        new long[] {10, 0, 20, 0, 30, 40, 45, 50},
        new boolean[] {false, false, false, true, false, false, false, false},
        new String[] {null, "Aé😀", null, null, null, null, null, null});
    String sql = "SELECT id FROM accounts WHERE region=? AND EXISTS "
        + "(SELECT ? FROM lookup WHERE note=? AND label='?') "
        + "AND day=? AND id IN (SELECT ? FROM other WHERE value=? AND EXISTS "
        + "(SELECT ? FROM deep WHERE id=?))";

    assertEquals(StatusCode.OK, parser.parseQuery(sql, parameters, query, command));
    assertEquals(4, query.blockCount());
    assertEquals(3, query.edgeCount());
    assertEquals(10, predicateValue(query.block(0), 0));
    assertEquals(
        SqlScalarExpression.NULL,
        query.block(0).wherePredicates().programOperator(
            2, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
    assertEquals(SqlTypeDescriptor.DATE, predicateProgramDescriptor(
        query.block(0).wherePredicates(),
        2,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT));
    assertText("Aé😀", query.block(1), query.block(1).projectionExpression(0).operand(0));
    assertEquals(
        SqlTypeDescriptor.varchar(8),
        query.block(1).projectionExpression(0).typeDescriptor(0));
    assertEquals(20, predicateValue(query.block(1), 0));
    assertEquals(30, query.block(2).projectionExpression(0).operand(0));
    assertEquals(40, predicateValue(query.block(2), 0));
    assertEquals(45, query.block(3).projectionExpression(0).operand(0));
    assertEquals(50, predicateValue(query.block(3), 0));

    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        parser.parseQuery(sql, TestParameters.fixed(SqlTypeDescriptor.BIGINT, 1), query, command));
    TestParameters extra = new TestParameters(
        new int[] {
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.varchar(8),
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.DATE,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT
        },
        new long[] {10, 0, 20, 0, 30, 40, 45, 50, 60},
        new boolean[] {false, false, false, true, false, false, false, false, false},
        new String[] {null, "Aé😀", null, null, null, null, null, null, null});
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        parser.parseQuery(sql, extra, query, command));
  }

  @Test
  void parsesExistencePredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM lookup WHERE lookup.region=7) ORDER BY id",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertEquals(1, query.edgeCount());
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_EXISTS,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertFalse(query.block(0).wherePredicates().leafNegated(0));
    assertName("accounts", command.tableName());
    assertName("id", command.orderBy().name(0));
    assertName("lookup", query.block(query.edgeChild(0)).tableName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE NOT EXISTS "
                + "(SELECT id FROM lookup WHERE lookup.region=7)",
            query,
            command));
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_EXISTS,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertBooleanNotOverLeaf(query.block(0).wherePredicates(), 0);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    SqlCommand correlated = query.block(query.edgeChild(0));
    assertTrue(isColumnPredicate(correlated, 0));
    assertName("regions", predicateTableName(correlated, 0));
    assertName("id", predicateColumnName(correlated, 0));
    assertName("accounts", predicateValueTableName(correlated, 0));
    assertName("region", predicateValueColumnName(correlated, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM accounts AS a WHERE EXISTS "
                + "(SELECT b.id FROM accounts b "
                + "WHERE b.region=a.region AND b.id=3)",
            query,
            command));
    assertName("accounts", command.tableName());
    assertName("a", command.tableAlias());
    correlated = query.block(query.edgeChild(0));
    assertName("accounts", correlated.tableName());
    assertName("b", correlated.tableAlias());
    assertName("b", predicateTableName(correlated, 0));
    assertName("a", predicateValueTableName(correlated, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedExistenceQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertNestedSubqueryChain(query, 3, SqlQuery.SUBQUERY_EXISTS,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedExistenceQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertNestedSubqueryChain(query, 32, SqlQuery.SUBQUERY_EXISTS,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedExistenceQuery(33), query, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(siblingExistenceQuery(8), query, command));
    assertEquals(8, query.edgeCount());
    assertEquals(9, query.blockCount());
    assertEquals(8, query.block(0).wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(siblingExistenceQuery(9), query, command));
    assertEquals(9, query.edgeCount());
    assertEquals(10, query.blockCount());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(siblingExistenceQuery(1), query, command));
  }

  @Test
  void parsesMembershipPredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region=7 AND balance IN "
                + "(SELECT balance FROM lookup WHERE lookup.id=9)",
            query,
            command));
    assertEquals(1, query.edgeCount());
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_MEMBERSHIP,
        0,
        1,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertFalse(query.block(0).wherePredicates().leafNegated(1));
    assertName("balance", predicateColumnName(command, 1));
    assertName("lookup", query.block(query.edgeChild(0)).tableName());
    assertName("balance", query.block(query.edgeChild(0)).firstColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE id NOT IN "
                + "(SELECT NULL FROM lookup WHERE id=9)",
            query,
            command));
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_MEMBERSHIP,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertTrue(query.block(0).wherePredicates().leafNegated(0));
    assertTrue(query.block(query.edgeChild(0)).isNullProjection(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region NOT IN "
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    SqlCommand correlated = query.block(query.edgeChild(0));
    assertTrue(query.block(0).wherePredicates().leafNegated(query.edgeLeaf(0)));
    assertTrue(isColumnPredicate(correlated, 0));
    assertName("regions", predicateTableName(correlated, 0));
    assertName("id", predicateColumnName(correlated, 0));
    assertName("accounts", predicateValueTableName(correlated, 0));
    assertName("region", predicateValueColumnName(correlated, 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedMembershipQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertNestedSubqueryChain(query, 3, SqlQuery.SUBQUERY_MEMBERSHIP,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedMembershipQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertNestedSubqueryChain(query, 32, SqlQuery.SUBQUERY_MEMBERSHIP,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedMembershipQuery(33), query, command));
  }

  @Test
  void parsesMixedNestedPredicateForms() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM accounts WHERE id IN "
                + "(SELECT id FROM accounts WHERE id="
                + "(SELECT id FROM accounts WHERE id=1)))",
            query,
            command));
    assertEquals(4, query.blockCount());
    assertEquals(3, query.edgeCount());
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_EXISTS,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertSubqueryEdge(
        query,
        1,
        SqlQuery.SUBQUERY_MEMBERSHIP,
        1,
        0,
        2,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertSubqueryEdge(
        query,
        2,
        SqlQuery.SUBQUERY_SCALAR,
        2,
        0,
        3,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON);
    assertEquals(4, query.nestedPlanDepth());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM accounts a WHERE EXISTS "
                + "(SELECT b.id FROM accounts b WHERE b.id IN "
                + "(SELECT c.id FROM accounts c WHERE c.id=a.id))",
            query,
            command));
    assertEquals(3, query.blockCount());
    assertSubqueryEdge(
        query,
        0,
        SqlQuery.SUBQUERY_EXISTS,
        0,
        0,
        1,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS);
    assertSubqueryEdge(
        query,
        1,
        SqlQuery.SUBQUERY_MEMBERSHIP,
        1,
        0,
        2,
        SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP);
    assertName("a", command.tableAlias());
    assertName("b", query.block(1).tableAlias());
    assertName("c", query.block(2).tableAlias());
    assertTrue(isColumnPredicate(query.block(2), 0));
    assertName("a", predicateValueTableName(query.block(2), 0));
    assertName("id", predicateValueColumnName(query.block(2), 0));
  }

  @Test
  void keepsExcludedChildPipelinesFailClosed() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    String[] children = {
        "SELECT COUNT(*) FROM lookup",
        "SELECT id,COUNT(*) FROM lookup GROUP BY id",
        "SELECT DISTINCT id FROM lookup",
        "SELECT id FROM lookup ORDER BY id",
        "SELECT d.id FROM (SELECT id FROM lookup) d"
    };
    for (String child : children) {
      assertEquals(
          StatusCode.FEATURE_NOT_SUPPORTED,
          parser.parseQuery(
              "SELECT id FROM accounts WHERE id=(" + child + ")",
              query,
              command));
      assertEquals(0, query.blockCount());
      assertEquals(0, query.edgeCount());
    }
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE id=(SELECT id FROM lookup)",
            query,
            command));
  }

  @Test
  void admitsJoinedPredicateBlocksButKeepsExcludedChildShapesFailClosed() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM accounts a JOIN lookup b ON a.id=b.id "
                + "WHERE EXISTS (SELECT i.id FROM lookup i WHERE i.id=b.id)",
            query,
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, query.block(0).type());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT o.id FROM accounts o WHERE EXISTS "
                + "(SELECT b.id FROM accounts a JOIN lookup b ON a.id=b.id "
                + "WHERE b.id=o.id)",
            query,
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, query.block(1).type());
    assertTrue(query.isBlockPipeline());

    String[] excluded = {
        "SELECT a.id FROM accounts a JOIN lookup b ON a.id=b.id ORDER BY a.id",
        "SELECT a.id,b.id FROM accounts a JOIN lookup b ON a.id=b.id",
        "SELECT * FROM accounts a JOIN lookup b ON a.id=b.id",
        "SELECT x.id FROM (SELECT a.id FROM accounts a "
            + "JOIN lookup b ON a.id=b.id) x"
    };
    for (String child : excluded) {
      assertEquals(
          StatusCode.FEATURE_NOT_SUPPORTED,
          parser.parseQuery(
              "SELECT id FROM accounts WHERE EXISTS (" + child + ")",
              query,
              command),
          child);
      assertEquals(0, query.blockCount());
      assertEquals(0, query.edgeCount());
    }
  }

  private static String nestedQuery(int blocks) {
    String query = "SELECT id FROM accounts";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT d" + depth + ".id FROM (" + query + ") d" + depth;
    }
    return query;
  }

  private static String nestedScalarQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE id=(" + query + ")";
    }
    return query;
  }

  private static String nestedExistenceQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE EXISTS (" + query + ")";
    }
    return query;
  }

  private static String nestedMembershipQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE id IN (" + query + ")";
    }
    return query;
  }

  private static String siblingExistenceQuery(int edges) {
    StringBuilder query = new StringBuilder("SELECT id FROM accounts WHERE ");
    for (int edge = 0; edge < edges; edge++) {
      if (edge > 0) query.append(" AND ");
      query.append("EXISTS (SELECT id FROM lookup WHERE id=").append(edge).append(')');
    }
    return query.toString();
  }


  private static void assertNestedSubqueryChain(
      SqlQuery query, int blocks, int kind, int leafTest) {
    assertEquals(blocks - 1, query.edgeCount());
    assertEquals(blocks, query.nestedPlanDepth());
    assertEquals(1, query.blockDepth(0));
    for (int edge = 0; edge < blocks - 1; edge++) {
      assertSubqueryEdge(query, edge, kind, edge, 0, edge + 1, leafTest);
      SqlBooleanPredicateProgram predicates = query.block(edge).wherePredicates();
      assertFalse(predicates.leafNegated(0));
      if (kind == SqlQuery.SUBQUERY_SCALAR) {
        assertEquals(SqlComparison.EQUAL, predicates.comparison(0));
      }
    }
  }
}
