package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolMessageType;

/** Encodes the program lifecycle while reusing the ordered wire exchange. */
final class RiverClientProgramExchange {
  private RiverClientProgramExchange() { }

  static StatusCode prepare(
      RiverClientConnection connection, TransactionProgram program,
      ProgramOpenResult result) {
    long requestId = requestId(connection);
    if (requestId == 0) return connection.lastStatus;
    StatusCode status;
    do {
      status = connection.codec.encodeProgramPrepareRequest(
          connection.request, requestId, program);
    } while (status == StatusCode.RESOURCE_EXHAUSTED
        && connection.growRequestBytes().isOk());
    return status.isOk()
        ? RiverClientWireExchange.programOpen(connection, requestId, result) : status;
  }

  static StatusCode execute(
      RiverClientConnection connection, long handle,
      IsolationLevel isolationLevel, TransactionProgramArguments arguments,
      TransactionProgramResult result) {
    long requestId = requestId(connection);
    if (requestId == 0) return connection.lastStatus;
    StatusCode status;
    do {
      status = connection.codec.encodeProgramExecuteRequest(
          connection.request, requestId, handle, isolationLevel, arguments);
    } while (status == StatusCode.RESOURCE_EXHAUSTED
        && connection.growRequestBytes().isOk());
    return status.isOk()
        ? RiverClientWireExchange.programResult(connection, requestId, result) : status;
  }

  static StatusCode close(RiverClientConnection connection, long handle) {
    long requestId = requestId(connection);
    if (requestId == 0) return connection.lastStatus;
    StatusCode status = connection.codec.encodeProgramCloseRequest(
        connection.request, requestId, handle);
    return status.isOk()
        ? RiverClientWireExchange.standard(
            connection, ProtocolMessageType.CLOSE_PROGRAM, requestId) : status;
  }

  private static long requestId(RiverClientConnection connection) {
    if (connection.closed) {
      connection.lastStatus = StatusCode.CLOSED;
      return 0;
    }
    if (connection.nextRequestId <= 0
        || connection.nextRequestId == Long.MAX_VALUE) {
      connection.fail(StatusCode.FENCED);
      return 0;
    }
    return connection.nextRequestId;
  }
}
