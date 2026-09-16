package io.riverdb.engine.relational;

import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlDefaultKind;

/** Validates optional column fields against their persisted presence flags. */
final class CatalogColumnValidation {
  private CatalogColumnValidation() { }

  static boolean defaultValue(
      boolean present, boolean text, int kind, long value, int textBytes) {
    return present ? (text ? value == 0 : textBytes == 0)
        : kind == SqlDefaultKind.NONE && value == 0 && textBytes == 0;
  }

  static boolean check(
      boolean present, int comparison, long value, int descriptor, int nodes, int totalNodes) {
    return present ? nodes > 0 && nodes <= SqlShapeLimits.MAX_EXPRESSION_NODES - totalNodes
        : comparison == 0 && value == 0 && descriptor == 0 && nodes == 0;
  }

  static boolean reference(boolean present, int referenceTableId, int tableId) {
    return present ? referenceTableId > 0 && referenceTableId <= RelationalKey.MAXIMUM_TABLE_ID
        && referenceTableId != tableId : referenceTableId == 0;
  }

}
