package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;

final class CatalogTableKeys {
  private CatalogTableKeys() {
  }

  static int count(TableDescriptor table) {
    return (table.primaryKey() == null ? 0 : 1)
        + table.secondaryKeyCount() + table.foreignKeyCount();
  }

  static int physicalIndexCount(TableDescriptor table) {
    return 1 + (table.primaryKey() == null ? 0 : 1) + table.secondaryKeyCount();
  }

  static KeyDescriptor physicalIndexAt(TableDescriptor table, int index) {
    if (index == 0) return table.identityKey();
    index--;
    if (table.primaryKey() != null) {
      if (index == 0) return table.primaryKey();
      index--;
    }
    return table.secondaryKeyAt(index);
  }

  static int reservedPhysicalIndexCount(
      TableDescriptor table, CatalogReservation reservation) {
    return reservedPhysicalIndexCount(
        table, reservation.firstKeyId(), reservation.keyCount(),
        reservation.kind() == CatalogBuildIntentCodec.KIND_INITIAL);
  }

  static int reservedPhysicalIndexCount(
      TableDescriptor table, io.riverdb.format.catalog.CatalogBuildIntent intent) {
    return reservedPhysicalIndexCount(
        table, intent.firstKeyId(), intent.keyCount(),
        intent.kind() == CatalogBuildIntentCodec.KIND_INITIAL);
  }

  private static int reservedPhysicalIndexCount(
      TableDescriptor table, long first, int keyCount, boolean initial) {
    int count = initial ? 1 : 0;
    long end = first + keyCount;
    for (int index = 0; index < physicalIndexCount(table); index++) {
      long keyId = physicalIndexAt(table, index).keyId();
      if (keyId >= first && keyId < end) count++;
    }
    return count;
  }

  static KeyDescriptor reservedPhysicalIndexAt(
      TableDescriptor table, CatalogReservation reservation, int ordinal) {
    return reservedPhysicalIndexAt(
        table, reservation.firstKeyId(), reservation.keyCount(), ordinal,
        reservation.kind() == CatalogBuildIntentCodec.KIND_INITIAL);
  }

  static KeyDescriptor reservedPhysicalIndexAt(
      TableDescriptor table, io.riverdb.format.catalog.CatalogBuildIntent intent,
      int ordinal) {
    return reservedPhysicalIndexAt(
        table, intent.firstKeyId(), intent.keyCount(), ordinal,
        intent.kind() == CatalogBuildIntentCodec.KIND_INITIAL);
  }

  private static KeyDescriptor reservedPhysicalIndexAt(
      TableDescriptor table, long first, int keyCount, int ordinal, boolean initial) {
    if (initial && ordinal-- == 0) return table.identityKey();
    long end = first + keyCount;
    for (int index = 0; index < physicalIndexCount(table); index++) {
      KeyDescriptor key = physicalIndexAt(table, index);
      if (key.keyId() >= first && key.keyId() < end && ordinal-- == 0) return key;
    }
    return null;
  }

  static KeyDescriptor at(TableDescriptor table, int index) {
    if (table.primaryKey() != null) {
      if (index == 0) return table.primaryKey();
      index--;
    }
    if (index < table.secondaryKeyCount()) return table.secondaryKeyAt(index);
    return table.foreignKeyAt(index - table.secondaryKeyCount());
  }

  static boolean validRange(TableDescriptor table, int first, int count) {
    return table != null && first >= 0 && count > 0 && first <= count(table) - count;
  }

  static int unboundCount(TableDescriptor table) {
    int count = 0;
    for (int index = 0; index < count(table); index++) {
      if (at(table, index).keyId() == 0) count++;
    }
    return count;
  }
}
