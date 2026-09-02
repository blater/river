package io.riverdb.bench.tpcc;

import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.base.error.StatusCode;
import io.riverdb.jdbc.RiverTransactionPrograms;
import java.sql.SQLException;
import java.util.Arrays;

/** Owns one River program adapter's statement and program handles. */
final class TpccRiverProgramResources implements AutoCloseable {
  private final RiverTransactionPrograms programs;
  private long[] statements = new long[0];
  private long[] compiled = new long[0];
  private int statementCount;
  private int programCount;

  TpccRiverProgramResources(RiverTransactionPrograms owner) {
    programs = owner;
  }

  long prepareStatement(String sql) throws SQLException {
    statements = ensureCapacity(statements, statementCount, "statement handle");
    long handle = programs.prepareStatement(sql);
    statements[statementCount++] = handle;
    return handle;
  }

  long prepareProgram(TransactionProgram graph, String operation) throws SQLException {
    compiled = ensureCapacity(compiled, programCount, "program handle");
    long handle;
    try {
      handle = programs.prepareProgram(graph);
    } catch (SQLException failure) {
      StatusCode released = graph.release();
      if (!released.isOk()) {
        failure.addSuppressed(TpccRiverStatus.failure(released, operation));
      }
      throw failure;
    }
    compiled[programCount++] = handle;
    TpccRiverStatus.require(graph.release(), operation);
    return handle;
  }

  void closeAfter(SQLException primary) {
    try {
      close();
    } catch (SQLException closeFailure) {
      primary.addSuppressed(closeFailure);
    }
  }

  @Override
  public void close() throws SQLException {
    SQLException failure = closePrograms(null);
    if (programCount == 0) failure = closeStatements(failure);
    if (failure != null) throw failure;
  }

  private SQLException closePrograms(SQLException failure) {
    int write = programCount;
    for (int index = programCount - 1; index >= 0; index--) {
      try {
        programs.closeProgram(compiled[index]);
        compiled[index] = 0;
      } catch (SQLException closeFailure) {
        compiled[--write] = compiled[index];
        failure = appendFailure(failure, closeFailure);
      }
    }
    int retained = programCount - write;
    if (retained > 0) System.arraycopy(compiled, write, compiled, 0, retained);
    Arrays.fill(compiled, retained, programCount, 0);
    programCount = retained;
    return failure;
  }

  private SQLException closeStatements(SQLException failure) {
    int write = statementCount;
    for (int index = statementCount - 1; index >= 0; index--) {
      try {
        programs.closeStatement(statements[index]);
        statements[index] = 0;
      } catch (SQLException closeFailure) {
        statements[--write] = statements[index];
        failure = appendFailure(failure, closeFailure);
      }
    }
    int retained = statementCount - write;
    if (retained > 0) System.arraycopy(statements, write, statements, 0, retained);
    Arrays.fill(statements, retained, statementCount, 0);
    statementCount = retained;
    return failure;
  }

  private static long[] ensureCapacity(long[] values, int index, String resource)
      throws SQLException {
    if (index < values.length) return values;
    try {
      return Arrays.copyOf(values, Math.max(4, index + (index >> 1) + 1));
    } catch (OutOfMemoryError failure) {
      throw new SQLException(
          "cannot retain " + resource, "HY001",
          io.riverdb.base.error.StatusCode.RESOURCE_EXHAUSTED.stableCode(), failure);
    }
  }

  private static SQLException appendFailure(SQLException primary, SQLException added) {
    if (primary == null) return added;
    primary.addSuppressed(added);
    return primary;
  }
}
