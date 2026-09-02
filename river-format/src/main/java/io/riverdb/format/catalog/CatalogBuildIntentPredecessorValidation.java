package io.riverdb.format.catalog;

final class CatalogBuildIntentPredecessorValidation {
  private CatalogBuildIntentPredecessorValidation() { }

  static boolean valid(
      int kind, long generation, long schema,
      long predecessorGeneration, long manifest) {
    return kind == CatalogBuildIntentCodec.KIND_INITIAL
        ? schema == 0 && predecessorGeneration == 0 && manifest == 0
        : schema > 0 && predecessorGeneration > 0
            && generation > 1 && predecessorGeneration == generation - 1
            && manifest > 0;
  }
}
