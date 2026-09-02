package io.riverdb.format.catalog;

/** Caller-owned decoded progress for one unreachable private catalog definition build. */
public final class CatalogBuildIntent {
  private int state;
  private int kind;
  private long objectId;
  private long schemaId;
  private long rowLayoutId;
  private long catalogGeneration;
  private long manifestRecordId;
  private long firstChildRecordId;
  private int childCount;
  private int nextChild;
  private int cleanupCursor;
  private int payloadBytes;
  private long catalogBytes;
  private long predecessorSchemaId;
  private long predecessorGeneration;
  private long predecessorManifestRecordId;
  private long firstKeyId;
  private int keyCount;
  private int physicalIndexCount;
  private int nextPhysicalIndex;
  private int indexCleanupCursor;
  private int indexCleanupHorizon;

  void set(
      int valueState, int valueKind, long object, long schema, long layout, long generation,
      long manifest, long firstChild, int children, int next, int cleanup,
      int payload, long bytes, long predecessorSchema,
      long predecessorGenerationValue, long predecessorManifest,
      long firstKey, int keys, int physicalIndexes,
      int nextIndex, int indexCleanup, int cleanupHorizon) {
    state = valueState;
    kind = valueKind;
    objectId = object;
    schemaId = schema;
    rowLayoutId = layout;
    catalogGeneration = generation;
    manifestRecordId = manifest;
    firstChildRecordId = firstChild;
    childCount = children;
    nextChild = next;
    cleanupCursor = cleanup;
    payloadBytes = payload;
    catalogBytes = bytes;
    predecessorSchemaId = predecessorSchema;
    predecessorGeneration = predecessorGenerationValue;
    predecessorManifestRecordId = predecessorManifest;
    firstKeyId = firstKey;
    keyCount = keys;
    physicalIndexCount = physicalIndexes;
    nextPhysicalIndex = nextIndex;
    indexCleanupCursor = indexCleanup;
    indexCleanupHorizon = cleanupHorizon;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0);
  }
  public int state() { return state; }
  public int kind() { return kind; }
  public long objectId() { return objectId; }
  public long schemaId() { return schemaId; }
  public long rowLayoutId() { return rowLayoutId; }
  public long catalogGeneration() { return catalogGeneration; }
  public long manifestRecordId() { return manifestRecordId; }
  public long firstChildRecordId() { return firstChildRecordId; }
  public int childCount() { return childCount; }
  public int nextChild() { return nextChild; }
  public int cleanupCursor() { return cleanupCursor; }
  public int payloadBytes() { return payloadBytes; }
  public long catalogBytes() { return catalogBytes; }
  public long predecessorSchemaId() { return predecessorSchemaId; }
  public long predecessorGeneration() { return predecessorGeneration; }
  public long predecessorManifestRecordId() { return predecessorManifestRecordId; }
  public long firstKeyId() { return firstKeyId; }
  public int keyCount() { return keyCount; }
  public int physicalIndexCount() { return physicalIndexCount; }
  public int nextPhysicalIndex() { return nextPhysicalIndex; }
  public int indexCleanupCursor() { return indexCleanupCursor; }
  public int indexCleanupHorizon() { return indexCleanupHorizon; }
}
