package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlPreparedTemplateTest {
  @Test
  void assignsStatementGlobalOrdinalsAndIgnoresQuotedQuestionMarks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();

    assertEquals(StatusCode.OK, parser.parseTemplate(
        "SELECT '?' AS literal_value, ? AS supplied FROM accounts WHERE id=?",
        query, command));
    assertEquals(2, parser.templateParameterCount());
    assertEquals(SqlScalarExpression.PARAMETER, command.projectionExpression(1).operator(0));
    assertEquals(0, command.projectionExpression(1).operand(0));
    assertEquals(SqlScalarExpression.PARAMETER, command.wherePredicates().programOperator(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
    assertEquals(1, command.wherePredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
  }

  @Test
  void restoresAndMaterializesWithoutChangingTheFrozenTemplate() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parseTemplate(
        "UPDATE accounts SET balance=balance+? WHERE id=?", query, parsed));
    SqlStatementTemplate.Result captured = new SqlStatementTemplate.Result();
    assertEquals(StatusCode.OK, SqlStatementTemplate.capture(
        parsed, query, parser.templateParameterCount(), captured));
    assertEquals(SqlStatementTemplate.estimateByteCharge(parsed, query),
        captured.value().byteCharge());
    assertEquals(2, captured.value().parameterCount());

    SqlCommand invocation = new SqlCommand();
    SqlQuery invocationQuery = new SqlQuery();
    SqlRuntimeParameterBindings parameters = new SqlRuntimeParameterBindings();
    assertEquals(StatusCode.OK, captured.value().restore(invocationQuery, invocation));
    assertEquals(StatusCode.OK, parameters.begin(2, 0));
    assertEquals(StatusCode.OK,
        parameters.set(0, SqlTypeDescriptor.INTEGER, 0, 25, false, 0));
    assertEquals(StatusCode.OK,
        parameters.set(1, SqlTypeDescriptor.INTEGER, 0, 7, false, 0));
    assertEquals(StatusCode.OK, parameters.materialize(invocationQuery, invocation));
    assertTrue(invocation.updateHasExpression(0));
    assertEquals(SqlScalarExpression.LITERAL, invocation.mutationExpressionOperator(0, 1));
    assertEquals(25, invocation.mutationExpressionOperand(0, 1));
    assertEquals(SqlScalarExpression.LITERAL, invocation.wherePredicates().programOperator(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
    assertEquals(7, invocation.wherePredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));

    parameters.reset();
    invocation.reset();
    assertEquals(StatusCode.OK, captured.value().restore(invocationQuery, invocation));
    assertTrue(invocation.updateHasExpression(0));
    assertEquals(SqlScalarExpression.PARAMETER, invocation.mutationExpressionOperator(0, 1));
    assertEquals(0, invocation.mutationExpressionOperand(0, 1));
    assertEquals(SqlScalarExpression.PARAMETER, invocation.wherePredicates().programOperator(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
    assertEquals(1, invocation.wherePredicates().programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0));
  }

  @Test
  void preservesOrderAggregateGroupAndHavingStructure() {
    String sql = "SELECT account_id,SUM(balance) FROM accounts WHERE account_id>? "
        + "GROUP BY account_id HAVING SUM(balance)>? ORDER BY account_id DESC";
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parseTemplate(sql, query, parsed));
    SqlStatementTemplate.Result captured = new SqlStatementTemplate.Result();
    assertEquals(StatusCode.OK, SqlStatementTemplate.capture(
        parsed, query, parser.templateParameterCount(), captured));
    assertEquals(SqlStatementTemplate.estimateByteCharge(parsed, query),
        captured.value().byteCharge());

    SqlQuery restoredQuery = new SqlQuery();
    SqlCommand restored = new SqlCommand();
    assertEquals(StatusCode.OK, captured.value().restore(restoredQuery, restored));
    assertEquals(2, captured.value().parameterCount());
    assertEquals(parsed.type(), restored.type());
    assertEquals(parsed.aggregateInvocationCount(), restored.aggregateInvocationCount());
    assertEquals(parsed.aggregateOutputCount(), restored.aggregateOutputCount());
    assertEquals(parsed.groupExpressionCount(), restored.groupExpressionCount());
    assertEquals(parsed.orderExpressionCount(), restored.orderExpressionCount());
    assertEquals(parsed.isDescendingOrder(0), restored.isDescendingOrder(0));
    assertEquals(parsed.booleanHavingPredicates().leafCount(),
        restored.booleanHavingPredicates().leafCount());
  }

  @Test
  void materializesWarehouseInsertParametersAsDirectTypedCells() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parseTemplate(
        "INSERT INTO warehouse VALUES (?,?,?,?,?,?,?,?,?)", query, parsed));
    SqlStatementTemplate.Result captured = new SqlStatementTemplate.Result();
    assertEquals(StatusCode.OK, SqlStatementTemplate.capture(
        parsed, query, parser.templateParameterCount(), captured));

    SqlCommand invocation = new SqlCommand();
    SqlQuery invocationQuery = new SqlQuery();
    assertEquals(StatusCode.OK, captured.value().restore(invocationQuery, invocation));
    SqlRuntimeParameterBindings parameters = new SqlRuntimeParameterBindings();
    assertEquals(StatusCode.OK, parameters.begin(9, 22));
    assertEquals(StatusCode.OK,
        parameters.set(0, SqlTypeDescriptor.SMALLINT, 0, 1, false, 0));
    setAscii(parameters, 1, "W");
    setAscii(parameters, 2, "one");
    setAscii(parameters, 3, "two");
    setAscii(parameters, 4, "city");
    setAscii(parameters, 5, "LN");
    setAscii(parameters, 6, "123456789");
    assertEquals(StatusCode.OK,
        parameters.set(7, SqlTypeDescriptor.decimal(4, 4), 0, 1_000, false, 0));
    assertEquals(StatusCode.OK,
        parameters.set(8, SqlTypeDescriptor.decimal(8, 2), 0, 30_000_000, false, 0));

    assertEquals(StatusCode.OK, parameters.materialize(invocationQuery, invocation));
    assertEquals(9, invocation.insertColumnCount());
    for (int column = 0; column < 9; column++) {
      assertTrue(!invocation.insertHasExpression(0, column));
    }
    assertEquals(SqlTypeDescriptor.SMALLINT, invocation.insertTypeDescriptor(0, 0));
    assertEquals(SqlTypeDescriptor.varchar(1), invocation.insertTypeDescriptor(0, 1));
    assertEquals(SqlTypeDescriptor.decimal(4, 4), invocation.insertTypeDescriptor(0, 7));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), invocation.insertTypeDescriptor(0, 8));
  }

  @Test
  void preservesStockLevelJoinAndGlobalParameterOrdinals() {
    String sql = "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
        + "INNER JOIN stock s ON s.s_w_id=ol.ol_supply_w_id AND s.s_i_id=ol.ol_i_id "
        + "WHERE ol.ol_w_id=? AND ol.ol_d_id=? AND ol.ol_o_id>=? "
        + "AND ol.ol_o_id<? AND s.s_quantity<?";
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parseTemplate(sql, query, parsed));
    SqlStatementTemplate.Result captured = new SqlStatementTemplate.Result();
    assertEquals(StatusCode.OK, SqlStatementTemplate.capture(
        parsed, query, parser.templateParameterCount(), captured));

    SqlCommand restored = new SqlCommand();
    SqlQuery restoredQuery = new SqlQuery();
    assertEquals(StatusCode.OK, captured.value().restore(restoredQuery, restored));
    assertEquals(5, captured.value().parameterCount());
    assertEquals(SqlCommandType.COUNT_DISTINCT, restored.type());
    assertEquals(2, restoredQuery.blockCount());
    assertEquals(SqlCommandType.JOIN_SCAN, restoredQuery.block(1).type());
    SqlJoinChain joins = restoredQuery.block(1).joinChain();
    assertEquals(2, joins.roleCount());
    assertEquals(1, joins.stageCount());
    assertEquals("order_line", joins.tableName(0).toString());
    assertEquals("ol", joins.alias(0).toString());
    assertEquals("stock", joins.tableName(1).toString());
    assertEquals("s", joins.alias(1).toString());
    assertEquals(2, joins.onPredicates(0).leafCount());

    SqlRuntimeParameterBindings parameters = new SqlRuntimeParameterBindings();
    assertEquals(StatusCode.OK, parameters.begin(5, 0));
    for (int parameter = 0; parameter < 5; parameter++) {
      assertEquals(StatusCode.OK,
          parameters.set(parameter, SqlTypeDescriptor.INTEGER, 0, parameter + 1, false, 0));
    }
    assertEquals(StatusCode.OK, parameters.materialize(restoredQuery, restored));
    assertEquals(5, restoredQuery.block(1).wherePredicates().leafCount());
  }

  private static void setAscii(
      SqlRuntimeParameterBindings parameters, int parameter, String value) {
    assertEquals(StatusCode.OK,
        parameters.set(parameter, SqlTypeDescriptor.varchar(value.length()),
            0, 0, false, value.length()));
    for (int index = 0; index < value.length(); index++) {
      assertEquals(StatusCode.OK,
          parameters.setTextByte(parameter, index, (byte) value.charAt(index)));
    }
  }
}
