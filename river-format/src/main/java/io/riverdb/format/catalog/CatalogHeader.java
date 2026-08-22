package io.riverdb.format.catalog;

/** Caller-owned decoded catalog header. */
public final class CatalogHeader {
  private int kind;
  private int tableId;
  private int keyKind;
  private int keyArity;
  private int columnCount;
  private long generation;
  private long firstSegmentKey;
  private int segmentCount;
  private int payloadBytes;

  void set(
      int recordKind,
      int ownerTableId,
      int tableKeyKind,
      int tableKeyArity,
      int columns,
      long recordGeneration,
      long firstKey,
      int segments,
      int bytes) {
    kind = recordKind;
    tableId = ownerTableId;
    keyKind = tableKeyKind;
    keyArity = tableKeyArity;
    columnCount = columns;
    generation = recordGeneration;
    firstSegmentKey = firstKey;
    segmentCount = segments;
    payloadBytes = bytes;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int kind() { return kind; }
  public int tableId() { return tableId; }
  public int keyKind() { return keyKind; }
  public int keyArity() { return keyArity; }
  public int columnCount() { return columnCount; }
  public long generation() { return generation; }
  public long firstSegmentKey() { return firstSegmentKey; }
  public int segmentCount() { return segmentCount; }
  public int payloadBytes() { return payloadBytes; }
}
