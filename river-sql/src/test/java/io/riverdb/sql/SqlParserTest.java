package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
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
    assertName("id", copied.projectionSymbolName(copiedId));

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
    assertName("day", compiled.projectionSymbolName(day));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT EXTRACT(DAY FROM observed) AS d FROM moments", view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, compiled));
    assertPostfix(
        compiled.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    int observed = (int) compiled.projectionExpression(0).operand(0);
    assertName("observed", compiled.projectionSymbolName(observed));

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
    assertName("d", compiled.orderColumnName());

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
  void parsesExecutablePointStatementSubset() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("create table accounts;", command));
    assertEquals(SqlCommandType.CREATE_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertName("key", command.firstColumnName());
    assertName("value", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE balances (account_id BIGINT PRIMARY KEY, amount BIGINT)",
            command));
    assertName("account_id", command.firstColumnName());
    assertName("amount", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE ledger "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            command));
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(2));
    assertTrue(command.columnIsNotNull(0));
    assertFalse(command.columnIsNotNull(1));
    assertFalse(command.columnIsNotNull(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE required_values "
                + "(id BIGINT NOT NULL PRIMARY KEY, value BIGINT NOT NULL, "
                + "note BIGINT)",
            command));
    assertTrue(command.columnIsNotNull(0));
    assertTrue(command.columnIsNotNull(1));
    assertFalse(command.columnIsNotNull(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE default_values "
                + "(id BIGINT PRIMARY KEY, required BIGINT NOT NULL DEFAULT -7, "
                + "optional BIGINT DEFAULT 9 NOT NULL, note BIGINT DEFAULT 0)",
            command));
    assertFalse(command.columnHasDefault(0));
    assertTrue(command.columnHasDefault(1));
    assertEquals(-7, command.columnDefaultValue(1));
    assertTrue(command.columnIsNotNull(1));
    assertTrue(command.columnHasDefault(2));
    assertEquals(9, command.columnDefaultValue(2));
    assertTrue(command.columnIsNotNull(2));
    assertTrue(command.columnHasDefault(3));
    assertEquals(0, command.columnDefaultValue(3));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7) NOT NULL DEFAULT 'new', "
                + "note VARCHAR(7))",
            command));
    assertTrue(command.columnIsVarchar(1));
    assertTrue(command.columnIsNotNull(1));
    assertText("new", command, command.columnDefaultValue(1));
    assertTrue(command.columnIsVarchar(2));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE UNIQUE INDEX accounts_value ON accounts(value)", command));
    assertEquals(SqlCommandType.CREATE_UNIQUE_INDEX, command.type());
    assertName("accounts_value", command.indexName());
    assertName("accounts", command.tableName());
    assertName("value", command.firstColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM labels WHERE code >= 'alpha' AND code < 'omega'",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlTypeDescriptor.varchar(5), predicateDescriptor(command, 0));
    assertEquals(SqlTypeDescriptor.varchar(5), predicateDescriptor(command, 1));
    assertText("alpha", command, predicateValue(command, 0));
    assertText("omega", command, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM labels WHERE code IN ('beta', 'alpha')", command));
    assertEquals(
        SqlTypeDescriptor.varchar(4),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.varchar(5),
        command.wherePredicates().memberDescriptor(0, 1));
    assertText("beta", command, membershipValue(command, 0, 0));
    assertText("alpha", command, membershipValue(command, 0, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id, NULL FROM accounts WHERE id=1", command));
    assertEquals(2, command.columnCount());
    assertFalse(command.isNullProjection(0));
    assertTrue(command.isNullProjection(1));
    assertName("null", command.columnName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE INDEX accounts_region ON accounts(region)", command));
    assertEquals(SqlCommandType.CREATE_INDEX, command.type());
    assertName("accounts_region", command.indexName());
    assertName("accounts", command.tableName());
    assertName("region", command.firstColumnName());
    assertCompositeIndexColumns(parser, command);
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP INDEX accounts_region ON accounts", command));
    assertEquals(SqlCommandType.DROP_INDEX, command.type());
    assertName("accounts_region", command.indexName());
    assertName("accounts", command.tableName());
    assertEquals(StatusCode.OK, parser.parse("DROP TABLE accounts", command));
    assertEquals(SqlCommandType.DROP_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER TABLE accounts RENAME TO customers", command));
    assertEquals(SqlCommandType.ALTER_TABLE_RENAME, command.type());
    assertName("accounts", command.tableName());
    assertName("customers", command.renamedTableName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER TABLE customers RENAME COLUMN region TO area", command));
    assertEquals(SqlCommandType.ALTER_TABLE_RENAME_COLUMN, command.type());
    assertName("customers", command.tableName());
    assertName("region", command.firstColumnName());
    assertName("area", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER INDEX customers_region RENAME TO customers_area", command));
    assertEquals(SqlCommandType.ALTER_INDEX_RENAME, command.type());
    assertName("customers_region", command.indexName());
    assertName("customers_area", command.renamedIndexName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE SEQUENCE invoice_ids INCREMENT BY -3 START WITH 100",
            command));
    assertEquals(SqlCommandType.CREATE_SEQUENCE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(100, command.sequenceStart());
    assertEquals(-3, command.sequenceIncrement());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT NEXT VALUE FOR invoice_ids", command));
    assertEquals(SqlCommandType.NEXT_SEQUENCE_VALUE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP SEQUENCE invoice_ids", command));
    assertEquals(SqlCommandType.DROP_SEQUENCE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE SEQUENCE invalid INCREMENT BY 0", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE VIEW active_accounts AS "
                + "SELECT id, region FROM accounts WHERE balance>0;",
            command));
    assertEquals(SqlCommandType.CREATE_VIEW, command.type());
    assertName("active_accounts", command.tableName());
    assertText(
        "SELECT id, region FROM accounts WHERE balance>0",
        command.viewQuery());
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP VIEW active_accounts", command));
    assertEquals(SqlCommandType.DROP_VIEW, command.type());
    assertName("active_accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE events (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                + "payload BIGINT NOT NULL)",
            command));
    assertEquals(SqlCommandType.CREATE_TABLE, command.type());
    assertEquals(true, command.hasPrimaryKeyIdentity());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE invalid_identity "
                + "(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, payload BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE bounded_accounts "
                + "(id BIGINT CHECK (id > 0) PRIMARY KEY, "
                + "balance BIGINT CHECK (balance >= -100))",
            command));
    assertEquals(true, command.columnHasCheck(0));
    assertEquals(SqlComparison.GREATER_THAN, command.columnCheckComparison(0));
    assertEquals(0, command.columnCheckValue(0));
    assertEquals(true, command.columnHasCheck(1));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, command.columnCheckComparison(1));
    assertEquals(-100, command.columnCheckValue(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE contacts "
                + "(id BIGINT PRIMARY KEY, email BIGINT UNIQUE, alias VARCHAR(7) UNIQUE)",
            command));
    assertEquals(true, command.columnIsUnique(1));
    assertEquals(true, command.columnIsUnique(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE orders "
                + "(id BIGINT PRIMARY KEY, account_id BIGINT REFERENCES accounts(id), "
                + "external_id BIGINT UNIQUE REFERENCES external_accounts(id))",
            command));
    assertEquals(true, command.columnHasReference(1));
    assertName("accounts", command.columnReferenceTableName(1));
    assertName("id", command.columnReferenceColumnName(1));
    assertEquals(true, command.columnHasReference(2));
    assertEquals(true, command.columnIsUnique(2));
    assertName("external_accounts", command.columnReferenceTableName(2));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE duplicate_reference "
                + "(id BIGINT PRIMARY KEY, account_id BIGINT "
                + "REFERENCES accounts(id) REFERENCES accounts(id))",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE text_reference "
                + "(id BIGINT PRIMARY KEY, account_id VARCHAR(7) REFERENCES accounts(id))",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE excessive_references "
                + "(id BIGINT PRIMARY KEY, a BIGINT REFERENCES parents(id), "
                + "b BIGINT REFERENCES parents(id), c BIGINT REFERENCES parents(id), "
                + "d BIGINT REFERENCES parents(id), e BIGINT REFERENCES parents(id))",
            command));
    assertTrue(command.columnHasReference(5));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE reserved_reference "
                + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES _river_parent(id))",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE duplicate_unique "
                + "(id BIGINT PRIMARY KEY, value BIGINT UNIQUE UNIQUE)",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("DROP INDEX _river_unique_1_1 ON contacts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE _river_reserved (id BIGINT PRIMARY KEY, value BIGINT)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE wrong_check "
                + "(id BIGINT PRIMARY KEY, value BIGINT CHECK (id > 0))",
            command));
    assertEquals(StatusCode.OK, parser.parse("INSERT INTO accounts VALUES (7, -9)", command));
    assertEquals(SqlCommandType.INSERT, command.type());
    assertEquals(7, command.key());
    assertEquals(-9, command.value());
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO accounts VALUES (1, 10), (2, 20), (3, 30)", command));
    assertEquals(3, command.insertRowCount());
    assertEquals(1, command.insertKey(0));
    assertEquals(20, command.insertValue(1));
    assertEquals(3, command.insertKey(2));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO ledger VALUES (1, 100, 7), (2, 200, 8)", command));
    assertEquals(2, command.insertRowCount());
    assertEquals(3, command.insertColumnCount());
    assertEquals(8, command.insertValue(1, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO ledger VALUES (1, NULL, 7), (2, 20, NULL)",
            command));
    assertFalse(command.insertIsNull(0, 0));
    assertTrue(command.insertIsNull(0, 1));
    assertTrue(command.insertIsNull(1, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO ledger VALUES (3, DEFAULT, NULL)", command));
    assertTrue(command.insertIsDefault(0, 1));
    assertFalse(command.insertIsDefault(0, 2));
    assertTrue(command.insertIsNull(0, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO labels VALUES (1, 'river', 'it''s')", command));
    assertEquals(SqlTypeDescriptor.varchar(5), command.insertTypeDescriptor(0, 1));
    assertText("river", command, command.insertValue(0, 1));
    assertText("it's", command, command.insertValue(0, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO products (id, code, qty) VALUES "
                + "(1, 'beta', 10), (2, 'alpha', 20)",
            command));
    assertEquals(3, command.columnCount());
    assertEquals(SqlTypeDescriptor.varchar(4), command.insertTypeDescriptor(0, 1));
    assertEquals(SqlTypeDescriptor.INTEGER, command.insertTypeDescriptor(0, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO ledger (region, id, balance) VALUES (7, 1, 100)",
            command));
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("balance", command.columnName(2));
    assertEquals(7, command.insertValue(0, 0));
    assertEquals(StatusCode.OK, parser.parse("select value from accounts where key=7", command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertName("value", command.firstColumnName());
    assertName("key", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(StatusCode.OK, parser.parse("SELECT COUNT(*) FROM accounts", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertName("accounts", command.tableName());
    assertEquals(false, command.hasPredicate());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT COUNT(*) FROM accounts WHERE region=7", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertEquals(true, command.hasPredicate());
    assertEquals(true, (predicateComparison(command, 0) == SqlComparison.EQUAL));
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(*) FROM accounts WHERE region >= -2 AND region < 9",
            command));
    assertEquals(true, command.hasPredicate());
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(-2, predicateValue(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(9, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(a.balance) AS present FROM accounts a WHERE a.region=7",
            command));
    assertEquals(SqlCommandType.COUNT_VALUE, command.type());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("present", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(DISTINCT a.balance) AS unique_balance FROM accounts a",
            command));
    assertEquals(SqlCommandType.COUNT_DISTINCT, command.type());
    assertEquals(SqlAggregateKind.COUNT_DISTINCT, command.aggregateKind(0));
    assertEquals(0, command.aggregateOperandProjection(0));
    assertName("balance", command.columnName(0));
    assertName("unique_balance", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT SUM(a.balance) AS total FROM accounts a "
                + "WHERE a.region>=7 AND a.region<9",
            command));
    assertEquals(SqlCommandType.SUM, command.type());
    assertName("accounts", command.tableName());
    assertName("a", command.tableAlias());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("total", command.columnAlias(0));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT MIN(a.balance) AS lowest FROM accounts a", command));
    assertEquals(SqlCommandType.MIN, command.type());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("lowest", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT MAX(balance) highest FROM accounts", command));
    assertEquals(SqlCommandType.MAX, command.type());
    assertName("balance", command.columnName(0));
    assertName("highest", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(EXTRACT(DAY FROM observed)) AS days FROM accounts",
            command));
    assertEquals(SqlCommandType.COUNT_VALUE, command.type());
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertTrue(command.projectionExpression(0).hasColumnReference());
    assertName("days", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT MIN(CAST(observed AS TIMESTAMP(3))) FROM accounts",
            command));
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MIN(EXTRACT(DAY FROM observed)) FROM accounts "
                + "GROUP BY region",
            command));
    assertPostfix(
        command.projectionExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(observed AT TIME ZONE 'UTC') FROM accounts "
                + "GROUP BY region HAVING MAX(observed AT TIME ZONE 'UTC')>="
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(day+(CAST('2024-01-01' AS DATE)-day)) FROM accounts "
                + "GROUP BY region HAVING "
                + "MAX(day+(CAST('2024-01-01' AS DATE)-day))>=DATE '2024-01-01'",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(observed AT TIME ZONE 'UTC') FROM accounts "
                + "GROUP BY region HAVING MAX(observed AT TIME ZONE '+01:00')>="
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'",
            command));
    assertEquals(2, command.aggregateInvocationCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(*) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(balance, region) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(balance AS total) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT MIN(*) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT MAX(balance, region) FROM accounts", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT COUNT(*) AS total FROM accounts", command));
    assertName("total", command.columnAlias(0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT COUNT(balance AS total) FROM accounts", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM accounts WHERE region IN (7, -2, 7, NULL) AND id NOT IN (9)",
            command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_MEMBERSHIP,
        command.wherePredicates().leafTest(0));
    assertEquals(4, membershipCount(command, 0));
    assertEquals(7, membershipValue(command, 0, 0));
    assertEquals(-2, membershipValue(command, 0, 1));
    assertEquals(7, membershipValue(command, 0, 2));
    assertTrue(membershipHasNull(command, 0));
    assertTrue(command.wherePredicates().leafNegated(1));
    assertEquals(1, membershipCount(command, 1));
    assertEquals(9, membershipValue(command, 1, 0));
    assertFalse(membershipHasNull(command, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM accounts WHERE region IN ()", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM accounts WHERE region IN (1,)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM accounts WHERE region BETWEEN -2 AND 9", command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(-2, predicateLower(command, 0));
    assertEquals(9, predicateUpper(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM accounts WHERE region BETWEEN 7 AND 9223372036854775807",
            command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(7, predicateLower(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM accounts WHERE region BETWEEN 9 AND -2", command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "WHERE value >= 100 AND value < 300 AND region=7 "
                + "GROUP BY region ORDER BY region ASC",
            command));
    assertEquals(SqlCommandType.GROUP_COUNT, command.type());
    assertName("region", command.firstColumnName());
    assertName("accounts", command.tableName());
    assertEquals(3, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) AS total FROM accounts "
                + "GROUP BY region ORDER BY region LIMIT 5",
            command));
    assertEquals(SqlCommandType.GROUP_SUM, command.type());
    assertName("region", command.columnName(0));
    assertName("balance", command.columnName(1));
    assertName("total", command.columnAlias(1));
    assertEquals(5, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) AS total FROM accounts "
                + "GROUP BY region HAVING SUM(balance) >= 100 "
                + "ORDER BY region LIMIT 5",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, havingComparison(command, 0));
    assertEquals(100, havingValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "GROUP BY region HAVING COUNT(*) <> 1",
            command));
    assertEquals(SqlComparison.NOT_EQUAL, havingComparison(command, 0));
    assertEquals(1, havingValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) FROM accounts "
                + "GROUP BY region HAVING COUNT(*) > 1",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_COUNT_VALUE, command.type());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MIN(accounts.balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_MIN, command.type());
    assertName("accounts", command.columnTableName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_MAX, command.type());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts GROUP BY value",
            command));
    assertEquals(StatusCode.OK, parser.parse("SELECT key, value FROM accounts", command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertName("accounts", command.tableName());
    assertEquals(false, command.isBoundedScan());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts ORDER BY value ASC LIMIT 7",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(true, command.isOrdered());
    assertEquals(false, command.isDescendingOrder());
    assertName("value", command.orderColumnName());
    assertEquals(7, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "JOIN regions ON accounts.region=regions.id "
                + "WHERE accounts.region >= 7 AND accounts.region < 9 LIMIT 0",
            command));
    assertEquals(0, command.rowLimit());
    assertName("accounts", predicateTableName(command, 0));
    assertName("region", predicateColumnName(command, 0));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(9, predicateValue(command, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT key FROM accounts LIMIT -1", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT region FROM accounts WHERE value=100 AND region=7 "
                + "ORDER BY region LIMIT 2",
            command));
    assertEquals(SqlCommandType.DISTINCT_SCAN, command.type());
    assertName("region", command.firstColumnName());
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(2, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT DISTINCT region, key FROM accounts", command));
    assertEquals(2, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("key", command.columnName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT key FROM accounts ORDER BY key DESC", command));
    assertTrue(command.isDescendingOrder());
    assertName("key", command.orderColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "GROUP BY region ORDER BY region DESC",
            command));
    assertTrue(command.isDescendingOrder());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT region FROM accounts ORDER BY region DESC",
            command));
    assertTrue(command.isDescendingOrder());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "JOIN regions ON accounts.region=regions.id",
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertName("accounts", command.tableName());
    assertName("regions", command.joinChain().tableName(1));
    assertEquals(1, command.joinChain().onPredicates(0).leafCount());
    assertEquals(
        SqlComparison.EQUAL,
        command.joinChain().onPredicates(0).comparison(0));
    int onLeft = (int) command.joinChain().onPredicates(0).programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    int onRight = (int) command.joinChain().onPredicates(0).programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    assertName("accounts", command.predicateSymbolTable(onLeft));
    assertName("region", command.predicateSymbolName(onLeft));
    assertName("regions", command.predicateSymbolTable(onRight));
    assertName("id", command.predicateSymbolName(onRight));
    assertName("accounts", command.columnTableName(0));
    assertName("key", command.columnName(0));
    assertName("regions", command.columnTableName(1));
    assertName("code", command.columnName(1));
    assertFalse(command.joinChain().isLeft(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "LEFT OUTER JOIN regions ON accounts.region=regions.id",
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertTrue(command.joinChain().isLeft(0));
    assertName("regions", command.joinChain().tableName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key, r.code FROM accounts a "
                + "JOIN regions AS r ON a.region=r.id "
                + "WHERE a.region=7 AND r.code>=7000",
            command));
    assertName("a", command.tableAlias());
    assertName("r", command.joinChain().alias(1));
    assertName("a", command.columnTableName(0));
    assertName("r", command.columnTableName(1));
    assertName("a", predicateTableName(command, 0));
    assertName("r", predicateTableName(command, 1));
    assertEquals(1, command.joinChain().onPredicates(0).leafCount());
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key+1 AS next_key,r.code FROM accounts a "
                + "LEFT JOIN regions r ON NOT (a.region+1<>r.id OR r.code<0) "
                + "WHERE a.key+r.id>2",
            command));
    assertTrue(command.joinChain().isLeft(0));
    assertEquals(2, command.joinChain().onPredicates(0).leafCount());
    assertEquals(1, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT region, key, value FROM accounts WHERE key=7", command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("value", command.columnName(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE key >= 11 AND key < 29",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertFalse(command.isBoundedScan());
    assertEquals(11, predicateValue(command, 0));
    assertEquals(29, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value = 701",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(701, predicateValue(command, 0));
    assertName("key", command.firstColumnName());
    assertName("value", command.secondColumnName());
    assertName("value", predicateColumnName(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value >= -50 AND value < 75",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(-50, predicateValue(command, 0));
    assertEquals(75, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key FROM accounts WHERE region=7 "
                + "AND value >= 100 AND value < 300 AND key=2",
            command));
    assertEquals(4, command.wherePredicates().leafCount());
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertName("value", predicateColumnName(command, 1));
    assertEquals(100, predicateValue(command, 1));
    assertName("value", predicateColumnName(command, 2));
    assertEquals(300, predicateValue(command, 2));
    assertName("key", predicateColumnName(command, 3));
    assertEquals(2, predicateValue(command, 3));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key FROM accounts "
                + "JOIN regions ON accounts.region=regions.id "
                + "WHERE accounts.region=7 AND accounts.value >= 100 "
                + "AND accounts.value < 300 AND regions.code=7000",
            command));
    assertEquals(4, command.wherePredicates().leafCount());
    assertName("accounts", predicateTableName(command, 1));
    assertName("value", predicateColumnName(command, 1));
    assertName("regions", predicateTableName(command, 3));
    assertName("code", predicateColumnName(command, 3));
    assertEquals(StatusCode.OK, parser.parse("UPDATE accounts SET value=11 WHERE key=7", command));
    assertEquals(SqlCommandType.UPDATE, command.type());
    assertEquals(11, command.value());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=12, region=-3 WHERE key=7",
            command));
    assertEquals(2, command.updateColumnCount());
    assertName("balance", command.columnName(0));
    assertName("region", command.columnName(1));
    assertEquals(12, command.updateValue(0));
    assertEquals(-3, command.updateValue(1));
    assertFalse(command.updateHasExpression(0));
    assertFalse(command.updateHasExpression(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=balance+25, region=id-2 WHERE key=7",
            command));
    assertEquals(2, command.updateColumnCount());
    assertTrue(command.updateHasExpression(0));
    assertTrue(command.updateHasExpression(1));
    assertEquals(2, command.mutationExpressionCount());
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertMutationPostfix(
        command,
        command.updateExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.SUBTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=NULL, region=4 WHERE key=7",
            command));
    assertTrue(command.updateIsNull(0));
    assertFalse(command.updateIsNull(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=DEFAULT, region=NULL WHERE key=7",
            command));
    assertTrue(command.updateIsDefault(0));
    assertFalse(command.updateIsNull(0));
    assertFalse(command.updateIsDefault(1));
    assertTrue(command.updateIsNull(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE labels SET code='fresh' WHERE id=1", command));
    assertEquals(SqlTypeDescriptor.varchar(5), command.updateTypeDescriptor(0));
    assertText("fresh", command, command.updateValue(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET region=9 WHERE balance >= 100 AND balance < 500",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(100, predicateValue(command, 0));
    assertEquals(500, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE accounts SET balance=balance WHERE key=7", command));
    assertTrue(command.updateHasExpression(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE accounts SET balance=balance*2 WHERE key=7", command));
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.MULTIPLY);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO accounts VALUES (1+2,-9223372036854775808),"
                + "(4,5)",
            command));
    assertTrue(command.insertHasExpression(0, 0));
    assertMutationPostfix(
        command,
        command.insertExpression(0, 0),
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(Long.MIN_VALUE, command.insertValue(0, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=-9223372036854775808+1 WHERE key=7",
            command));
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(
        Long.MIN_VALUE,
        command.mutationExpressionOperand(command.updateExpression(0), 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO accounts VALUES"
                + "(1+1,2+2,3+3,4+4,5+5,6+6,7+7,8+8),"
                + "(9+9,10+10,11+11,12+12,13+13,14+14,15+15,16+16)",
            command));
    assertEquals(2, command.insertRowCount());
    assertEquals(8, command.insertColumnCount());
    assertEquals(StatusCode.OK, parser.parse("DELETE FROM accounts WHERE key = 7", command));
    assertEquals(SqlCommandType.DELETE, command.type());
    assertEquals(true, (predicateComparison(command, 0) == SqlComparison.EQUAL));
    assertEquals(
        StatusCode.OK,
        parser.parse("DELETE FROM accounts WHERE key >= 10 AND key < 20", command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(10, predicateValue(command, 0));
    assertEquals(20, predicateValue(command, 1));
    assertEquals(StatusCode.OK, parser.parse("BEGIN;", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN READ COMMITTED", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(true, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN REPEATABLE READ", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN SERIALIZABLE", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(true, command.isSerializableTransaction());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("BEGIN READ", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("BEGIN REPEATABLE", command));
    assertEquals(StatusCode.OK, parser.parse("SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.SAVEPOINT, command.type());
    assertName("before_update", command.savepointName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ROLLBACK TO SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.ROLLBACK_TO_SAVEPOINT, command.type());
    assertName("before_update", command.savepointName());
    assertEquals(
        StatusCode.OK,
        parser.parse("RELEASE SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.RELEASE_SAVEPOINT, command.type());
    assertEquals(StatusCode.OK, parser.parse("COMMIT", command));
    assertEquals(SqlCommandType.COMMIT, command.type());
    assertEquals(StatusCode.OK, parser.parse("ROLLBACK", command));
    assertEquals(SqlCommandType.ROLLBACK, command.type());
    assertEquals(StatusCode.OK, parser.parse("CHECKPOINT", command));
    assertEquals(SqlCommandType.CHECKPOINT, command.type());
  }

  @Test
  void parsesStreamingCatalogQueries() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertEquals(StatusCode.OK, parser.parseQuery("SHOW TABLES;", query, command));
    assertEquals(SqlCommandType.SHOW_TABLES, command.type());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW TABLE", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW TABLES EXTRA", query, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery("SHOW INDEXES FROM accounts", query, command));
    assertEquals(SqlCommandType.SHOW_INDEXES, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery("SHOW COLUMNS FROM accounts", query, command));
    assertEquals(SqlCommandType.SHOW_COLUMNS, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW INDEXES accounts", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW COLUMNS accounts", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW COLUMNS FROM accounts EXTRA", query, command));
  }

  @Test
  void parsesAnalyzeTableAndRecoversFromMalformedInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("ANALYZE accounts", command));
    assertEquals(SqlCommandType.ANALYZE_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertEquals(StatusCode.OK, parser.parse("ANALYZE TABLE regions", command));
    assertEquals(SqlCommandType.ANALYZE_TABLE, command.type());
    assertName("regions", command.tableName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("ANALYZE", command));
    assertEquals(StatusCode.OK, parser.parse("ANALYZE countries", command));
    assertName("countries", command.tableName());
  }

  @Test
  void rejectsMalformedUnsupportedAndOverflowInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("SELECT * FROM x", command));
    assertTrue(command.isSelectAll());
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("CREATE TABLE bad-name", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE TABLE only_key (id BIGINT PRIMARY KEY)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE bad_default "
                + "(id BIGINT PRIMARY KEY, value BIGINT DEFAULT 1 DEFAULT 2)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE bad_primary_default "
                + "(id BIGINT DEFAULT 1 PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE text_key (id VARCHAR(7) PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO labels VALUES (1, 'ninechars', NULL)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM labels WHERE code IN ('one', 2)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (9223372036854775808, 1)", command));
    assertEquals(SqlTypeDescriptor.decimal(19, 0), command.insertTypeDescriptor(0, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (0, -9223372036854775808)", command));
    assertEquals(Long.MIN_VALUE, command.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("DROP TABLE", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT key, value FROM x WHERE key ! 1", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key FROM x WHERE a=1 AND b=2 AND c=3 AND d=4 "
                + "AND e=5 AND f=6 AND g=7 AND h=8 AND i=9",
            command));
    assertEquals(9, command.wherePredicates().leafCount());
    StringBuilder tooManyRows = new StringBuilder("INSERT INTO x VALUES ");
    for (int index = 0; index <= SqlCommand.MAXIMUM_INSERT_ROWS; index++) {
      if (index > 0) {
        tooManyRows.append(',');
      }
      tooManyRows.append('(').append(index).append(',').append(index).append(')');
    }
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parser.parse(tooManyRows.toString(), command));
    assertFalse(command.isAvailable());
  }

  @Test
  void acceptsExactCapacitiesAndPreservesCapacityBeforeTrailingInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    String maximumName = "a".repeat(SqlIdentifier.MAXIMUM_LENGTH);

    assertEquals(StatusCode.OK, parser.parse("DROP TABLE " + maximumName, command));
    assertTrue(command.isAvailable());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parser.parse("DROP TABLE " + maximumName + "a trailing", command));
    assertFalse(command.isAvailable());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("DROP TABLE " + maximumName + " trailing", command));
    assertFalse(command.isAvailable());

    StringBuilder columns = new StringBuilder(
        "CREATE TABLE exact_columns (c0 BIGINT PRIMARY KEY");
    for (int index = 1; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
      columns.append(", c").append(index).append(" BIGINT");
    }
    columns.append(')');
    assertEquals(StatusCode.OK, parser.parse(columns, command));
    assertEquals(SqlCommand.MAXIMUM_COLUMNS, command.columnCount());
    assertTrue(command.isAvailable());
    columns.insert(columns.length() - 1, ", overflow BIGINT");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(columns, command));
    assertFalse(command.isAvailable());

    StringBuilder predicates = new StringBuilder("SELECT key FROM x WHERE ");
    for (int index = 0; index < SqlCommand.MAXIMUM_PREDICATES; index++) {
      if (index > 0) {
        predicates.append(" AND ");
      }
      predicates.append('c').append(index).append('=').append(index);
    }
    assertEquals(StatusCode.OK, parser.parse(predicates, command));
    assertEquals(SqlCommand.MAXIMUM_PREDICATES, command.wherePredicates().leafCount());
    assertTrue(command.isAvailable());

    StringBuilder rows = new StringBuilder("INSERT INTO x VALUES ");
    for (int index = 0; index < SqlCommand.MAXIMUM_INSERT_ROWS; index++) {
      if (index > 0) {
        rows.append(',');
      }
      rows.append('(').append(index).append(',').append(index).append(')');
    }
    assertEquals(StatusCode.OK, parser.parse(rows, command));
    assertEquals(SqlCommand.MAXIMUM_INSERT_ROWS, command.insertRowCount());
    assertTrue(command.isAvailable());
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
    assertName("id", compiled.orderColumnName());

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
    assertName("region", command.orderColumnName());
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
    assertName("id", command.orderColumnName());
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
    assertName("id", command.orderColumnName());
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

  private static void assertCompositeIndexColumns(SqlParser parser, SqlCommand command) {
    StringBuilder maximum = new StringBuilder("CREATE UNIQUE INDEX wide ON records(");
    for (int part = 0; part < 32; part++) {
      if (part > 0) maximum.append(',');
      maximum.append('c').append(part + 1);
    }
    maximum.append(')');
    assertEquals(StatusCode.OK, parser.parse(maximum, command));
    assertEquals(32, command.columnCount());
    assertName("c32", command.columnName(31));

    maximum.insert(maximum.length() - 1, ",c33");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(maximum, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX empty ON records()", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX trailing ON records(c1,)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX duplicate ON records(c1,C1)", command));
  }

  private static void assertName(String expected, SqlIdentifier actual) {
    assertText(expected, actual);
  }

  private static void assertSubqueryEdge(
      SqlQuery query,
      int edge,
      int kind,
      int parent,
      int leaf,
      int child,
      int leafTest) {
    assertEquals(kind, query.edgeKind(edge));
    assertEquals(parent, query.edgeParent(edge));
    assertEquals(leaf, query.edgeLeaf(edge));
    assertEquals(child, query.edgeChild(edge));
    assertEquals(parent, query.blockParent(child));
    assertEquals(query.blockDepth(parent) + 1, query.blockDepth(child));
    SqlBooleanPredicateProgram predicates = query.block(parent).wherePredicates();
    assertEquals(leafTest, predicates.leafTest(leaf));
    assertEquals(edge, predicates.subqueryEdge(leaf));
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

  private static void assertBooleanNotOverLeaf(
      SqlBooleanPredicateProgram predicates, int leaf) {
    assertFalse(predicates.leafNegated(leaf));
    int root = predicates.root();
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_NOT, predicates.booleanOperator(root));
    int leafNode = predicates.booleanLeft(root);
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_LEAF,
        predicates.booleanOperator(leafNode));
    assertEquals(leaf, predicates.booleanLeft(leafNode));
  }

  private static void assertPostfix(
      SqlScalarExpression expression, int... operators) {
    assertEquals(operators.length, expression.nodeCount());
    for (int index = 0; index < operators.length; index++) {
      assertEquals(operators[index], expression.operator(index));
    }
  }

  private static SqlScalarExpression predicateExpression(
      SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    int count = predicates.programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    if (count == 1 && predicates.programOperator(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0)
        == SqlScalarExpression.COLUMN) return null;
    SqlScalarExpression expression = new SqlScalarExpression();
    for (int node = 0; node < count; node++) {
      expression.append(
          predicates.programOperator(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node),
          predicates.programOperand(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node),
          predicates.programDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node));
    }
    expression.finish(predicates.programDescriptor(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, count - 1));
    return expression;
  }

  private static SqlComparison predicateComparison(SqlCommand command, int leaf) {
    return command.wherePredicates().comparison(leaf);
  }

  private static int predicateDescriptor(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    int program = predicates.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_BETWEEN
        ? SqlBooleanPredicateProgram.PROGRAM_LOWER
        : SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int count = predicates.programNodeCount(leaf, program);
    if (count > 0) return predicates.programDescriptor(leaf, program, count - 1);
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      int descriptor = predicates.memberDescriptor(leaf, member);
      if (descriptor != 0) return descriptor;
    }
    return 0;
  }

  private static long predicateValue(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT);
  }

  private static long predicateLower(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER);
  }

  private static long predicateUpper(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER);
  }

  private static long predicateProgramValue(
      SqlBooleanPredicateProgram predicates, int leaf, int program) {
    int count = predicates.programNodeCount(leaf, program);
    return count == 0 ? 0 : predicates.programOperand(leaf, program, count - 1);
  }

  private static int predicateProgramDescriptor(
      SqlBooleanPredicateProgram predicates, int leaf, int program) {
    int count = predicates.programNodeCount(leaf, program);
    return count == 0 ? 0 : predicates.programDescriptor(leaf, program, count - 1);
  }

  private static SqlIdentifier predicateColumnName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    return command.predicateSymbolName(symbol);
  }

  private static SqlIdentifier predicateTableName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    return command.predicateSymbolTable(symbol);
  }

  private static SqlIdentifier predicateValueTableName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    return command.predicateSymbolTable(symbol);
  }

  private static SqlIdentifier predicateValueColumnName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    return command.predicateSymbolName(symbol);
  }

  private static boolean isColumnPredicate(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    return predicates.programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT) == 1
        && predicates.programOperator(
            leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0)
        == SqlScalarExpression.COLUMN;
  }

  private static int membershipCount(SqlCommand command, int leaf) {
    return command.wherePredicates().leafMemberCount(leaf);
  }

  private static long membershipValue(SqlCommand command, int leaf, int member) {
    return command.wherePredicates().memberValue(leaf, member);
  }

  private static boolean membershipHasNull(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      if (predicates.memberNull(leaf, member)) return true;
    }
    return false;
  }

  private static SqlComparison havingComparison(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().comparison(leaf);
  }

  private static long havingValue(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.booleanHavingPredicates(),
        leaf,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT);
  }

  private static long havingOperand(SqlCommand command, int leaf, int node) {
    return command.booleanHavingPredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node);
  }

  private static int havingNodeCount(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
  }

  private static int havingMemberCount(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().leafMemberCount(leaf);
  }

  private static boolean havingMembershipHasNull(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.booleanHavingPredicates();
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      if (predicates.memberNull(leaf, member)) return true;
    }
    return false;
  }

  private static void assertHavingPostfix(
      SqlCommand command, int predicate, int... expected) {
    assertEquals(expected.length, havingNodeCount(command, predicate));
    for (int node = 0; node < expected.length; node++) {
      assertEquals(
          expected[node],
          command.booleanHavingPredicates().programOperator(
              predicate, SqlBooleanPredicateProgram.PROGRAM_LEFT, node));
    }
  }

  private static void assertMutationPostfix(
      SqlCommand command, int expression, int... operators) {
    assertEquals(operators.length, command.mutationExpressionNodeCount(expression));
    for (int index = 0; index < operators.length; index++) {
      assertEquals(
          operators[index],
          command.mutationExpressionOperator(expression, index));
    }
  }

  private static void assertText(
      String expected, SqlCommand command, long handle) {
    ByteBuffer bytes = ByteBuffer.allocate(64);
    int length = command.copyText(handle, bytes);
    assertTrue(length >= 0);
    bytes.flip();
    byte[] encoded = new byte[length];
    bytes.get(encoded);
    assertEquals(expected, new String(encoded, StandardCharsets.UTF_8));
  }

  private static void assertText(String expected, CharSequence actual) {
    assertEquals(expected.length(), actual.length());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), actual.charAt(index));
    }
  }

  private static final class TestParameters implements SqlParameterSource {
    private final int[] descriptors;
    private final long[] values;
    private final boolean[] nulls;
    private final String[] texts;

    private TestParameters(
        int[] valueDescriptors,
        long[] fixedValues,
        boolean[] nullValues,
        String[] textValues) {
      descriptors = valueDescriptors;
      values = fixedValues;
      nulls = nullValues;
      texts = textValues;
    }

    private static TestParameters empty() {
      return new TestParameters(new int[0], new long[0], new boolean[0], new String[0]);
    }

    private static TestParameters fixed(int descriptor, long value) {
      return new TestParameters(
          new int[] {descriptor},
          new long[] {value},
          new boolean[] {false},
          new String[] {null});
    }

    @Override
    public int count() {
      return descriptors.length;
    }

    @Override
    public boolean isNull(int index) {
      return nulls[index];
    }

    @Override
    public int typeDescriptorAt(int index) {
      return descriptors[index];
    }

    @Override
    public long valueAt(int index) {
      return values[index];
    }

    @Override
    public int copyTextAt(int index, char[] target, int offset) {
      String value = texts[index];
      if (value == null) {
        return -1;
      }
      value.getChars(0, value.length(), target, offset);
      return value.length();
    }
  }
}
