package io.riverdb.format.catalog;

/** Caller-owned decoded catalog object head. */
public final class CatalogObjectHead {
  private int state;
  private long objectId;
  private long schemaId;
  private long catalogGeneration;
  private long manifestRecordId;

  void set(int value, long object, long schema, long generation, long manifest) {
    state = value;
    objectId = object;
    schemaId = schema;
    catalogGeneration = generation;
    manifestRecordId = manifest;
  }

  public void reset() { set(0, 0, 0, 0, 0); }
  public int state() { return state; }
  public long objectId() { return objectId; }
  public long schemaId() { return schemaId; }
  public long catalogGeneration() { return catalogGeneration; }
  public long manifestRecordId() { return manifestRecordId; }
}
