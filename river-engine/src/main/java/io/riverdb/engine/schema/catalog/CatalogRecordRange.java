package io.riverdb.engine.schema.catalog;

/** Caller-owned contiguous catalog-record reservation from the shared durable watermark. */
public final class CatalogRecordRange {
  private long firstRecordId;
  private int recordCount;

  public void reset() {
    firstRecordId = 0;
    recordCount = 0;
  }

  void set(long first, int count) {
    firstRecordId = first;
    recordCount = count;
  }

  public long firstRecordId() { return firstRecordId; }
  public int recordCount() { return recordCount; }
}
