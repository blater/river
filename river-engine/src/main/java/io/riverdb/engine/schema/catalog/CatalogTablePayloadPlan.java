package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Reusable exact chunk layout produced before catalog record-ID reservation. */
public final class CatalogTablePayloadPlan {
  private static final int INITIAL_CAPACITY = 8;

  private byte[] kinds = new byte[INITIAL_CAPACITY];
  private int[] itemStarts = new int[INITIAL_CAPACITY];
  private int[] itemCounts = new int[INITIAL_CAPACITY];
  private int[] logicalStarts = new int[INITIAL_CAPACITY];
  private int[] logicalCounts = new int[INITIAL_CAPACITY];
  private int[] payloadBytes = new int[INITIAL_CAPACITY];
  private int chunkCount;
  private int totalPayloadBytes;

  StatusCode add(
      int kind, int itemStart, int itemCount,
      int logicalStart, int logicalCount, int bytes) {
    if (!ensureCapacity(chunkCount + 1)) return StatusCode.RESOURCE_EXHAUSTED;
    kinds[chunkCount] = (byte) kind;
    itemStarts[chunkCount] = itemStart;
    itemCounts[chunkCount] = itemCount;
    logicalStarts[chunkCount] = logicalStart;
    logicalCounts[chunkCount] = logicalCount;
    payloadBytes[chunkCount] = bytes;
    chunkCount++;
    totalPayloadBytes += bytes;
    return StatusCode.OK;
  }

  public void reset() {
    chunkCount = 0;
    totalPayloadBytes = 0;
  }

  public int chunkCount() { return chunkCount; }
  public int kindAt(int index) { return valid(index) ? kinds[index] : 0; }
  public int itemStartAt(int index) { return valid(index) ? itemStarts[index] : -1; }
  public int itemCountAt(int index) { return valid(index) ? itemCounts[index] : 0; }
  public int logicalStartAt(int index) { return valid(index) ? logicalStarts[index] : -1; }
  public int logicalCountAt(int index) { return valid(index) ? logicalCounts[index] : 0; }
  public int payloadBytesAt(int index) { return valid(index) ? payloadBytes[index] : 0; }
  public int totalPayloadBytes() { return totalPayloadBytes; }

  private boolean valid(int index) { return index >= 0 && index < chunkCount; }

  private boolean ensureCapacity(int required) {
    if (required <= kinds.length) return true;
    if (required > SqlShapeLimits.MAX_SCHEMA_CHUNKS) return false;
    int capacity = Math.min(
        SqlShapeLimits.MAX_SCHEMA_CHUNKS, Math.max(required, kinds.length * 2));
    try {
      byte[] grownKinds = new byte[capacity];
      int[] grownItemStarts = new int[capacity];
      int[] grownItemCounts = new int[capacity];
      int[] grownLogicalStarts = new int[capacity];
      int[] grownLogicalCounts = new int[capacity];
      int[] grownPayloadBytes = new int[capacity];
      System.arraycopy(kinds, 0, grownKinds, 0, chunkCount);
      System.arraycopy(itemStarts, 0, grownItemStarts, 0, chunkCount);
      System.arraycopy(itemCounts, 0, grownItemCounts, 0, chunkCount);
      System.arraycopy(logicalStarts, 0, grownLogicalStarts, 0, chunkCount);
      System.arraycopy(logicalCounts, 0, grownLogicalCounts, 0, chunkCount);
      System.arraycopy(payloadBytes, 0, grownPayloadBytes, 0, chunkCount);
      kinds = grownKinds;
      itemStarts = grownItemStarts;
      itemCounts = grownItemCounts;
      logicalStarts = grownLogicalStarts;
      logicalCounts = grownLogicalCounts;
      payloadBytes = grownPayloadBytes;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }
}
