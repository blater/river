package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Structural identity retained across metadata-only descriptor successors. */
final class CatalogSuccessorMetadataEquality {
  private CatalogSuccessorMetadataEquality() { }

  static boolean columns(TableDescriptor left, TableDescriptor right) {
    if (left.columnCount() != right.columnCount()) return false;
    for (int column = 0; column < left.columnCount(); column++) {
      if (!column(left, right, column)) return false;
    }
    return true;
  }

  static boolean key(KeyDescriptor left, KeyDescriptor right) {
    if (left == null || right == null) return left == right;
    if (left.keyId() != right.keyId()
        || left.kind() != right.kind()
        || left.isUnique() != right.isUnique()
        || left.referencedKeyId() != right.referencedKeyId()
        || left.partCount() != right.partCount()) return false;
    for (int part = 0; part < left.partCount(); part++) {
      if (left.columnOrdinalAt(part) != right.columnOrdinalAt(part)
          || left.typeDescriptorAt(part) != right.typeDescriptorAt(part)) return false;
    }
    return true;
  }

  private static boolean column(
      TableDescriptor left, TableDescriptor right, int column) {
    return left.typeDescriptorAt(column) == right.typeDescriptorAt(column)
        && left.isNullable(column) == right.isNullable(column)
        && left.columns().defaultKindAt(column) == right.columns().defaultKindAt(column)
        && left.columns().defaultHighAt(column) == right.columns().defaultHighAt(column)
        && left.columns().defaultValueAt(column) == right.columns().defaultValueAt(column)
        && left.columns().checkComparisonAt(column)
            == right.columns().checkComparisonAt(column)
        && left.columns().checkTypeAt(column) == right.columns().checkTypeAt(column)
        && left.columns().checkHighAt(column) == right.columns().checkHighAt(column)
        && left.columns().checkValueAt(column) == right.columns().checkValueAt(column);
  }
}
