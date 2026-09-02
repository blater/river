package io.riverdb.engine.schema.catalog;

import io.riverdb.format.catalog.CatalogKeyspace;

final class CatalogReservedKeyRange {
  private CatalogReservedKeyRange() { }

  static boolean matches(CatalogReservation reservation, int count) {
    if (reservation.keyCount() != count) return false;
    long first = reservation.firstKeyId();
    if (count == 0) return first > 0 && first <= CatalogKeyspace.KEY_ID_EXHAUSTED;
    return CatalogKeyspace.validKeyId(first)
        && first <= CatalogKeyspace.MAXIMUM_KEY_ID - count + 1;
  }
}
