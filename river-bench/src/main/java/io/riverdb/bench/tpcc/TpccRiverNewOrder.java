package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.jdbc.RiverTransactionPrograms;
import java.sql.Connection;
import java.sql.SQLException;

/** River-native one-request implementation of the complete New-Order transaction. */
final class TpccRiverNewOrder implements AutoCloseable {
  static final int FAILURE_KINDS = 11;

  private final RiverTransactionPrograms programs;
  private final TpccRiverProgramResources resources;
  private final TpccRiverNewOrderArguments arguments = new TpccRiverNewOrderArguments();
  private final TransactionProgramResult result = new TransactionProgramResult();
  private final long[] programByLines = new long[
      TpccRiverNewOrderLayout.MAXIMUM_LINES - TpccRiverNewOrderLayout.MINIMUM_LINES + 1];
  private final long[] failures = new long[FAILURE_KINDS];
  private final int district;
  private final int maximumItem;

  TpccRiverNewOrder(Connection connection, int districtId, int maximumItemId)
      throws SQLException {
    if (maximumItemId < 1) throw new SQLException("maximum item ID must be positive", "22003");
    programs = connection.unwrap(RiverTransactionPrograms.class);
    resources = new TpccRiverProgramResources(programs);
    district = districtId;
    maximumItem = maximumItemId;
    try {
      TpccRiverNewOrderStatements statements =
          new TpccRiverNewOrderStatements(resources, districtId);
      for (int lines = TpccRiverNewOrderLayout.MINIMUM_LINES;
          lines <= TpccRiverNewOrderLayout.MAXIMUM_LINES; lines++) {
        programByLines[index(lines)] = prepare(statements, lines);
      }
    } catch (SQLException failure) {
      resources.closeAfter(failure);
      throw failure;
    }
  }

  boolean execute(TpccInputs.NewOrder input) throws SQLException {
    if (input == null) throw new SQLException("New-Order input is required", "22023");
    if (input.district != district) {
      throw new SQLException("New-Order district differs from prepared program", "22023");
    }
    long handle = programByLines[index(input.lines)];
    try {
      programs.executeProgram(handle, arguments.bind(input), result);
    } catch (SQLException failure) {
      if (expectedInvalidItem(input, failure)) return false;
      recordFailure(input.lines, failure);
      throw failure;
    }
    validateCommitted(input.lines);
    return true;
  }

  @Override
  public void close() throws SQLException {
    SQLException failure = null;
    try {
      resources.close();
    } catch (SQLException closeFailure) {
      failure = closeFailure;
    }
    try {
      arguments.release();
    } catch (SQLException closeFailure) {
      failure = append(failure, closeFailure);
    }
    StatusCode released = result.release();
    if (!released.isOk()) {
      failure = append(failure, new SQLException(
          "release New-Order result failed: " + released, "HY000", released.stableCode()));
    }
    if (failure != null) throw failure;
  }

  private long prepare(TpccRiverNewOrderStatements statements, int lines)
      throws SQLException {
    TransactionProgram graph = TpccRiverNewOrderGraph.build(statements, lines);
    return resources.prepareProgram(graph, "release New-Order graph");
  }

  private boolean expectedInvalidItem(TpccInputs.NewOrder input, SQLException failure) {
    int last = input.lines - 1;
    return last >= 0
        && (long) input.item[last] == (long) maximumItem + 1
        && failure.getErrorCode() == StatusCode.CARDINALITY_VIOLATION.stableCode()
        && result.primaryStatus() == StatusCode.CARDINALITY_VIOLATION
        && result.failingStep() == TpccRiverNewOrderLayout.itemStep(last)
        && result.rollbackStatus() == StatusCode.OK
        && !result.sessionFenced();
  }

  long failureCount(int kind) {
    return kind >= 0 && kind < failures.length ? failures[kind] : 0;
  }

  static String failureName(int kind) {
    return switch (kind) {
      case 0 -> "warehouse-read";
      case 1 -> "district-lock";
      case 2 -> "customer-read";
      case 3 -> "district-update";
      case 4 -> "order-insert";
      case 5 -> "new-order-insert";
      case 6 -> "item-read";
      case 7 -> "stock-lock";
      case 8 -> "stock-update";
      case 9 -> "order-line-insert";
      case 10 -> "commit";
      default -> "unknown";
    };
  }

  private void recordFailure(int lines, SQLException failure) {
    StatusCode primary = result.primaryStatus();
    if (primary.isOk() || failure.getErrorCode() != primary.stableCode()) return;
    int step = result.failingStep();
    int kind = step == TpccRiverNewOrderLayout.stepCount(lines)
        ? 10 : step < 0 ? -1 : step < TpccRiverNewOrderLayout.HEADER_STEPS
            ? step : TpccRiverNewOrderLayout.HEADER_STEPS
                + (step - TpccRiverNewOrderLayout.HEADER_STEPS)
                    % TpccRiverNewOrderLayout.STEPS_PER_LINE;
    if (kind >= 0 && kind < failures.length && failures[kind] != Long.MAX_VALUE) {
      failures[kind]++;
    }
  }

  private void validateCommitted(int lines) throws SQLException {
    if (result.primaryStatus() != StatusCode.OK || result.rollbackStatus() != StatusCode.OK
        || result.sessionFenced() || result.commitSequence() <= 0
        || result.stepCount() != TpccRiverNewOrderLayout.stepCount(lines)) {
      throw new SQLException("New-Order program returned an invalid committed result", "HY000");
    }
  }

  private static int index(int lines) throws SQLException {
    if (!TpccRiverNewOrderLayout.validLines(lines)) {
      throw new SQLException("TPC-C New-Order line count is outside [5,15]", "22003");
    }
    return lines - TpccRiverNewOrderLayout.MINIMUM_LINES;
  }

  private static SQLException append(SQLException primary, SQLException added) {
    if (primary == null) return added;
    primary.addSuppressed(added);
    return primary;
  }
}
