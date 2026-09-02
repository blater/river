package io.riverdb.bench.tpcc;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionScalarOperator;
import java.sql.SQLException;

/** Allocation-free postfix expressions reused by every New-Order program shape. */
final class TpccRiverNewOrderExpressions {
  private TpccRiverNewOrderExpressions() { }

  static void newQuantity(
      TransactionProgram graph, int stockStep, int line, int lines) throws SQLException {
    begin(graph);
    prior(graph, stockStep, 0, SqlTypeDescriptor.SMALLINT);
    argumentValue(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    argumentValue(graph, TpccRiverNewOrderLayout.ten(lines), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.ADD, SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.GREATER_OR_EQUAL, SqlTypeDescriptor.BOOLEAN);
    prior(graph, stockStep, 0, SqlTypeDescriptor.SMALLINT);
    argumentValue(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER);
    prior(graph, stockStep, 0, SqlTypeDescriptor.SMALLINT);
    argumentValue(graph, TpccRiverNewOrderLayout.ninetyOne(lines), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.ADD, SqlTypeDescriptor.INTEGER);
    argumentValue(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.SELECT, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  static void remoteIncrement(TransactionProgram graph, int line, int lines)
      throws SQLException {
    begin(graph);
    argumentValue(graph, TpccRiverNewOrderLayout.supplyWarehouse(line),
        SqlTypeDescriptor.INTEGER);
    argumentValue(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.NOT_EQUAL, SqlTypeDescriptor.BOOLEAN);
    argumentValue(graph, TpccRiverNewOrderLayout.one(lines), SqlTypeDescriptor.INTEGER);
    argumentValue(graph, TpccRiverNewOrderLayout.zero(lines), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.SELECT, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  static void lineAmount(TransactionProgram graph, int itemStep, int line)
      throws SQLException {
    begin(graph);
    prior(graph, itemStep, 0, SqlTypeDescriptor.decimal(5, 2));
    argumentValue(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    operator(graph, TransactionScalarOperator.MULTIPLY, SqlTypeDescriptor.decimal(6, 2));
    end(graph);
  }

  static void argument(TransactionProgram graph, int slot, int descriptor)
      throws SQLException {
    begin(graph);
    argumentValue(graph, slot, descriptor);
    end(graph);
  }

  static void priorParameter(
      TransactionProgram graph, int step, int column, int descriptor) throws SQLException {
    begin(graph);
    prior(graph, step, column, descriptor);
    end(graph);
  }

  private static void begin(TransactionProgram graph) throws SQLException {
    require(graph.beginParameter());
  }

  private static void argumentValue(TransactionProgram graph, int slot, int descriptor)
      throws SQLException {
    require(graph.argument(slot, descriptor));
  }

  private static void prior(
      TransactionProgram graph, int step, int column, int descriptor) throws SQLException {
    require(graph.priorResult(step, column, descriptor));
  }

  private static void operator(TransactionProgram graph, int operator, int descriptor)
      throws SQLException {
    require(graph.operator(operator, descriptor));
  }

  private static void end(TransactionProgram graph) throws SQLException {
    require(graph.endExpression());
  }

  private static void require(io.riverdb.base.error.StatusCode status) throws SQLException {
    TpccRiverStatus.require(status, "New-Order expression construction");
  }
}
