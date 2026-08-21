package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Stable typed equality hash shared by physical and spill-decoded JOIN rows. */
final class SqlJoinHashKey {
  private static final long OFFSET = 0xcbf29ce484222325L;
  private static final long PRIME = 0x100000001b3L;

  long decoded(SqlBlockRow row, int column, int descriptor) {
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      long hash = OFFSET;
      int length = row.textLength(column);
      for (int index = 0; index < length; index++) {
        char first = row.textCharacter(column, index);
        int scalar = first;
        if (Character.isHighSurrogate(first) && index + 1 < length) {
          scalar = Character.toCodePoint(first, row.textCharacter(column, ++index));
        }
        hash = mix(hash, scalar);
      }
      return hash;
    }
    return fixed(row.value(column), descriptor);
  }

  private static long fixed(long value, int descriptor) {
    int scale = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
    while (scale > 0 && value % 10 == 0) {
      value /= 10;
      scale--;
    }
    return mix(mix(OFFSET, value), scale);
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    return hash * PRIME;
  }
}
