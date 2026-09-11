package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;

/** Owns session state while delegating the ordered wire exchange to its connection. */
final class RiverClientRemoteSession implements RiverSession {
  private final RiverClientConnection connection;
  private final RiverClientRemoteQuery query;
  private boolean active;

  RiverClientRemoteSession(RiverClientConnection clientConnection) {
    connection = clientConnection;
    query = new RiverClientRemoteQuery(clientConnection);
  }

  boolean isActive() { return active; }

  @Override
  public StatusCode configureTransactionDiagnostics(
      long requestedDiagnosticTag,
      long requestedDiagnosticStepTag,
      long requestedMetricsEpoch) {
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    if (!RiverClientConnection.validDiagnosticContext(
        requestedDiagnosticTag, requestedDiagnosticStepTag, requestedMetricsEpoch)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    connection.diagnosticTag = requestedDiagnosticTag;
    connection.diagnosticStepTag = requestedDiagnosticStepTag;
    connection.metricsEpoch = requestedMetricsEpoch;
    return StatusCode.OK;
  }

  @Override
  public StatusCode prepare(String sql, PreparedOpenResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    StatusCode status = connection.exchange(ProtocolMessageType.PREPARE, sql);
    if (status.isOk()) status = connection.response.status();
    return status.isOk()
        ? result.complete(connection.response.key(), connection.response.affectedRows(),
            (connection.response.flags() & ProtocolFrameCodec.FLAG_PREPARED_QUERY) != 0)
        : status;
  }

  @Override
  public StatusCode executePrepared(
      long handle, ParameterSet parameters, CommandResult result) {
    if (parameters == null || result == null || handle <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    StatusCode status = connection.exchangePrepared(
        ProtocolMessageType.EXECUTE_PREPARED, handle, parameters);
    if (status.isOk()) status = connection.response.status();
    return status.isOk() ? connection.copyCommand(result) : status;
  }

  @Override
  public StatusCode beginPreparedQuery(
      long handle, ParameterSet parameters, QueryOpenResult result) {
    if (parameters == null || result == null || handle <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    StatusCode status = connection.exchangePrepared(
        ProtocolMessageType.BEGIN_PREPARED_QUERY, handle, parameters);
    return status.isOk() ? query.open(connection.response.status(), result) : status;
  }

  @Override
  public StatusCode closePrepared(long handle) {
    if (handle <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    return connection.exchangePrepared(ProtocolMessageType.CLOSE_PREPARED, handle, null);
  }

  @Override
  public StatusCode prepareProgram(TransactionProgram program, ProgramOpenResult result) {
    return connection.programs.prepare(program, result, active, query.isActive());
  }

  @Override
  public StatusCode executeProgram(
      long programHandle, IsolationLevel isolationLevel,
      TransactionProgramArguments arguments,
      TransactionProgramResult result) {
    return connection.programs.execute(
        programHandle, isolationLevel, arguments, result, active, query.isActive());
  }

  @Override
  public StatusCode closeProgram(long programHandle) {
    return connection.programs.close(programHandle, active, query.isActive());
  }

  @Override
  public StatusCode execute(String sql, CommandResult result) {
    return executeRequest(sql, null, result, false);
  }

  @Override
  public StatusCode execute(String sql, ParameterSet parameters, CommandResult result) {
    return executeRequest(sql, parameters, result, true);
  }

  private StatusCode executeRequest(
      String sql, ParameterSet parameters, CommandResult result, boolean typed) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    if (typed && parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = connection.exchange(
        ProtocolMessageType.EXECUTE, sql, parameters, null, 0);
    if (status.isOk()) status = connection.response.status();
    return status.isOk() ? connection.copyCommand(result) : status;
  }

  @Override
  public StatusCode beginQuery(String sql, QueryOpenResult result) {
    return beginQueryRequest(sql, null, result, false);
  }

  @Override
  public StatusCode beginQuery(
      String sql, ParameterSet parameters, QueryOpenResult result) {
    return beginQueryRequest(sql, parameters, result, true);
  }

  private StatusCode beginQueryRequest(
      String sql, ParameterSet parameters, QueryOpenResult result, boolean typed) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (query.isActive()) return StatusCode.CONFLICT;
    if (typed && parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = connection.exchange(
        ProtocolMessageType.BEGIN_QUERY, sql, parameters, null, 0);
    return status.isOk() ? query.open(connection.response.status(), result) : status;
  }

  @Override
  public StatusCode close() {
    if (!active) return StatusCode.CLOSED;
    StatusCode status = connection.exchange(ProtocolMessageType.CLOSE_SESSION, null);
    if (status.isOk()) status = connection.response.status();
    if (status.isOk()) {
      active = false;
      query.clear();
    }
    return status;
  }

  void resetForOpen() {
    active = true;
    connection.diagnosticTag = 0;
    connection.diagnosticStepTag = 0;
    connection.metricsEpoch = 0;
    query.clear();
  }

  void terminate() {
    active = false;
    query.clear();
  }
}
