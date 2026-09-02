package io.riverdb.jdbc;

import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.sql.SQLException;

/** River extension for preparing and invoking one-request atomic transaction programs. */
public interface RiverTransactionPrograms {
  long prepareStatement(String sql) throws SQLException;

  void closeStatement(long handle) throws SQLException;

  long prepareProgram(TransactionProgram program) throws SQLException;

  void executeProgram(
      long handle,
      TransactionProgramArguments arguments,
      TransactionProgramResult result) throws SQLException;

  void closeProgram(long handle) throws SQLException;
}
