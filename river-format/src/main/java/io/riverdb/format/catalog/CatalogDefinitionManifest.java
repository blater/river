package io.riverdb.format.catalog;

/** Caller-owned decoded manifest for one immutable catalog definition. */
public final class CatalogDefinitionManifest {
  private int kind;
  private long catalogRecordId;
  private long objectId;
  private long schemaId;
  private long rowLayoutId;
  private long catalogGeneration;
  private long firstChildRecordId;
  private int childCount;
  private int columnCount;
  private int keyPartCount;
  private int logicalCount;
  private int payloadBytes;
  private int childSetChecksum;

  void set(
      int valueKind,
      long recordId,
      long object,
      long schema,
      long layout,
      long generation,
      long firstChild,
      int children,
      int columns,
      int keyParts,
      int logical,
      int bytes,
      int childrenChecksum) {
    kind = valueKind;
    catalogRecordId = recordId;
    objectId = object;
    schemaId = schema;
    rowLayoutId = layout;
    catalogGeneration = generation;
    firstChildRecordId = firstChild;
    childCount = children;
    columnCount = columns;
    keyPartCount = keyParts;
    logicalCount = logical;
    payloadBytes = bytes;
    childSetChecksum = childrenChecksum;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
  public int kind() { return kind; }
  public long catalogRecordId() { return catalogRecordId; }
  public long objectId() { return objectId; }
  public long schemaId() { return schemaId; }
  public long rowLayoutId() { return rowLayoutId; }
  public long catalogGeneration() { return catalogGeneration; }
  public long firstChildRecordId() { return firstChildRecordId; }
  public int childCount() { return childCount; }
  public int columnCount() { return columnCount; }
  public int keyPartCount() { return keyPartCount; }
  public int logicalCount() { return logicalCount; }
  public int payloadBytes() { return payloadBytes; }
  public int childSetChecksum() { return childSetChecksum; }
}
