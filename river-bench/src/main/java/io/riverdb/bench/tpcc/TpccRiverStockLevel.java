package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.api.TransactionScalarOperator;
import io.riverdb.jdbc.RiverTransactionPrograms;
import java.sql.Connection;
import java.sql.SQLException;

/** River-native one-request Stock-Level transaction program. */
final class TpccRiverStockLevel implements AutoCloseable {
  private static final int WAREHOUSE = 0;
  private static final int DISTRICT = 1;
  private static final int THRESHOLD = 2;
  private static final int ONE = 3;
  private static final int TWENTY = 4;

  private final RiverTransactionPrograms programs;
  private final TpccRiverProgramResources resources;
  private final long program;
  private final TransactionProgramArguments arguments = new TransactionProgramArguments();
  private final TransactionProgramResult result = new TransactionProgramResult();

  TpccRiverStockLevel(Connection connection) throws SQLException {
    programs = connection.unwrap(RiverTransactionPrograms.class);
    resources = new TpccRiverProgramResources(programs);
    try {
      long next = resources.prepareStatement(
          "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=?");
      long low = resources.prepareStatement(
          "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
              + "INNER JOIN stock s ON s.s_w_id=ol.ol_w_id "
              + "AND s.s_i_id=ol.ol_i_id WHERE ol.ol_w_id=? AND ol.ol_d_id=? "
              + "AND ol.ol_o_id>=? AND ol.ol_o_id<? AND s.s_quantity<?");
      program = resources.prepareProgram(
          build(next, low), "release Stock-Level graph");
    } catch (SQLException failure) {
      resources.closeAfter(failure);
      throw failure;
    }
  }

  boolean execute(TpccInputs.StockLevel input) throws SQLException {
    arguments.reset();
    require(arguments.setFixed(WAREHOUSE, SqlTypeDescriptor.INTEGER, input.warehouse));
    require(arguments.setFixed(DISTRICT, SqlTypeDescriptor.INTEGER, input.district));
    require(arguments.setFixed(THRESHOLD, SqlTypeDescriptor.INTEGER, input.threshold));
    require(arguments.setFixed(ONE, SqlTypeDescriptor.INTEGER, 1));
    require(arguments.setFixed(TWENTY, SqlTypeDescriptor.INTEGER, 20));
    programs.executeProgram(program, arguments, result);
    if (result.stepCount() != 2 || result.rowCount() != 1
        || result.columnCount(0) != 1 || result.isNull(0, 0)
        || result.valueAt(0, 0) < 0) {
      throw new SQLException("stock-level program returned an invalid result", "21000");
    }
    return true;
  }

  long lowStockCount() { return result.valueAt(0, 0); }

  @Override
  public void close() throws SQLException {
    SQLException failure = null;
    try {
      resources.close();
    } catch (SQLException closeFailure) {
      failure = closeFailure;
    }
    StatusCode released = arguments.release();
    if (!released.isOk()) {
      failure = append(failure, TpccRiverStatus.failure(
          released, "release Stock-Level arguments"));
    }
    released = result.release();
    if (!released.isOk()) {
      failure = append(failure, TpccRiverStatus.failure(
          released, "release Stock-Level result"));
    }
    if (failure != null) throw failure;
  }

  private static SQLException append(SQLException primary, SQLException added) {
    if (primary == null) return added;
    primary.addSuppressed(added);
    return primary;
  }

  private static TransactionProgram build(long nextOrder, long lowStock) throws SQLException {
    TransactionProgram graph = new TransactionProgram();
    require(graph.beginStep(nextOrder, TransactionProgramAction.EXACT_ONE));
    argument(graph, WAREHOUSE);
    argument(graph, DISTRICT);
    require(graph.endStep());
    require(graph.beginStep(lowStock, TransactionProgramAction.EXACT_ONE));
    argument(graph, WAREHOUSE);
    argument(graph, DISTRICT);
    lowerOrderBound(graph);
    priorNextOrder(graph);
    argument(graph, THRESHOLD);
    require(graph.captureColumn(0));
    require(graph.endStep());
    require(graph.freeze());
    return graph;
  }

  private static void lowerOrderBound(TransactionProgram graph) throws SQLException {
    require(graph.beginParameter());
    priorNextOrderValue(graph);
    argumentValue(graph, TWENTY);
    require(graph.operator(TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER));
    argumentValue(graph, ONE);
    require(graph.operator(TransactionScalarOperator.GREATER, SqlTypeDescriptor.BOOLEAN));
    priorNextOrderValue(graph);
    argumentValue(graph, TWENTY);
    require(graph.operator(TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER));
    argumentValue(graph, ONE);
    require(graph.operator(TransactionScalarOperator.SELECT, SqlTypeDescriptor.INTEGER));
    require(graph.endExpression());
  }

  private static void priorNextOrder(TransactionProgram graph) throws SQLException {
    require(graph.beginParameter());
    priorNextOrderValue(graph);
    require(graph.endExpression());
  }

  private static void argument(TransactionProgram graph, int slot) throws SQLException {
    require(graph.beginParameter());
    argumentValue(graph, slot);
    require(graph.endExpression());
  }

  private static void argumentValue(TransactionProgram graph, int slot) throws SQLException {
    require(graph.argument(slot, SqlTypeDescriptor.INTEGER));
  }

  private static void priorNextOrderValue(TransactionProgram graph) throws SQLException {
    require(graph.priorResult(0, 0, SqlTypeDescriptor.INTEGER));
  }

  private static void require(io.riverdb.base.error.StatusCode status) throws SQLException {
    TpccRiverStatus.require(status, "stock-level program construction");
  }

}
