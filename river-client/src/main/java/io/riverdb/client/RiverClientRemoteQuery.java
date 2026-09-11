package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import io.riverdb.protocol.ProtocolMessageType;

/** Owns one remote query's streaming state and completion cleanup. */
final class RiverClientRemoteQuery implements RiverQuery {
  private final RiverClientConnection connection;
  private final RiverClientQueryMetadata metadata = new RiverClientQueryMetadata();
  private long rowsReturned;
  private boolean active;
  private boolean serverActive;
  private boolean prefetched;
  private int completionRows;
  private long completionSequence;
  private boolean completionTransactionActive;

  RiverClientRemoteQuery(RiverClientConnection clientConnection) {
    connection = clientConnection;
  }

  StatusCode open(StatusCode status, QueryOpenResult result) {
    serverActive = connection.response.queryActive();
    boolean opened = status.isOk();
    if (opened) status = metadata.prepare(connection.response);
    if (status.isOk()) {
      active = true;
      rowsReturned = 0;
      prefetched = connection.response.rowAvailable();
      if (connection.response.endOfStream()) captureCompletion();
      status = result.complete(this);
    }
    return !status.isOk() && (opened || serverActive)
        ? cleanupFailedOpen(status) : status;
  }

  @Override
  public StatusCode next(RowResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!connection.sessionActive() || !active) return StatusCode.CLOSED;
    StatusCode status = connection.results.reserve(metadata.columnCount());
    if (status.isOk()) status = result.reserve(metadata, null);
    if (!status.isOk()) return status;
    if (prefetched) {
      prefetched = false;
      return copyStagedRow(result);
    }
    if (!serverActive) return StatusCode.OK;
    status = connection.exchange(ProtocolMessageType.FETCH, null);
    if (status.isOk()) {
      status = connection.response.status();
      serverActive = connection.response.queryActive();
      if (connection.response.endOfStream()) captureCompletion();
    }
    if (!status.isOk()) return status;
    if (!connection.response.rowAvailable()) {
      return connection.fail(StatusCode.CORRUPTION);
    }
    return copyStagedRow(result);
  }

  private StatusCode copyStagedRow(RowResult result) {
    if (rowsReturned == Long.MAX_VALUE
        || connection.response.rowsReturned() != rowsReturned + 1) {
      return connection.fail(StatusCode.CORRUPTION);
    }
    int columns = connection.response.columnCount();
    if (!metadata.matches(connection.response, columns)) {
      return connection.fail(StatusCode.CORRUPTION);
    }
    StatusCode status = connection.results.copyRow(
        connection.response, result, metadata.descriptors(), columns);
    if (status.isOk()) rowsReturned = connection.response.rowsReturned();
    return status;
  }

  @Override
  public StatusCode close(CommandResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!connection.sessionActive() || !active) return StatusCode.CLOSED;
    StatusCode status = StatusCode.OK;
    if (serverActive) {
      status = connection.exchange(ProtocolMessageType.CLOSE_QUERY, null);
      if (status.isOk()) status = connection.response.status();
      if (status.isOk()
          && (connection.response.queryActive() || connection.response.rowAvailable())) {
        status = connection.fail(StatusCode.CORRUPTION);
      }
      if (status.isOk()) captureCompletion();
    }
    status = serverActive || !status.isOk() ? status : completeLocally(result);
    if (status.isOk()) clear();
    return status;
  }

  @Override
  public boolean isActive() { return active; }

  @Override
  public QueryMetadata metadata() { return metadata; }

  @Override
  public int columnCount() { return metadata.columnCount(); }

  @Override
  public CharSequence columnName(int index) { return metadata.columnName(index); }

  @Override
  public int columnTypeDescriptor(int index) {
    return metadata.columnTypeDescriptor(index);
  }

  @Override
  public boolean columnIsNullable(int index) {
    return metadata.columnIsNullable(index);
  }

  @Override
  public long rowsReturned() { return rowsReturned; }

  void clear() {
    active = false;
    serverActive = false;
    prefetched = false;
    rowsReturned = 0;
    completionRows = 0;
    completionSequence = 0;
    completionTransactionActive = false;
    metadata.clear();
  }

  private StatusCode cleanupFailedOpen(StatusCode failure) {
    StatusCode cleanup = StatusCode.OK;
    if (serverActive) {
      cleanup = connection.exchange(ProtocolMessageType.CLOSE_QUERY, null);
      if (cleanup.isOk()) cleanup = connection.response.status();
    }
    clear();
    return cleanup.isOk() ? failure : connection.fail(cleanup);
  }

  private void captureCompletion() {
    serverActive = false;
    completionRows = connection.response.affectedRows();
    completionSequence = connection.response.commitSequence();
    completionTransactionActive = connection.response.transactionActive();
  }

  private StatusCode completeLocally(CommandResult result) {
    return result.complete(
        completionRows, completionSequence, completionTransactionActive,
        false, 0, null, 0, null, 0);
  }
}
