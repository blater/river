package io.riverdb.bench.tpcc;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import java.sql.SQLException;

/** Builds one immutable generic New-Order graph for an admitted TPC-C line count. */
final class TpccRiverNewOrderGraph {
  private static final int ORDER_ID_STEP = 1;

  private TpccRiverNewOrderGraph() { }

  static TransactionProgram build(TpccRiverNewOrderStatements sql, int lines)
      throws SQLException {
    if (!TpccRiverNewOrderLayout.validLines(lines)) {
      throw new SQLException("TPC-C New-Order line count is outside [5,15]", "22003");
    }
    TransactionProgram graph = new TransactionProgram();
    warehouse(graph, sql.warehouse);
    district(graph, sql.district);
    customer(graph, sql.customer);
    advanceDistrict(graph, sql.advanceDistrict);
    insertOrder(graph, sql.insertOrder);
    insertNewOrder(graph, sql.insertNewOrder);
    for (int line = 0; line < lines; line++) addLine(graph, sql, line, lines);
    require(graph.freeze());
    return graph;
  }

  private static void warehouse(TransactionProgram graph, long handle) throws SQLException {
    begin(graph, handle, TransactionProgramAction.EXACT_ONE);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void district(TransactionProgram graph, long handle) throws SQLException {
    begin(graph, handle, TransactionProgramAction.EXACT_ONE);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void customer(TransactionProgram graph, long handle) throws SQLException {
    begin(graph, handle, TransactionProgramAction.EXACT_ONE);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.CUSTOMER, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void advanceDistrict(TransactionProgram graph, long handle)
      throws SQLException {
    beginOneRowCommand(graph, handle);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void insertOrder(TransactionProgram graph, long handle) throws SQLException {
    beginOneRowCommand(graph, handle);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    prior(graph, ORDER_ID_STEP, 1, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.CUSTOMER, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.ENTRY, SqlTypeDescriptor.timestamp(6));
    argument(graph, TpccRiverNewOrderLayout.LINE_COUNT, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.ALL_LOCAL, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void insertNewOrder(TransactionProgram graph, long handle)
      throws SQLException {
    beginOneRowCommand(graph, handle);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    prior(graph, ORDER_ID_STEP, 1, SqlTypeDescriptor.INTEGER);
    end(graph);
  }

  private static void addLine(
      TransactionProgram graph, TpccRiverNewOrderStatements sql, int line, int lines)
      throws SQLException {
    int itemStep = graph.stepCount();
    begin(graph, sql.item, TransactionProgramAction.EXACT_ONE);
    argument(graph, TpccRiverNewOrderLayout.item(line), SqlTypeDescriptor.INTEGER);
    end(graph);

    int stockStep = graph.stepCount();
    begin(graph, sql.stock, TransactionProgramAction.EXACT_ONE);
    argument(graph, TpccRiverNewOrderLayout.supplyWarehouse(line), SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.item(line), SqlTypeDescriptor.INTEGER);
    end(graph);

    beginOneRowCommand(graph, sql.updateStock);
    TpccRiverNewOrderExpressions.newQuantity(graph, stockStep, line, lines);
    argument(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    TpccRiverNewOrderExpressions.remoteIncrement(graph, line, lines);
    argument(graph, TpccRiverNewOrderLayout.supplyWarehouse(line), SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.item(line), SqlTypeDescriptor.INTEGER);
    end(graph);

    beginOneRowCommand(graph, sql.insertLine);
    argument(graph, TpccRiverNewOrderLayout.WAREHOUSE, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.DISTRICT, SqlTypeDescriptor.INTEGER);
    prior(graph, ORDER_ID_STEP, 1, SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.lineNumber(line), SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.item(line), SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.supplyWarehouse(line), SqlTypeDescriptor.INTEGER);
    argument(graph, TpccRiverNewOrderLayout.quantity(line), SqlTypeDescriptor.INTEGER);
    TpccRiverNewOrderExpressions.lineAmount(graph, itemStep, line);
    prior(graph, stockStep, 1, SqlTypeDescriptor.varchar(24));
    end(graph);
  }

  private static void begin(TransactionProgram graph, long handle, int action)
      throws SQLException {
    require(graph.beginStep(handle, action));
  }

  private static void beginOneRowCommand(TransactionProgram graph, long handle)
      throws SQLException {
    begin(graph, handle, TransactionProgramAction.COMMAND);
    require(graph.requireAffectedRows(1, 1));
  }

  private static void argument(TransactionProgram graph, int slot, int descriptor)
      throws SQLException {
    TpccRiverNewOrderExpressions.argument(graph, slot, descriptor);
  }

  private static void prior(
      TransactionProgram graph, int step, int column, int descriptor) throws SQLException {
    TpccRiverNewOrderExpressions.priorParameter(graph, step, column, descriptor);
  }

  private static void end(TransactionProgram graph) throws SQLException {
    require(graph.endStep());
  }

  private static void require(io.riverdb.base.error.StatusCode status) throws SQLException {
    TpccRiverStatus.require(status, "New-Order graph construction");
  }
}
