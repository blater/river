package io.riverdb.format.catalog;

final class CatalogBuildIntentValidation {
  private CatalogBuildIntentValidation() { }

  static boolean valid(
      int state, int kind, long objectId, long schemaId, long layoutId, long generation,
      long manifestId, long firstChild, int children, int nextChild,
      int cleanup, int payloadBytes, long catalogBytes, long predecessorSchema,
      long predecessorGeneration, long predecessorManifest,
      long firstKeyId, int keyCount, int physicalIndexCount,
      int nextPhysicalIndex, int indexCleanupCursor, int indexCleanupHorizon) {
    return CatalogBuildIntentProgressValidation.valid(
            state, kind, children, nextChild, cleanup)
        && CatalogBuildIntentDefinitionValidation.valid(
            objectId, schemaId, layoutId, generation,
            manifestId, firstChild, children, payloadBytes, catalogBytes)
        && CatalogBuildIntentKeyValidation.valid(
            state, firstKeyId, keyCount, physicalIndexCount,
            nextPhysicalIndex, indexCleanupCursor, indexCleanupHorizon)
        && CatalogBuildIntentPredecessorValidation.valid(
            kind, generation, predecessorSchema,
            predecessorGeneration, predecessorManifest);
  }
}
