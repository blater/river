package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;

/** Reusable decoded response with at most the engine API's bounded value count. */
public final class ProtocolResponse {
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private StatusCode status;
  private int flags;
  private int affectedRows;
  private int columnCount;
  private long commitSequence;
  private long key;
  private long rowsReturned;

  public void reset() {
    status = null;
    flags = 0;
    affectedRows = 0;
    columnCount = 0;
    commitSequence = 0;
    key = 0;
    rowsReturned = 0;
  }

  void complete(
      StatusCode responseStatus,
      int responseFlags,
      int rows,
      int columns,
      long committedAt,
      long rowKey,
      long returned) {
    status = responseStatus;
    flags = responseFlags;
    affectedRows = rows;
    columnCount = columns;
    commitSequence = committedAt;
    key = rowKey;
    rowsReturned = returned;
  }

  void valueAt(int index, long value) {
    values[index] = value;
  }

  public StatusCode status() {
    return status;
  }

  public int flags() {
    return flags;
  }

  public boolean rowAvailable() {
    return (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
  }

  public boolean transactionActive() {
    return (flags & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) != 0;
  }

  public boolean queryActive() {
    return (flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public int columnCount() {
    return columnCount;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public long key() {
    return key;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public long valueAt(int index) {
    return index >= 0 && index < columnCount ? values[index] : 0;
  }
}
