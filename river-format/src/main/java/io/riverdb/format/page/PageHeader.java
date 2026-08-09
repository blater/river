package io.riverdb.format.page;

/** Caller-owned decoded v1 page metadata. */
public final class PageHeader {
  private long databaseHigh;
  private long databaseLow;
  private long walGeneration;
  private long pageId;
  private long pageGeneration;
  private long recordStart;
  private long recordEnd;
  private int payloadBytes;

  public long databaseHigh() {
    return databaseHigh;
  }

  public long databaseLow() {
    return databaseLow;
  }

  public long walGeneration() {
    return walGeneration;
  }

  public long pageId() {
    return pageId;
  }

  public long pageGeneration() {
    return pageGeneration;
  }

  public long recordStart() {
    return recordStart;
  }

  public long recordEnd() {
    return recordEnd;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public void set(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long localWalGeneration,
      long physicalPageId,
      long physicalPageGeneration,
      long walRecordStart,
      long walRecordEnd,
      int bodyBytes) {
    databaseHigh = databaseIncarnationHigh;
    databaseLow = databaseIncarnationLow;
    walGeneration = localWalGeneration;
    pageId = physicalPageId;
    pageGeneration = physicalPageGeneration;
    recordStart = walRecordStart;
    recordEnd = walRecordEnd;
    payloadBytes = bodyBytes;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0);
  }
}
