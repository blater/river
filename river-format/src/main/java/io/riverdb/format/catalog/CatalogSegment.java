package io.riverdb.format.catalog;

/** Caller-owned decoded catalog segment metadata. */
public final class CatalogSegment {
  private int kind;
  private int tableId;
  private int ordinal;
  private int segmentCount;
  private int payloadBytes;
  private long generation;

  void set(int recordKind, int ownerTableId, int index, int count, int bytes, long value) {
    kind = recordKind;
    tableId = ownerTableId;
    ordinal = index;
    segmentCount = count;
    payloadBytes = bytes;
    generation = value;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0); }
  public int kind() { return kind; }
  public int tableId() { return tableId; }
  public int ordinal() { return ordinal; }
  public int segmentCount() { return segmentCount; }
  public int payloadBytes() { return payloadBytes; }
  public long generation() { return generation; }
}
