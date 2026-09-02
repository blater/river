package io.riverdb.format.catalog;

/** Caller-owned decoded metadata for one catalog definition child. */
public final class CatalogDefinitionRecord {
  private long catalogRecordId;
  private long objectId;
  private long schemaId;
  private long catalogGeneration;
  private int kind;
  private int ordinal;
  private int logicalStart;
  private int logicalCount;
  private int payloadBytes;
  private int recordChecksum;

  void set(
      long recordId,
      long object,
      long schema,
      long generation,
      int valueKind,
      int index,
      int firstLogical,
      int logical,
      int bytes,
      int checksum) {
    catalogRecordId = recordId;
    objectId = object;
    schemaId = schema;
    catalogGeneration = generation;
    kind = valueKind;
    ordinal = index;
    logicalStart = firstLogical;
    logicalCount = logical;
    payloadBytes = bytes;
    recordChecksum = checksum;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
  public long catalogRecordId() { return catalogRecordId; }
  public long objectId() { return objectId; }
  public long schemaId() { return schemaId; }
  public long catalogGeneration() { return catalogGeneration; }
  public int kind() { return kind; }
  public int ordinal() { return ordinal; }
  public int logicalStart() { return logicalStart; }
  public int logicalCount() { return logicalCount; }
  public int payloadBytes() { return payloadBytes; }
  public int recordChecksum() { return recordChecksum; }
}
