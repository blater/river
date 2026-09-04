package io.riverdb.jdbc;

import java.sql.SQLException;

/** River JDBC extension for allocation-stable transaction-attempt diagnostics. */
public interface RiverTransactionDiagnostics {
  /** Selects the opaque attempt and phase tags used by subsequent statements. */
  void beginDiagnosticAttempt(long diagnosticTag, long metricsEpoch) throws SQLException;

  /** Selects the opaque operation tag used by the next statement or program action. */
  void diagnosticStep(long diagnosticStepTag) throws SQLException;

  /** Returns the operation tag most recently selected by this connection. */
  long diagnosticStepTag() throws SQLException;
}
