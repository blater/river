package io.riverdb.engine.schema.catalog;

/** One committed, nonrollbackable catalog identity and record-range reservation. */
final class CatalogReservation {
  private long objectId, schemaId, rowLayoutId, catalogGeneration;
  private long manifestRecordId, firstChildRecordId, firstKeyId;
  private long predecessorSchemaId, predecessorGeneration, predecessorManifestRecordId;
  private int childCount, keyCount;
  private int physicalIndexCount, nextPhysicalIndex;
  private int kind;

  void setInitial(
      long object, long schema, long layout, long generation,
      long manifest, long firstChild, int children, long firstKey, int keys) {
    set(io.riverdb.format.catalog.CatalogBuildIntentCodec.KIND_INITIAL,
        object, schema, layout, generation, manifest, firstChild, children,
        firstKey, keys, 0, 0, 0);
  }

  void setSuccessor(
      long object, long schema, long layout, long generation,
      long manifest, long firstChild, int children, long firstKey, int keys,
      long predecessorSchema, long predecessorGenerationValue,
      long predecessorManifest) {
    set(io.riverdb.format.catalog.CatalogBuildIntentCodec.KIND_SUCCESSOR,
        object, schema, layout, generation, manifest, firstChild, children,
        firstKey, keys, predecessorSchema, predecessorGenerationValue,
        predecessorManifest);
  }

  private void set(
      int buildKind, long object, long schema, long layout, long generation,
      long manifest, long firstChild, int children, long firstKey, int keys,
      long predecessorSchema, long predecessorGenerationValue,
      long predecessorManifest) {
    kind = buildKind;
    objectId = object;
    schemaId = schema;
    rowLayoutId = layout;
    catalogGeneration = generation;
    manifestRecordId = manifest;
    firstChildRecordId = firstChild;
    firstKeyId = firstKey;
    childCount = children;
    keyCount = keys;
    predecessorSchemaId = predecessorSchema;
    predecessorGeneration = predecessorGenerationValue;
    predecessorManifestRecordId = predecessorManifest;
  }
  void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    physicalIndexCount = 0;
    nextPhysicalIndex = 0;
  }
  int kind() { return kind; }
  long objectId() { return objectId; }
  long schemaId() { return schemaId; }
  long rowLayoutId() { return rowLayoutId; }
  long catalogGeneration() { return catalogGeneration; }
  long manifestRecordId() { return manifestRecordId; }
  long firstChildRecordId() { return firstChildRecordId; }
  long firstKeyId() { return firstKeyId; }
  int childCount() { return childCount; }
  int keyCount() { return keyCount; }
  int physicalIndexCount() { return physicalIndexCount; }
  int nextPhysicalIndex() { return nextPhysicalIndex; }
  void setPhysicalIndexCount(int count) { physicalIndexCount = count; }
  void setNextPhysicalIndex(int next) { nextPhysicalIndex = next; }
  long predecessorSchemaId() { return predecessorSchemaId; }
  long predecessorGeneration() { return predecessorGeneration; }
  long predecessorManifestRecordId() { return predecessorManifestRecordId; }
}
