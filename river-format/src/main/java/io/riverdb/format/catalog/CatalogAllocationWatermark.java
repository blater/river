package io.riverdb.format.catalog;

/**
 * Caller-owned decoded authority for the next positive catalog and key identities.
 * {@link Long#MAX_VALUE} is the durable exhausted sentinel and is never issued.
 */
public final class CatalogAllocationWatermark {
  private long nextObjectId;
  private long nextSchemaId;
  private long nextRowLayoutId;
  private long nextCatalogRecordId;
  private long nextKeyId;
  private boolean available;

  void set(long objectId, long schemaId, long rowLayoutId, long recordId, long keyId) {
    nextObjectId = objectId;
    nextSchemaId = schemaId;
    nextRowLayoutId = rowLayoutId;
    nextCatalogRecordId = recordId;
    nextKeyId = keyId;
    available = true;
  }

  public void reset() {
    nextObjectId = 0;
    nextSchemaId = 0;
    nextRowLayoutId = 0;
    nextCatalogRecordId = 0;
    nextKeyId = 0;
    available = false;
  }

  public long nextObjectId() { return nextObjectId; }
  public long nextSchemaId() { return nextSchemaId; }
  public long nextRowLayoutId() { return nextRowLayoutId; }
  public long nextCatalogRecordId() { return nextCatalogRecordId; }
  public long nextKeyId() { return nextKeyId; }
  public boolean isAvailable() { return available; }
  public boolean canAllocateObjectId() {
    return available && nextObjectId <= CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID;
  }
  public boolean canAllocateSchemaId() { return available && nextSchemaId < Long.MAX_VALUE; }
  public boolean canAllocateRowLayoutId() {
    return available && nextRowLayoutId < Long.MAX_VALUE;
  }
  public boolean canAllocateCatalogRecordId() {
    return available && nextCatalogRecordId < Long.MAX_VALUE;
  }
  public boolean canAllocateKeyIds(int count) {
    return available && count >= 0 && nextKeyId > 0
        && nextKeyId <= CatalogKeyspace.KEY_ID_EXHAUSTED - count;
  }
}
