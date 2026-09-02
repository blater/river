package io.riverdb.format.catalog;

final class CatalogBuildIntentProgressValidation {
  private CatalogBuildIntentProgressValidation() { }

  static boolean valid(
      int state, int kind, int children, int nextChild, int cleanup) {
    return (state == CatalogBuildIntentCodec.STATE_BUILDING
            || state == CatalogBuildIntentCodec.STATE_CLEANUP
            || state == CatalogBuildIntentCodec.STATE_READY)
        && (kind == CatalogBuildIntentCodec.KIND_INITIAL
            || kind == CatalogBuildIntentCodec.KIND_SUCCESSOR)
        && nextChild >= 0 && nextChild <= children
        && cleanup >= 0 && cleanup <= children + 1
        && (state != CatalogBuildIntentCodec.STATE_BUILDING || cleanup == 0)
        && (state != CatalogBuildIntentCodec.STATE_READY
            || nextChild == children && cleanup == 0);
  }
}
