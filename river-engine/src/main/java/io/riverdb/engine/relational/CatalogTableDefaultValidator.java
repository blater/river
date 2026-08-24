package io.riverdb.engine.relational;

import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Validates fixed and variable-length column defaults in a table record. */
final class CatalogTableDefaultValidator {
  private CatalogTableDefaultValidator() {
  }

  static boolean valid(
      ByteBuffer source,
      int columns,
      long defaultMask,
      int defaultTextBytes,
      int defaultTextOffset) {
    int expectedOffset = 0;
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      long value = source.getLong(
          CatalogRecord.TABLE_DEFAULTS_OFFSET + column * Long.BYTES);
      int kind = Byte.toUnsignedInt(
          source.get(CatalogRecord.TABLE_DEFAULT_KINDS_OFFSET + column));
      boolean present = column < columns && (defaultMask & 1L << column) != 0;
      int descriptor = column < columns
          ? source.getInt(CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES) : 0;
      if (!present) {
        if (kind != SqlDefaultKind.NONE || value != 0) {
          return false;
        }
        continue;
      }
      if (SqlDefaultKind.isCurrent(kind)) {
        if (value != 0 || !SqlDefaultKind.compatible(kind, descriptor)) {
          return false;
        }
        continue;
      }
      if (kind != SqlDefaultKind.LITERAL) {
        return false;
      }
      if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        if (!TableSchema.validFixedValue(descriptor, value)) {
          return false;
        }
        continue;
      }
      int offset = (int) (value >>> 32);
      int length = (int) value;
      if (offset != expectedOffset
          || length < 0
          || offset > defaultTextBytes - length
          || Utf8Text.validate(
              source,
              defaultTextOffset + offset,
              length,
              SqlTypeDescriptor.parameterOne(descriptor)) < 0) {
        return false;
      }
      expectedOffset += length;
    }
    return expectedOffset == defaultTextBytes;
  }
}
