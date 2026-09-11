package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertBooleanNotOverLeaf;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertHavingPostfix;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertMutationPostfix;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertPostfix;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertText;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingComparison;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingMemberCount;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingMembershipHasNull;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingNodeCount;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingOperand;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.isColumnPredicate;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipCount;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipHasNull;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateColumnName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateComparison;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateDescriptor;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateExpression;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateLower;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateProgramDescriptor;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateProgramValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateTableName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateUpper;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValueColumnName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValueTableName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertSubqueryEdge;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlParserParameterTextSupport.TestParameters;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlParserTest {
  private static volatile long allocationGuard;

  @Test
  void lowersDirectAggregateOverJoinIntoTwoCardinalityStages() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
                + "INNER JOIN stock s ON s.s_w_id=ol.ol_supply_w_id "
                + "AND s.s_i_id=ol.ol_i_id WHERE ol.ol_w_id=1 "
                + "AND ol.ol_d_id=2 AND s.s_quantity<20",
            query,
            command));

    assertTrue(query.isBlockPipeline());
    assertEquals(2, query.sourceBlockCount());
    assertEquals(SqlCommandType.COUNT_DISTINCT, command.type());
    assertEquals(SqlCommandType.COUNT_DISTINCT, query.block(0).type());
    assertEquals(SqlCommandType.JOIN_SCAN, query.block(1).type());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT COUNT(DISTINCT s.s_i_id),SUM(s.s_i_id) FROM order_line ol "
                + "INNER JOIN stock s ON s.s_i_id=ol.ol_i_id",
            query,
            command));
    assertEquals(2, query.block(0).aggregateInvocationCount());
    assertEquals(2, query.block(0).aggregateOutputCount());
    assertEquals(2, query.block(1).columnCount());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT s.s_i_id,ol.ol_i_id,COUNT(DISTINCT s.s_i_id),SUM(s.s_i_id) "
                + "FROM order_line ol INNER JOIN stock s ON s.s_i_id=ol.ol_i_id "
                + "GROUP BY s.s_i_id,ol.ol_i_id",
            query,
            command));
    assertEquals(SqlCommandType.GROUP_COUNT_DISTINCT, query.block(0).type());
    assertEquals(SqlCommandType.JOIN_SCAN, query.block(1).type());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT s.s_i_id,COUNT(*) AS n,SUM(s.s_i_id) AS total "
                + "FROM order_line ol INNER JOIN stock s ON s.s_i_id=ol.ol_i_id "
                + "GROUP BY s.s_i_id ORDER BY n DESC LIMIT 1",
            query,
            command));
    assertEquals(0, query.block(0).groupOperandProjection(0));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT COUNT(*) FROM left_rows l JOIN right_rows r ON l.id=r.left_id "
                + "GROUP BY l.a,r.b HAVING b=100",
            query,
            command));
    assertEquals(2, query.block(0).groupExpressionCount());
    assertEquals(3, query.block(1).columnCount());
    assertEquals(1, query.block(0).groupOperandProjection(0));
    assertEquals(2, query.block(0).groupOperandProjection(1));

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT COUNT(*) FROM order_line ol INNER JOIN stock s "
                + "ON s.s_i_id=ol.ol_i_id LIMIT 0",
            query,
            command));
    assertEquals(0, command.rowLimit());
    assertEquals(Long.MAX_VALUE, query.block(1).rowLimit());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT s.s_i_id,COUNT(*) FROM order_line ol INNER JOIN stock s "
                + "ON s.s_i_id=ol.ol_i_id GROUP BY s.s_i_id LIMIT 1",
            query,
            command));
    assertEquals(1, command.rowLimit());
    assertEquals(Long.MAX_VALUE, query.block(1).rowLimit());
  }

  @Test
  void parsesOnlyLockableSelectForUpdateTailsAndRetainsState() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlCommand copied = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT d_next_o_id FROM district "
                + "WHERE d_w_id=1 AND d_id=2 ORDER BY d_next_o_id LIMIT 1 FOR UPDATE",
            command));
    assertTrue(command.isSelectForUpdate());
    assertEquals(1, command.rowLimit());
    assertTrue(command.isOrdered());
    assertEquals(StatusCode.OK, copied.copyBlockFrom(command));
    assertTrue(copied.isSelectForUpdate());
    command.reset();
    assertFalse(command.isSelectForUpdate());

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM district FOR", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM district FOR SHARE", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM district FOR UPDATE LIMIT 1", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM district FOR UPDATE NOWAIT", command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse("SELECT DISTINCT id FROM district FOR UPDATE", command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse("SELECT COUNT(*) FROM district FOR UPDATE", command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse(
            "SELECT d.id FROM district d JOIN warehouse w ON d.d_w_id=w.id FOR UPDATE",
            command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT x.id FROM (SELECT id FROM district) x FOR UPDATE", query, command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT id FROM district WHERE id IN "
                + "(SELECT id FROM warehouse FOR UPDATE)",
            query,
            command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery("EXPLAIN SELECT id FROM district FOR UPDATE", query, command));
  }

  @Test
  void ownsBoundedOnProgramsAcrossCopyResetAndMarkers() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlCommand copied = new SqlCommand();
    TestParameters parameters = new TestParameters(
        new int[] {
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT
        },
        new long[] {7, 1, 10},
        new boolean[] {false, false, false},
        new String[] {null, null, null});
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key,r.code FROM accounts a LEFT JOIN regions r "
                + "ON ?=a.region AND r.id+?=a.key AND r.label='Aé😀' "
                + "WHERE r.code>?",
            parameters,
            command));
    assertTrue(command.joinChain().isLeft(0));
    assertEquals(3, command.joinChain().onPredicates(0).leafCount());
    assertEquals(7, predicateProgramValue(
        command.joinChain().onPredicates(0), 0,
        SqlBooleanPredicateProgram.PROGRAM_LEFT));
    assertEquals(1, command.joinChain().onPredicates(0).programOperand(
        1, SqlBooleanPredicateProgram.PROGRAM_LEFT, 1));
    assertEquals(10, predicateValue(command, 0));
    copied.copyQueryFrom(command);
    assertTrue(copied.joinChain().isLeft(0));
    assertName("accounts", copied.tableName());
    assertName("regions", copied.joinChain().tableName(1));
    assertName("a", copied.tableAlias());
    assertName("r", copied.joinChain().alias(1));
    assertEquals(3, copied.joinChain().onPredicates(0).leafCount());
    long text = copied.joinChain().onPredicates(0).programOperand(
        2, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    assertText("Aé😀", copied, text);
    command.reset();
    assertNull(command.joinChain());
    assertEquals(StatusCode.OK, parser.parse("SELECT id FROM moments", command));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key FROM accounts a JOIN regions r ON "
                + "a.key=1 AND a.region=2 AND r.id=3 AND r.code=4 "
                + "AND a.key=5 AND a.region=6 AND r.id=7 AND r.code=8",
            command));
    assertEquals(8, command.joinChain().onPredicates(0).leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key FROM accounts a JOIN regions r ON "
                + "a.key=1 AND a.region=2 AND r.id=3 AND r.code=4 "
                + "AND a.key=5 AND a.region=6 AND r.id=7 AND r.code=8 "
                + "AND a.key=9",
            command));
    assertEquals(9, command.joinChain().onPredicates(0).leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key FROM accounts a JOIN regions r ON "
                + "a.key+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1=r.id",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key FROM accounts a JOIN regions r ON a.key=r.id",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key AS account_key FROM accounts a "
                + "JOIN regions r ON a.key=r.id ORDER BY account_key",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key FROM accounts a JOIN regions r ON a.key=r.id "
                + "ORDER BY a.key",
            command));
    assertEquals("a", command.orderColumnTableName(0).toString());
    assertEquals("key", command.orderColumnName(0).toString());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key AS account_key FROM accounts a "
                + "JOIN regions r ON a.key=r.id ORDER BY account_key",
            command));
  }

  @Test
  void retainsAdmittedDeepestJoinArenaAndDiscardsRejectedTopology() throws Exception {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    StringBuilder members = new StringBuilder();
    for (int value = 1; value <= 256; value++) {
      if (value > 1) members.append(',');
      members.append(value);
    }
    String join = "SELECT a.key FROM accounts a JOIN regions r ON r.id IN ("
        + members + ")";
    assertEquals(StatusCode.OK, parser.parse(join, command));
    java.lang.reflect.Field joins = SqlCommand.class.getDeclaredField("joinChain");
    joins.setAccessible(true);
    assertTrue(joins.get(command) != null);
    command.reset();
    assertTrue(joins.get(command) != null);
    assertNull(command.joinChain());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.key FROM (" + join + ") d", query, command));
    assertTrue(query.isBlockPipeline());
    java.lang.reflect.Field blocks = SqlQuery.class.getDeclaredField("blocks");
    blocks.setAccessible(true);
    SqlCommand[] owned = (SqlCommand[]) blocks.get(query);
    assertNull(joins.get(owned[0]));
    assertTrue(joins.get(owned[1]) != null);
    query.reset();
    assertNull(joins.get(owned[0]));
    assertTrue(joins.get(owned[1]) != null);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.key FROM (SELECT a.key FROM accounts a JOIN regions r "
                + "ON missing.id=a.key) d",
            query,
            command));
    for (SqlCommand block : owned) assertNull(joins.get(block));
    assertEquals(StatusCode.OK, parser.parse("SELECT id FROM moments", command));
  }

  @Test
  void growsJoinChainPastEightRoles() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlCommand copied = new SqlCommand();
    String eight = "SELECT a.id,h.id FROM a a "
        + "JOIN b b ON a.id=b.id "
        + "LEFT JOIN c c ON b.id=c.id "
        + "INNER JOIN d d ON c.id=d.id "
        + "JOIN e e ON d.id=e.id "
        + "LEFT OUTER JOIN f f ON e.id=f.id "
        + "JOIN g g ON f.id=g.id "
        + "JOIN h h ON g.id=h.id WHERE h.id=a.id";
    assertEquals(StatusCode.OK, parser.parse(eight, command));
    SqlJoinChain chain = command.joinChain();
    assertEquals(8, chain.roleCount());
    assertEquals(7, chain.stageCount());
    assertName("a", chain.tableName(0));
    assertName("h", chain.tableName(7));
    assertEquals(SqlJoinChain.LEFT, chain.joinKind(1));
    assertEquals(SqlJoinChain.LEFT, chain.joinKind(4));
    for (int stage = 0; stage < chain.stageCount(); stage++) {
      assertEquals(stage + 1, chain.rightRole(stage));
      assertEquals(1, chain.onPredicates(stage).leafCount());
    }
    copied.copyQueryFrom(command);
    assertEquals(8, copied.joinChain().roleCount());
    assertName("h", copied.joinChain().alias(7));
    assertEquals(1, copied.joinChain().onPredicates(6).leafCount());
    command.reset();
    assertNull(command.joinChain());
    assertEquals(StatusCode.OK, parser.parse("SELECT m.id FROM moments m", command));
    assertNull(command.joinChain());

    assertEquals(
        StatusCode.OK,
        parser.parse(eight.replace(" WHERE", " JOIN i i ON h.id=i.id WHERE"), command));
    assertEquals(9, command.joinChain().roleCount());
    assertEquals(StatusCode.OK, parser.parse("SELECT m.id FROM moments m", command));
  }

  @Test
  void classifiesJoinFormsAndNamespacesExactly() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT a.id,b.id FROM t a JOIN t b ON id=id", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT x.id FROM a x JOIN b x ON x.id=x.id", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a JOIN b a ON a.id=a.id", command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse("SELECT a.id FROM a RIGHT JOIN b ON a.id=b.id", command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse("SELECT a.id FROM a JOIN b USING (id)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a LEFT b ON a.id=b.id", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a OUTER JOIN b ON a.id=b.id", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a AS JOIN b ON a.id=b.id", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a AS WHERE", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a AS ON", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a ON", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a USING", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT a.id FROM a JOIN b AS WHERE ON a.id=b.id", command));
    assertEquals(StatusCode.OK, parser.parse("SELECT m.id FROM moments m", command));
  }

  @Test
  void copiesTypedParametersAndPreservesNullSemantics() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    TestParameters parameters = new TestParameters(
        new int[] {
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.varchar(8),
            SqlTypeDescriptor.DATE
        },
        new long[] {7, 0, 0},
        new boolean[] {false, false, true},
        new String[] {null, "Aé😀", null});

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO moments(id,note,day) VALUES(?,?,?)",
            parameters,
            command));
    parameters.texts[1] = "changed";
    assertEquals(7, command.insertValue(0, 0));
    assertEquals(SqlTypeDescriptor.BIGINT, command.insertTypeDescriptor(0, 0));
    assertText("Aé😀", command, command.insertValue(0, 1));
    assertTrue(command.insertIsNull(0, 2));
    assertEquals(SqlTypeDescriptor.DATE, command.insertTypeDescriptor(0, 2));

    parameters = new TestParameters(
        new int[] {SqlTypeDescriptor.DATE},
        new long[] {0},
        new boolean[] {true},
        new String[] {null});
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE day IN (?)", parameters, command));
    assertEquals(1, membershipCount(command, 0));
    assertTrue(membershipHasNull(command, 0));
    assertEquals(SqlTypeDescriptor.DATE, predicateDescriptor(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE day=?", parameters, command));

    parameters = new TestParameters(
        new int[] {SqlTypeDescriptor.BIGINT},
        new long[] {0},
        new boolean[] {true},
        new String[] {null});
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE moments SET amount=? WHERE id=1", parameters, command));
    assertTrue(command.updateIsNull(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE moments SET amount=ABS(amount) WHERE id=1", command));
    assertFalse(command.updateIsNull(0));
    parameters = TestParameters.fixed(SqlTypeDescriptor.BIGINT, 9);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE moments SET amount=?+1 WHERE id=1", parameters, command));
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(9, command.mutationExpressionOperand(command.updateExpression(0), 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO moments VALUES(?+1,2)", parameters, command));
    assertTrue(command.insertHasExpression(0, 0));
    parameters = new TestParameters(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new long[] {9, 10},
        new boolean[] {false, false},
        new String[] {null, null});
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        parser.parse(
            "INSERT INTO moments VALUES(?+1,2)", parameters, command));
  }

  @Test
  void appliesParameterCountsAcrossQueryTopologiesWithoutConsumingTextMarkers() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    TestParameters none = TestParameters.empty();
    TestParameters one = TestParameters.fixed(SqlTypeDescriptor.BIGINT, 1);

    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO notes(id,text) VALUES(1,'?')", none, command));
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        parser.parse("INSERT INTO notes(id) VALUES(?)", none, command));
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        parser.parse("INSERT INTO notes(id) VALUES(1)", one, command));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse(
            "CREATE TABLE blocked(id BIGINT DEFAULT ?)", one, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT id FROM notes WHERE id=?) d",
            one,
            query,
            command));
    assertEquals(1, predicateValue(query.block(1), 0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM notes WHERE id IN (SELECT id FROM notes WHERE id=?)",
            one,
            query,
            command));
    assertEquals(1, predicateValue(query.block(1), 0));
    TestParameters ordered = new TestParameters(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new long[] {7, 9},
        new boolean[] {false, false},
        new String[] {null, null});
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT id FROM notes WHERE id=?) d WHERE d.id=?",
            ordered,
            query,
            command));
    assertEquals(9, predicateValue(query.block(0), 0));
    assertEquals(7, predicateValue(query.block(1), 0));
  }

  @Test
  void parsesBoundedExactScalarExpressions() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT CAST(1.25 AS DECIMAL(4,1))", command));
    assertEquals(SqlCommandType.SCALAR_EXPRESSION, command.type());
    SqlScalarExpression expression = command.scalarExpression();
    assertTrue(expression.isAvailable());
    assertEquals(2, expression.nodeCount());
    assertEquals(SqlScalarExpression.LITERAL, expression.operator(0));
    assertEquals(125, expression.operand(0));
    assertEquals(SqlTypeDescriptor.decimal(3, 2), expression.typeDescriptor(0));
    assertEquals(SqlScalarExpression.CAST, expression.operator(1));
    assertEquals(SqlTypeDescriptor.decimal(4, 1), expression.typeDescriptor(1));
    assertEquals(SqlTypeDescriptor.decimal(4, 1), expression.resultTypeDescriptor());

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT 1.20+2.345*2.0", command));
    assertEquals(5, command.scalarExpression().nodeCount());
    assertEquals(
        SqlTypeDescriptor.decimal(7, 4),
        command.scalarExpression().resultTypeDescriptor());
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse("SELECT TRUE+1", command));
    assertFalse(command.isAvailable());
  }

  @Test
  void parsesApproximateArithmeticAndScaleFunctions() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    String[] expressions = {
        "CAST(5.5 AS REAL)+CAST(2.0 AS REAL)",
        "CAST(5.5 AS REAL)-CAST(2.0 AS DOUBLE PRECISION)",
        "CAST(5.5 AS REAL)*CAST(2.0 AS DOUBLE PRECISION)",
        "CAST(5.5 AS REAL)/CAST(2.0 AS DOUBLE PRECISION)",
        "CAST(5.5 AS REAL)%CAST(2.0 AS DOUBLE PRECISION)",
        "ROUND(CAST(1.256 AS DOUBLE PRECISION),2)",
        "TRUNCATE(CAST(-1.259 AS REAL),2)"
    };
    int[] descriptors = {
        SqlTypeDescriptor.REAL,
        SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.REAL
    };
    for (int index = 0; index < expressions.length; index++) {
      assertEquals(StatusCode.OK, parser.parse("SELECT " + expressions[index], command));
      assertEquals(descriptors[index], command.scalarExpression().resultTypeDescriptor());
    }
  }

  @Test
  void decimalLiteralPrecisionIgnoresLeadingIntegerZeros() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    String fractionalDigits = "9".repeat(38);
    BigInteger unscaled = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

    assertEquals(StatusCode.OK,
        parser.parse("SELECT 0." + fractionalDigits, command));
    SqlScalarExpression expression = command.scalarExpression();
    assertEquals(SqlTypeDescriptor.decimal(38, 38), expression.typeDescriptor(0));
    assertEquals(unscaled.longValue(), expression.operand(0));
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(), expression.operandHigh(0));

    assertEquals(StatusCode.OK,
        parser.parse("SELECT -0." + fractionalDigits, command));
    assertEquals(SqlTypeDescriptor.decimal(38, 38), expression.typeDescriptor(0));
    assertEquals(unscaled.negate().longValue(), expression.operand(0));
    assertEquals(
        unscaled.negate().shiftRight(Long.SIZE).longValue(), expression.operandHigh(0));

    assertEquals(StatusCode.OK,
        parser.parse("SELECT 0000." + fractionalDigits, command));
    assertEquals(SqlTypeDescriptor.decimal(38, 38), expression.typeDescriptor(0));
    assertEquals(StatusCode.OK, parser.parse("SELECT 0000012.3400", command));
    assertEquals(SqlTypeDescriptor.decimal(6, 4), expression.typeDescriptor(0));
    assertEquals(123_400, expression.operand(0));

    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        parser.parse("SELECT 0." + "1".repeat(39), command));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        parser.parse("SELECT " + "1".repeat(39) + ".0", command));
  }

  @Test
  void parsesScalarTemporalExtractAndDateArithmetic() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(YEAR FROM DATE '2024-02-29')", command));
    SqlScalarExpression expression = command.scalarExpression();
    assertEquals(2, expression.nodeCount());
    assertEquals(SqlScalarExpression.EXTRACT, expression.operator(1));
    assertEquals(LocalTemporal.EXTRACT_YEAR, expression.operand(1));
    assertEquals(SqlTypeDescriptor.BIGINT, expression.resultTypeDescriptor());

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(SECOND FROM TIME '12:34:56.123')", command));
    assertEquals(
        SqlTypeDescriptor.decimal(5, 3),
        command.scalarExpression().resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(SECOND FROM CURRENT_TIMESTAMP)", command));
    assertEquals(SqlScalarExpression.CURRENT_TIMESTAMP, expression.operator(0));
    assertEquals(SqlTypeDescriptor.decimal(8, 6), expression.resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT EXTRACT(TIMEZONE_MINUTE FROM TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00+01:00')",
            command));
    assertEquals(SqlTypeDescriptor.BIGINT, expression.resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT EXTRACT(DAY FROM DATE '2024-02-28'+1)", command));
    assertEquals(SqlScalarExpression.ADD, expression.operator(2));
    assertEquals(SqlScalarExpression.EXTRACT, expression.operator(3));
    assertEquals(SqlTypeDescriptor.BIGINT, expression.resultTypeDescriptor());

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT DATE '2024-02-29'+1", command));
    assertEquals(SqlScalarExpression.ADD, expression.operator(2));
    assertEquals(SqlTypeDescriptor.DATE, expression.resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT DATE '2024-03-01'-1", command));
    assertEquals(SqlTypeDescriptor.DATE, expression.resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DATE '2024-03-01'-DATE '2024-02-29'", command));
    assertEquals(SqlTypeDescriptor.BIGINT, expression.resultTypeDescriptor());

    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse("SELECT EXTRACT(YEAR FROM TIME '12:34:56')", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT EXTRACT(WEEK FROM DATE '2024-02-29')", command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse(
            "SELECT EXTRACT(HOUR FROM TIMESTAMP '2024-01-01 00:00:00' "
                + "AT TIME ZONE 'UTC')",
            command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse(
            "SELECT CAST(TIMESTAMP '2024-01-01 00:00:00' "
                + "AT TIME ZONE 'UTC' AS TIMESTAMP)",
            command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse("SELECT DATE '2024-02-29'+1.0", command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse("SELECT 1+DATE '2024-02-29'", command));
  }

  @Test
  void carriesMultipleUnboundRowProjectionProgramsAndAliases() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT m.id AS event_id, EXTRACT(YEAR FROM m.observed) AS seen_year, "
                + "m.day+1 tomorrow, m.day-m.day AS age, "
                + "CAST(m.observed AS VARCHAR(26)) AS rendered, NULL AS absent "
                + "FROM moments m ORDER BY tomorrow",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(6, command.columnCount());
    assertName("event_id", command.columnAlias(0));
    assertName("seen_year", command.columnAlias(1));
    assertName("tomorrow", command.columnAlias(2));
    assertName("rendered", command.columnAlias(4));
    assertName("tomorrow", command.orderColumnName());

    SqlScalarExpression direct = command.projectionExpression(0);
    assertTrue(direct.isDirectColumnReference());
    assertEquals(SqlScalarExpression.COLUMN, direct.operator(0));
    int id = command.directProjectionSymbol(0);
    assertName("id", command.projectionSymbolName(id));
    assertName("m", command.projectionSymbolTable(id));
    assertName("id", command.columnName(0));

    SqlScalarExpression extract = command.projectionExpression(1);
    assertEquals(2, extract.nodeCount());
    assertEquals(SqlScalarExpression.COLUMN, extract.operator(0));
    assertEquals(SqlScalarExpression.EXTRACT, extract.operator(1));
    assertEquals(LocalTemporal.EXTRACT_YEAR, extract.operand(1));
    assertEquals(0, extract.resultTypeDescriptor());

    SqlScalarExpression addition = command.projectionExpression(2);
    assertEquals(3, addition.nodeCount());
    assertEquals(SqlScalarExpression.COLUMN, addition.operator(0));
    assertEquals(SqlScalarExpression.LITERAL, addition.operator(1));
    assertEquals(SqlScalarExpression.ADD, addition.operator(2));
    assertEquals(0, addition.resultTypeDescriptor());

    SqlScalarExpression difference = command.projectionExpression(3);
    assertEquals(difference.operand(0), difference.operand(1));
    assertEquals(SqlScalarExpression.SUBTRACT, difference.operator(2));

    SqlScalarExpression cast = command.projectionExpression(4);
    assertEquals(SqlScalarExpression.CAST, cast.operator(1));
    assertEquals(SqlTypeDescriptor.varchar(26), cast.typeDescriptor(1));
    assertEquals(SqlTypeDescriptor.varchar(26), cast.resultTypeDescriptor());
    assertTrue(command.projectionExpression(5).isNullLiteral());
    assertTrue(command.isNullProjection(5));
  }

  @Test
  void carriesComposableRowAtTimeZonePostfixPrograms() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT observed AT TIME ZONE 'UTC' AS direct_zone, "
                + "CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS current_zone, "
                + "CAST(observed AS TIMESTAMP(3)) AT TIME ZONE 'UTC' AS cast_zone, "
                + "(observed) AT TIME ZONE 'UTC' AS parenthesized_zone, "
                + "EXTRACT(HOUR FROM observed AT TIME ZONE 'UTC') AS wall_hour "
                + "FROM moments",
            command));
    assertEquals(5, command.columnCount());
    assertPostfix(command.projectionExpression(0),
        SqlScalarExpression.COLUMN, SqlScalarExpression.AT_TIME_ZONE);
    assertPostfix(command.projectionExpression(1),
        SqlScalarExpression.CURRENT_TIMESTAMP, SqlScalarExpression.AT_TIME_ZONE);
    assertPostfix(command.projectionExpression(2),
        SqlScalarExpression.COLUMN, SqlScalarExpression.CAST,
        SqlScalarExpression.AT_TIME_ZONE);
    assertPostfix(command.projectionExpression(3),
        SqlScalarExpression.COLUMN, SqlScalarExpression.AT_TIME_ZONE);
    assertPostfix(command.projectionExpression(4),
        SqlScalarExpression.COLUMN, SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.EXTRACT);
    assertEquals(0, command.projectionExpression(0).resultTypeDescriptor());
    assertEquals(SqlTypeDescriptor.timestamp(6),
        command.projectionExpression(1).resultTypeDescriptor());
    assertEquals(SqlTypeDescriptor.timestampWithTimeZone(3),
        command.projectionExpression(2).resultTypeDescriptor());
    assertEquals(0, command.projectionExpression(4).resultTypeDescriptor());

    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        parser.parse("SELECT 1 AT TIME ZONE 'UTC' FROM moments", command));
    assertFalse(command.isAvailable());
  }

  @Test
  void carriesSelectedComputedSortDistinctAndExactGroupKeys() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id, day+1 AS tomorrow FROM moments ORDER BY tomorrow DESC",
            command));
    assertTrue(command.isDescendingOrder());
    assertName("tomorrow", command.orderColumnName());
    assertPostfix(
        command.projectionExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT EXTRACT(DAY FROM observed) AS seen_day FROM moments "
                + "ORDER BY seen_day",
            command));
    assertEquals(SqlCommandType.DISTINCT_SCAN, command.type());
    assertName("seen_day", command.columnAlias(0));
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT EXTRACT(DAY FROM observed) FROM moments",
            command));

    String grouped = "SELECT observed AT TIME ZONE 'UTC' AS instant, "
        + "MAX(CAST(day AS TIMESTAMP(3))) FROM moments GROUP BY "
        + "observed AT TIME ZONE 'UTC' HAVING "
        + "MAX(CAST(day AS TIMESTAMP(3)))>TIMESTAMP '2024-01-01 00:00:00' "
        + "ORDER BY instant";
    assertEquals(StatusCode.OK, parser.parse(grouped, command));
    assertEquals(SqlCommandType.GROUP_MAX, command.type());
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.AT_TIME_ZONE);
    assertPostfix(
        command.projectionExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            grouped.replace(
                "GROUP BY observed AT TIME ZONE 'UTC'", "GROUP BY instant"),
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            grouped.replace("GROUP BY observed AT TIME ZONE 'UTC'",
                "GROUP BY observed AT TIME ZONE '+01:00'"),
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            grouped.replace("GROUP BY observed AT TIME ZONE 'UTC'",
                "GROUP BY CAST(observed AS TIMESTAMP(6))"),
            command));
  }

  @Test
  void carriesBoundedAggregateSetAndGeneralHavingPredicates() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(observed) FROM moments GROUP BY category "
                + "HAVING EXTRACT(YEAR FROM MAX(observed) AT TIME ZONE 'UTC')=2024",
            command));
    assertHavingPostfix(
        command,
        0,
        SqlScalarExpression.AGGREGATE_VALUE,
        SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.EXTRACT);
    assertText(
        "UTC", command, havingOperand(command, 0, 1));
    SqlCommand copied = new SqlCommand();
    copied.copyQueryFrom(command);
    assertHavingPostfix(
        copied,
        0,
        SqlScalarExpression.AGGREGATE_VALUE,
        SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.EXTRACT);
    assertText(
        "UTC", copied, havingOperand(copied, 0, 1));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, SUM(balance) FROM accounts GROUP BY category "
                + "HAVING ROUND(ABS(SUM(balance))*2/3,0)%5=0",
            command));
    assertHavingPostfix(
        command,
        0,
        SqlScalarExpression.AGGREGATE_VALUE,
        SqlScalarExpression.ABSOLUTE,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.MULTIPLY,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.DIVIDE,
        SqlScalarExpression.ROUND,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.REMAINDER);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) FROM moments GROUP BY category "
                + "HAVING MAX(day)+1>=DATE '2024-03-01'",
            command));
    assertHavingPostfix(
        command,
        0,
        SqlScalarExpression.AGGREGATE_VALUE,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, COUNT(*) FROM moments GROUP BY category "
                + "HAVING COUNT(*)+1>2",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) FROM moments GROUP BY category "
                + "HAVING MAX(day)>=DATE '2024-03-01'",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) AS latest FROM moments GROUP BY category "
                + "HAVING latest>=DATE '2024-03-01'",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, COUNT(*) FROM moments GROUP BY category "
                + "HAVING category BETWEEN 1 AND 2",
            command));
    StatusCode groupAliasStatus = parser.parse(
        "SELECT category AS c, COUNT(*) AS n FROM moments GROUP BY category "
            + "HAVING c BETWEEN 1 AND 2",
        command);
    assertName("c", command.columnAlias(0));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(StatusCode.OK, groupAliasStatus);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category AS c, COUNT(*) AS n FROM moments GROUP BY category "
                + "HAVING n>=2",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category AS c, COUNT(*) AS n FROM moments GROUP BY category "
                + "HAVING c BETWEEN 1 AND 2 AND n>=2",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) FROM moments GROUP BY category "
                + "HAVING category>1",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) FROM moments GROUP BY category "
                + "HAVING MAX(day)-MAX(day)>0",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(observed) FROM moments GROUP BY category "
                + "HAVING EXTRACT(YEAR FROM MIN(observed))=2024",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT category, MAX(day) FROM moments GROUP BY category "
                + "HAVING CAST(MAX(day) AS VARCHAR(10))='2024-03-01'",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT MAX(day) FROM moments HAVING MAX(day)>DATE '2024-01-01'",
            command));
    assertEquals(1, command.aggregateInvocationCount());
    assertEquals(1, command.aggregateOutputCount());

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(*) FROM moments HAVING COUNT(*)=0 OR "
                + "MIN(day) BETWEEN DATE '2024-01-01' AND NULL AND "
                + "MAX(day) NOT IN (DATE '2023-01-01',NULL)",
            command));
    assertEquals(3, command.booleanHavingPredicates().leafCount());
    assertEquals(
        SqlBooleanPredicateProgram.BOOLEAN_OR,
        command.booleanHavingPredicates().booleanOperator(
            command.booleanHavingPredicates().root()));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.booleanHavingPredicates().leafTest(1));
    assertEquals(
        SqlScalarExpression.NULL,
        command.booleanHavingPredicates().programOperator(
            1, SqlBooleanPredicateProgram.PROGRAM_UPPER, 0));
    assertTrue(command.booleanHavingPredicates().leafNegated(2));
    assertTrue(havingMembershipHasNull(command, 2));
  }

  @Test
  void boundsGeneralHavingAggregatePredicatesProgramsAndMembership() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    String eightInvocations =
        "SELECT COUNT(*) FROM moments HAVING COUNT(*)=0 AND COUNT(day)=0 AND "
            + "SUM(day)=0 AND AVG(day)=0 AND MIN(day)=0 AND MAX(day)=0 AND "
            + "MIN(observed)=0 AND MAX(observed)=0";
    assertEquals(StatusCode.OK, parser.parse(eightInvocations, command));
    assertEquals(8, command.aggregateInvocationCount());
    assertEquals(8, command.booleanHavingPredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(eightInvocations + " AND SUM(observed)=0", command));
    assertEquals(9, command.aggregateInvocationCount());

    StringBuilder ninthPredicate = new StringBuilder(
        "SELECT COUNT(*) FROM moments HAVING COUNT(*)=0");
    for (int predicate = 1;
        predicate < SqlBooleanPredicateProgram.MAXIMUM_LEAVES;
        predicate++) {
      ninthPredicate.append(" AND COUNT(*)=").append(predicate);
    }
    assertEquals(StatusCode.OK, parser.parse(ninthPredicate, command));
    assertEquals(SqlBooleanPredicateProgram.MAXIMUM_LEAVES, command.booleanHavingPredicates().leafCount());
    ninthPredicate.append(" AND COUNT(*)=9");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(ninthPredicate, command));

    StringBuilder maximumNodes = new StringBuilder("SELECT COUNT(*) FROM moments HAVING ");
    for (int node = 1;
        node < SqlBooleanPredicateProgram.MAXIMUM_DEPTH;
        node++) {
      maximumNodes.append("ABS(");
    }
    maximumNodes.append("COUNT(*)");
    for (int node = 1;
        node < SqlBooleanPredicateProgram.MAXIMUM_DEPTH;
        node++) {
      maximumNodes.append(')');
    }
    maximumNodes.append(">=0");
    assertEquals(StatusCode.OK, parser.parse(maximumNodes, command));
    assertEquals(
        SqlBooleanPredicateProgram.MAXIMUM_DEPTH,
        havingNodeCount(command, 0));
    maximumNodes.insert(
        "SELECT COUNT(*) FROM moments HAVING ".length(), "ABS(");
    maximumNodes.insert(maximumNodes.length() - 3, ')');
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(maximumNodes, command));

    StringBuilder maximumMembers = new StringBuilder(
        "SELECT COUNT(*) FROM moments HAVING COUNT(*) IN (");
    for (int member = 0; member < SqlBooleanPredicateProgram.MAXIMUM_MEMBERS; member++) {
      if (member > 0) maximumMembers.append(',');
      maximumMembers.append(member);
    }
    maximumMembers.append(')');
    assertEquals(StatusCode.OK, parser.parse(maximumMembers, command));
    assertEquals(
        SqlBooleanPredicateProgram.MAXIMUM_MEMBERS,
        havingMemberCount(command, 0));
    maximumMembers.insert(maximumMembers.length() - 1, ",256");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(maximumMembers, command));
  }

  @Test
  void carriesBoundedBooleanPredicatesAcrossAdmittedConsumers() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE id=7 AND "
                + "EXTRACT(DAY FROM observed AT TIME ZONE 'UTC')>=29",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(2, command.wherePredicates().leafCount());
    assertNull(predicateExpression(command, 0));
    SqlScalarExpression expression = predicateExpression(command, 1);
    assertPostfix(
        expression,
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.EXTRACT);
    assertTrue(expression.hasColumnReference());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 1));
    assertEquals(SqlTypeDescriptor.INTEGER, predicateDescriptor(command, 1));

    SqlCommand copied = new SqlCommand();
    copied.copyQueryFrom(command);
    assertPostfix(
        predicateExpression(copied, 1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.AT_TIME_ZONE,
        SqlScalarExpression.EXTRACT);

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT observed, COUNT(*) FROM moments "
                + "WHERE EXTRACT(DAY FROM observed)>=29 GROUP BY observed",
            command));
    assertEquals(SqlCommandType.GROUP_COUNT, command.type());
    assertPostfix(
        predicateExpression(command, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT observed FROM moments "
                + "WHERE EXTRACT(DAY FROM observed)>=29",
            command));
    assertEquals(SqlCommandType.DISTINCT_SCAN, command.type());
    assertPostfix(
        predicateExpression(command, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM moments WHERE (id)=7", command));
    assertNull(predicateExpression(command, 0));
    assertName("id", predicateColumnName(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE CAST(clock AS TIME(6)) BETWEEN "
                + "TIME '01:02:03' AND TIME '01:02:03.123456'",
            command));
    assertPostfix(
        predicateExpression(command, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(
        SqlTypeDescriptor.time(0),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_LOWER));
    assertEquals(
        SqlTypeDescriptor.time(6),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_UPPER));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE CAST(observed AS TIMESTAMP(6)) IN ("
                + "TIMESTAMP '2024-01-01 00:00:00',"
                + "TIMESTAMP '2024-01-01 00:00:00.123456')",
            command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_MEMBERSHIP,
        command.wherePredicates().leafTest(0));
    assertEquals(
        SqlTypeDescriptor.timestamp(6),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_LEFT));
    assertEquals(
        SqlTypeDescriptor.timestamp(0),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.timestamp(6),
        command.wherePredicates().memberDescriptor(0, 1));
    assertEquals(2, membershipCount(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE "
                + "CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) NOT IN ("
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00',NULL,"
                + "TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00.123456+00:00')",
            command));
    assertTrue(command.wherePredicates().leafNegated(0));
    assertTrue(membershipHasNull(command, 0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_LEFT));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(0),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        command.wherePredicates().memberDescriptor(0, 2));
    copied.copyQueryFrom(command);
    assertPostfix(
        predicateExpression(copied, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertTrue(copied.wherePredicates().leafNegated(0));
    assertEquals(3, membershipCount(copied, 0));
    assertTrue(membershipHasNull(copied, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(*) FROM moments WHERE day+0 BETWEEN "
                + "DATE '2024-01-01' AND DATE '2024-01-31'",
            command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertPostfix(
        predicateExpression(command, 0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed) IS UNKNOWN",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed)=id",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed) IN (day)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed) IN "
                + "(SELECT day FROM other_moments)",
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
    assertEquals(
        2,
        query.block(0).wherePredicates().programNodeCount(
            0, SqlBooleanPredicateProgram.PROGRAM_LEFT));
    assertEquals(
        SqlScalarExpression.EXTRACT,
        query.block(0).wherePredicates().programOperator(
            0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed)=29 OR id=1",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM moments WHERE EXTRACT(DAY FROM observed)=29 "
                + "AND EXTRACT(YEAR FROM observed)=2024",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE moments SET id=2 WHERE EXTRACT(DAY FROM observed)=29",
            command));
    assertTrue(command.wherePredicates().programNodeCount(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT) > 1);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE checked_day (id BIGINT PRIMARY KEY, day DATE "
                + "CHECK (EXTRACT(DAY FROM day)>1))",
            command));
    assertPostfix(
        command.projectionExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(SqlTypeDescriptor.INTEGER, command.columnCheckTypeDescriptor(1));
    assertEquals(1, command.columnCheckValue(1));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parse(
            "CREATE TABLE rejected_current (id BIGINT PRIMARY KEY, day DATE "
                + "CHECK (EXTRACT(DAY FROM CURRENT_DATE)"
                + "+EXTRACT(DAY FROM day)>1))",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE rejected_owner (id BIGINT PRIMARY KEY, day DATE "
                + "CHECK (id>1))",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE deferred_numeric_check (id BIGINT "
                + "CHECK (CAST(id AS BIGINT)>0) PRIMARY KEY, value BIGINT)",
            command));
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT observed, COUNT(*) FROM moments GROUP BY observed "
                + "HAVING EXTRACT(DAY FROM observed)>1",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM (SELECT id FROM moments "
                + "WHERE EXTRACT(DAY FROM observed)=29) m",
            new SqlQuery(),
            command));
  }

  @Test
  void parsesBooleanAndDecimalDescriptorsAndTypedLiterals() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE invoices (id BIGINT PRIMARY KEY, paid BOOLEAN "
                + "DEFAULT FALSE, amount DECIMAL(8,2) DEFAULT 12.30)",
            command));
    assertEquals(SqlTypeDescriptor.BOOLEAN, command.columnTypeDescriptor(1));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), command.columnTypeDescriptor(2));
    assertEquals(0, command.columnDefaultValue(1));
    assertEquals(1_230, command.columnDefaultValue(2));

    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO invoices VALUES (1, TRUE, -42.75)", command));
    assertEquals(SqlTypeDescriptor.BOOLEAN, command.insertTypeDescriptor(0, 1));
    assertEquals(1, command.insertValue(0, 1));
    assertEquals(SqlTypeDescriptor.decimal(4, 2), command.insertTypeDescriptor(0, 2));
    assertEquals(-4_275, command.insertValue(0, 2));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM invoices WHERE paid=TRUE AND amount BETWEEN 10.00 AND 20.00",
            command));
    assertEquals(SqlTypeDescriptor.BOOLEAN, predicateDescriptor(command, 0));
    assertEquals(SqlTypeDescriptor.decimal(4, 2), predicateDescriptor(command, 1));
    assertEquals(1_000, predicateLower(command, 1));
    assertEquals(2_000, predicateUpper(command, 1));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE wide_decimal (id BIGINT PRIMARY KEY, amount DECIMAL(19,2))",
            command));
    assertEquals(SqlTypeDescriptor.decimal(19, 2), command.columnTypeDescriptor(1));
  }

  @Test
  void parsesFirstClassNumericTypeAliasesAndFloatPrecision() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE numeric_types (small SMALLINT, normal INTEGER, alias INT, "
            + "wide BIGINT, exact NUMERIC(12,3), bare DECIMAL, single REAL, "
            + "float24 FLOAT(24), float53 FLOAT(53), floating FLOAT, "
            + "double_value DOUBLE PRECISION, mysql_double DOUBLE)", command));
    int[] expected = {
        SqlTypeDescriptor.SMALLINT, SqlTypeDescriptor.INTEGER, SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.decimal(12, 3),
        SqlTypeDescriptor.decimal(10, 0), SqlTypeDescriptor.REAL,
        SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.DOUBLE,
        SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.DOUBLE
    };
    assertEquals(expected.length, command.columnCount());
    for (int column = 0; column < expected.length; column++) {
      assertEquals(expected[column], command.columnTypeDescriptor(column));
    }
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE bad_float (value FLOAT(54))", command));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE bad_float (value FLOAT(0))", command));
  }

  @Test
  void parsesFiniteScientificLiteralsAsCanonicalDoubleValues() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "INSERT INTO measurements VALUES (1e3,-1.25E-2)", command));
    assertEquals(SqlTypeDescriptor.DOUBLE, command.insertTypeDescriptor(0, 0));
    assertEquals(SqlApproximateNumeric.doubleBits(1_000.0d), command.insertValue(0, 0));
    assertEquals(SqlTypeDescriptor.DOUBLE, command.insertTypeDescriptor(0, 1));
    assertEquals(SqlApproximateNumeric.doubleBits(-0.0125d), command.insertValue(0, 1));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        parser.parse("INSERT INTO measurements VALUES (1e309)", command));
  }

  @Test
  void parsesStrictLocalTemporalDescriptorsAndLiterals() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE moments (id BIGINT PRIMARY KEY, day DATE, "
                + "clock TIME(3), observed TIMESTAMP)",
            command));
    assertEquals(SqlTypeDescriptor.DATE, command.columnTypeDescriptor(1));
    assertEquals(SqlTypeDescriptor.time(3), command.columnTypeDescriptor(2));
    assertEquals(SqlTypeDescriptor.timestamp(6), command.columnTypeDescriptor(3));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO moments VALUES (1, DATE '1970-01-01', "
                + "TIME '12:34:56.123', "
                + "TIMESTAMP '1969-12-31 23:59:59.999999')",
            command));
    assertEquals(SqlTypeDescriptor.DATE, command.insertTypeDescriptor(0, 1));
    assertEquals(0, command.insertValue(0, 1));
    assertEquals(SqlTypeDescriptor.time(3), command.insertTypeDescriptor(0, 2));
    assertEquals(45_296_123_000L, command.insertValue(0, 2));
    assertEquals(SqlTypeDescriptor.timestamp(6), command.insertTypeDescriptor(0, 3));
    assertEquals(-1, command.insertValue(0, 3));

    assertEquals(StatusCode.OK, parser.parse("SELECT DATE '0001-01-01'", command));
    assertEquals(SqlCommandType.SCALAR_EXPRESSION, command.type());
    assertEquals(
        SqlTypeDescriptor.DATE,
        command.scalarExpression().resultTypeDescriptor());
    assertEquals(-719_162, command.scalarExpression().operand(0));

    assertTemporalInputRejected(
        parser, command, "DATE '0000-01-01'", StatusCode.DATETIME_FIELD_OVERFLOW);
    assertTemporalInputRejected(
        parser, command, "DATE '2023-02-29'", StatusCode.DATETIME_FIELD_OVERFLOW);
    assertTemporalInputRejected(
        parser, command, "TIME '24:00:00'", StatusCode.DATETIME_FIELD_OVERFLOW);
    assertTemporalInputRejected(
        parser, command, "TIME '23:59:60'", StatusCode.DATETIME_FIELD_OVERFLOW);
    assertTemporalInputRejected(
        parser, command, "TIME '12:00:00.1234567'", StatusCode.INVALID_DATETIME_FORMAT);
    assertTemporalInputRejected(
        parser, command, "TIMESTAMP '1970-01-01T00:00:00'",
        StatusCode.INVALID_DATETIME_FORMAT);
    assertTemporalInputRejected(
        parser, command, "TIMESTAMP '1970-01-01 00:00:00 trailing'",
        StatusCode.INVALID_DATETIME_FORMAT);
  }

  @Test
  void normalizesMixedPrecisionTemporalPredicateLiterals() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE clock BETWEEN "
                + "TIME '01:02:03' AND TIME '01:02:03.123456'",
            command));
    assertEquals(
        SqlTypeDescriptor.time(0),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_LOWER));
    assertEquals(
        SqlTypeDescriptor.time(6),
        predicateProgramDescriptor(
            command.wherePredicates(), 0, SqlBooleanPredicateProgram.PROGRAM_UPPER));
    assertEquals(3_723_000_000L, predicateLower(command, 0));
    assertEquals(3_723_123_456L, predicateUpper(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE local_seen IN ("
                + "TIMESTAMP '1970-01-01 00:00:00',"
                + "TIMESTAMP '1970-01-01 00:00:00.123',"
                + "TIMESTAMP '1970-01-01 00:00:00.123456')",
            command));
    assertEquals(3, membershipCount(command, 0));
    assertEquals(
        SqlTypeDescriptor.timestamp(0),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.timestamp(3),
        command.wherePredicates().memberDescriptor(0, 1));
    assertEquals(
        SqlTypeDescriptor.timestamp(6),
        command.wherePredicates().memberDescriptor(0, 2));
    assertEquals(0, membershipValue(command, 0, 0));
    assertEquals(123_000, membershipValue(command, 0, 1));
    assertEquals(123_456, membershipValue(command, 0, 2));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE captured NOT IN ("
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00',"
                + "NULL,TIMESTAMP WITH TIME ZONE "
                + "'1970-01-01 00:00:00.123456+00:00')",
            command));
    assertEquals(3, membershipCount(command, 0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(0),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        command.wherePredicates().memberDescriptor(0, 2));
    assertTrue(membershipHasNull(command, 0));
    assertEquals(0, membershipValue(command, 0, 0));
    assertEquals(123_456, membershipValue(command, 0, 2));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE day BETWEEN "
                + "DATE '2024-01-01' AND DATE '2024-01-02'",
            command));
    assertEquals(SqlTypeDescriptor.DATE, predicateDescriptor(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE clock BETWEEN "
                + "TIME '01:02:03' AND TIMESTAMP '1970-01-01 01:02:03'",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE day IN ("
                + "DATE '1970-01-01',TIME '00:00:00')",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE captured IN ("
                + "TIMESTAMP '1970-01-01 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00')",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM samples WHERE clock IN (TIME '01:02:03',1)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM samples WHERE amount BETWEEN 1 AND 2.0", command));
  }

  @Test
  void parsesZonedTemporalSessionAndCurrentForms() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE events (id BIGINT PRIMARY KEY, "
                + "recorded TIMESTAMP(3) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, "
                + "local_seen TIMESTAMP DEFAULT LOCALTIMESTAMP)",
            command));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(3), command.columnTypeDescriptor(1));
    assertEquals(SqlDefaultKind.CURRENT_TIMESTAMP, command.columnDefaultKind(1));
    assertEquals(SqlDefaultKind.LOCALTIMESTAMP, command.columnDefaultKind(2));

    assertEquals(StatusCode.OK, parser.parse("SET TIME ZONE 'Europe/London'", command));
    assertEquals(SqlCommandType.SET_TIME_ZONE, command.type());
    ByteBuffer zone = ByteBuffer.allocate(32);
    assertEquals(13, command.copyText(command.value(), zone));
    zone.flip();
    assertEquals("Europe/London", StandardCharsets.US_ASCII.decode(zone).toString());

    assertEquals(StatusCode.OK, parser.parse("SELECT CURRENT_TIMESTAMP", command));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        command.scalarExpression().resultTypeDescriptor());
    assertEquals(StatusCode.OK, parser.parse("SELECT LOCALTIME", command));
    assertEquals(SqlTypeDescriptor.time(6), command.scalarExpression().resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT TIMESTAMP '2024-01-01 00:00:00' AT TIME ZONE '+01:00'",
            command));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(0),
        command.scalarExpression().resultTypeDescriptor());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT CURRENT_TIMESTAMP AT TIME ZONE 'UTC'", command));
    assertEquals(
        SqlTypeDescriptor.timestamp(6),
        command.scalarExpression().resultTypeDescriptor());

    assertTemporalInputRejected(
        parser,
        command,
        "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+14:01'",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertTemporalInputRejected(
        parser,
        command,
        "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00Z'",
        StatusCode.INVALID_DATETIME_FORMAT);
  }

  private static void assertTemporalInputRejected(
      SqlParser parser, SqlCommand command, String literal, StatusCode expected) {
    assertEquals(
        expected,
        parser.parse("SELECT " + literal, command));
    assertFalse(command.isAvailable());
  }

  @Test
  void groupsBoundedDisjunctionsWithoutFlatteningNestedBlocks() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics "
                + "WHERE region=7 OR region=8 AND value>100",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(3, command.wherePredicates().leafCount());
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    assertEquals(5, predicates.booleanNodeCount());
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_AND, predicates.booleanOperator(3));
    assertEquals(1, predicates.booleanLeft(3));
    assertEquals(2, predicates.booleanRight(3));
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_OR, predicates.booleanOperator(4));
    assertEquals(0, predicates.booleanLeft(4));
    assertEquals(3, predicates.booleanRight(4));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM metrics WHERE region=7 OR", command));
    SqlQuery query = new SqlQuery();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM "
                + "(SELECT id FROM metrics WHERE region=7 OR region=8) d",
            query,
            command));
    assertEquals(
        SqlBooleanPredicateProgram.BOOLEAN_OR,
        command.wherePredicates().booleanOperator(
            command.wherePredicates().root()));
  }

  @Test
  void parsesBigintComparisonsWithoutLosingHalfOpenRanges() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value=-4", command));
    assertEquals(SqlComparison.EQUAL, predicateComparison(command, 0));
    assertEquals(-4, predicateValue(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value<>0", command));
    assertEquals(SqlComparison.NOT_EQUAL, predicateComparison(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value!=0", command));
    assertEquals(SqlComparison.NOT_EQUAL, predicateComparison(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value<-9223372036854775807",
            command));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 0));
    assertEquals(-9223372036854775807L, predicateValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value<=-9223372036854775808",
            command));
    assertEquals(SqlComparison.LESS_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(Long.MIN_VALUE, predicateValue(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value>9223372036854775806", command));
    assertEquals(SqlComparison.GREATER_THAN, predicateComparison(command, 0));
    assertEquals(9223372036854775806L, predicateValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value>=9223372036854775807", command));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(Long.MAX_VALUE, predicateValue(command, 0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND value<8",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(-5, predicateValue(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(8, predicateValue(command, 1));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND value<=8",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(SqlComparison.LESS_OR_EQUAL, predicateComparison(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND id<8",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertName("value", predicateColumnName(command, 0));
    assertName("id", predicateColumnName(command, 1));
  }

  @Test
  void parsesNullPredicates() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM nullable_values WHERE value IS NULL",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(1, command.wherePredicates().leafCount());
    assertEquals(
        SqlBooleanPredicateProgram.TEST_NULL,
        command.wherePredicates().leafTest(0));
    assertFalse(command.wherePredicates().leafNegated(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM nullable_values "
                + "WHERE value IS NOT NULL AND rank IS NULL",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(
        SqlBooleanPredicateProgram.TEST_NULL,
        command.wherePredicates().leafTest(0));
    assertTrue(command.wherePredicates().leafNegated(0));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_NULL,
        command.wherePredicates().leafTest(1));
    assertFalse(command.wherePredicates().leafNegated(1));
  }

  @Test
  void warmedParseReusesCommandAndParserState() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    TestParameters nestedParameters = new TestParameters(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new long[] {7, 9},
        new boolean[] {false, false},
        new String[] {null, null});
    for (int index = 0; index < 1_000; index++) {
      allocationGuard += parser.parse("SELECT 1.00/8.0", command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT d.key FROM "
              + "(SELECT key, region FROM accounts WHERE accounts.region=3) d "
              + "WHERE d.key=7",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value="
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE EXISTS "
              + "(SELECT key FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value NOT IN "
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM nullable_values WHERE value IS NOT NULL",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT a.id FROM accounts AS a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.region=a.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id=(SELECT id FROM accounts "
              + "WHERE id=(SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE EXISTS (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id IN (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE id=1)))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM lookup WHERE id=?) AND region=?",
          nestedParameters,
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE (SELECT id FROM lookup WHERE id=1)<id",
          query,
          command).ordinal();
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000; index++) {
      allocationGuard += parser.parse("SELECT 1.00/8.0", command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT d.key FROM "
              + "(SELECT key, region FROM accounts WHERE accounts.region=3) d "
              + "WHERE d.key=7",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE EXISTS "
              + "(SELECT key FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value="
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value NOT IN "
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM nullable_values WHERE value IS NOT NULL",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT a.id FROM accounts AS a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.region=a.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id=(SELECT id FROM accounts "
              + "WHERE id=(SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE EXISTS (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id IN (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE id=1)))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM lookup WHERE id=?) AND region=?",
          nestedParameters,
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE (SELECT id FROM lookup WHERE id=1)<id",
          query,
          command).ordinal();
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 256, "warmed SQL parse allocated bytes: " + allocated);
  }

  @Test
  void retainsAggregateShapeWhenJoinParserFinishesLast() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "SELECT COUNT(DISTINCT s.i_id), SUM(s.i_id) FROM stock s "
            + "INNER JOIN order_line ol ON ol.ol_i_id=s.i_id", command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertEquals(2, command.aggregateOutputCount());
    assertEquals(2, command.aggregateInvocationCount());
    assertEquals(SqlAggregateKind.COUNT_DISTINCT, command.aggregateKind(0));
    assertEquals(SqlAggregateKind.SUM, command.aggregateKind(1));
  }

}
