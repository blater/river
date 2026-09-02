package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates the nodes of one bound nested projection against mixed row sources. */
final class SqlNestedProjectionNodes {
  private SqlNestedProjectionNodes() { }

  static StatusCode evaluate(
      SqlRowExpressionEvaluator machine,
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      SqlTemporalZonePlan zone,
      SqlNestedRowProvider rows) {
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < programs.nodeCount(0); node++) {
      int operator = programs.operator(0, node);
      int scope = programs.scope(0, node);
      int block = SqlNestedRowProvider.block(scope);
      int role = SqlNestedRowProvider.role(scope);
      status = operator == SqlScalarExpression.COLUMN
          ? SqlNestedColumnValue.evaluate(
              machine,
              command,
              zone,
              rows,
              block,
              role,
              (int) programs.operand(0, node),
              programs.descriptor(0, node))
          : machine.predicateOperandNode(
              command,
              operator,
              programs.operandHigh(0, node),
              programs.operand(0, node),
              programs.descriptor(0, node),
              zone,
              0,
              null,
              null,
              null);
    }
    return status;
  }
}
