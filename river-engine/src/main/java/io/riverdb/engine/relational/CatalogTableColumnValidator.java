package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Validates table column names, descriptors, and foreign-key references. */
final class CatalogTableColumnValidator {
  private CatalogTableColumnValidator() {
  }

  static boolean validColumns(TableDefinition table) {
    for (int index = 0; index < table.columnCount(); index++) {
      CharSequence name = table.columnName(index);
      if (!RelationalKey.validName(name)) {
        return false;
      }
      for (int prior = 0; prior < index; prior++) {
        if (sameName(name, table.columnName(prior))) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean validTypeDescriptors(ByteBuffer source, int columns) {
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      int descriptor = source.getInt(
          CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES);
      if (column >= columns) {
        if (descriptor != 0) {
          return false;
        }
      } else if (column == 0) {
        if (descriptor != SqlTypeDescriptor.BIGINT) {
          return false;
        }
      } else if (!SqlTypeDescriptor.isValid(descriptor)
          || !supportedType(SqlTypeDescriptor.typeId(descriptor))) {
        return false;
      }
    }
    return true;
  }

  static boolean validReferences(
      ByteBuffer source,
      int columns,
      long referenceMask,
      int tableId) {
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      int referencedTableId = source.getInt(
          CatalogRecord.TABLE_REFERENCE_IDS_OFFSET + column * Integer.BYTES);
      boolean referenced = column < columns && (referenceMask & 1L << column) != 0;
      if (referenced
          ? source.getInt(CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES)
                  != SqlTypeDescriptor.BIGINT
              || referencedTableId <= 0
              || referencedTableId > RelationalKey.MAXIMUM_TABLE_ID
              || referencedTableId == tableId
          : referencedTableId != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean supportedType(int typeId) {
    return typeId == SqlTypeDescriptor.TYPE_ID_BIGINT
        || typeId == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || typeId == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || typeId == SqlTypeDescriptor.TYPE_ID_DECIMAL
        || typeId == SqlTypeDescriptor.TYPE_ID_DATE
        || typeId == SqlTypeDescriptor.TYPE_ID_TIME
        || typeId == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || typeId == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private static boolean sameName(CharSequence first, CharSequence second) {
    if (first.length() != second.length()) {
      return false;
    }
    for (int index = 0; index < first.length(); index++) {
      if (first.charAt(index) != second.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}
