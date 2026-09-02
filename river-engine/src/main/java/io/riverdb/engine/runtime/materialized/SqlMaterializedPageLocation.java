package io.riverdb.engine.runtime.materialized;

/** Caller-owned result for one checked logical-stream mapping. */
public final class SqlMaterializedPageLocation {
  private long pageNumber;
  private long filePosition;
  private int payloadOffset;
  private int payloadRemaining;

  void set(long page, long position, int offset, int remaining) {
    pageNumber = page;
    filePosition = position;
    payloadOffset = offset;
    payloadRemaining = remaining;
  }

  public long pageNumber() { return pageNumber; }
  public long filePosition() { return filePosition; }
  public int payloadOffset() { return payloadOffset; }
  public int payloadRemaining() { return payloadRemaining; }
}
