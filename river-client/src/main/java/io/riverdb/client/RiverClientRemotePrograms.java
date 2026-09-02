package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;

/** Applies remote-session state policy around the shared program exchange. */
final class RiverClientRemotePrograms {
  private final RiverClientConnection connection;

  RiverClientRemotePrograms(RiverClientConnection clientConnection) {
    connection = clientConnection;
  }

  StatusCode prepare(
      TransactionProgram program, ProgramOpenResult result,
      boolean active, boolean queryActive) {
    if (program == null || result == null || !program.isFrozen()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (queryActive) return StatusCode.CONFLICT;
    synchronized (connection) {
      StatusCode status = RiverClientProgramExchange.prepare(connection, program, result);
      return status.isOk() ? connection.codec.decodedProgramOpenStatus() : status;
    }
  }

  StatusCode execute(
      long handle, TransactionProgramArguments arguments,
      TransactionProgramResult result, boolean active, boolean queryActive) {
    if (handle <= 0 || arguments == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (!active) return StatusCode.CLOSED;
    if (queryActive) return StatusCode.CONFLICT;
    StatusCode status;
    synchronized (connection) {
      status = RiverClientProgramExchange.execute(connection, handle, arguments, result);
      if (status.isOk()) status = connection.codec.decodedProgramResultStatus();
    }
    if (result.sessionFenced()) connection.fail(StatusCode.FENCED);
    return status;
  }

  StatusCode close(long handle, boolean active, boolean queryActive) {
    if (handle <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!active) return StatusCode.CLOSED;
    if (queryActive) return StatusCode.CONFLICT;
    synchronized (connection) {
      StatusCode status = RiverClientProgramExchange.close(connection, handle);
      return status.isOk() ? connection.response.status() : status;
    }
  }
}
