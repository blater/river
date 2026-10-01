package io.riverdb.engine.sql;

import static io.riverdb.base.type.ExactDecimalDescriptors.precision;
import static io.riverdb.base.type.ExactDecimalDescriptors.scale;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Stable typed equality hash shared by physical and spill-decoded JOIN rows. */
final class SqlJoinHashKey {
  private static final long OFFSET = 0xcbf29ce484222325L;
  private static final long PRIME = 0x100000001b3L;
  private final ExactDecimal128.Scratch decimal = new ExactDecimal128.Scratch();
  private final ExactDecimal128.Value normalized = new ExactDecimal128.Value();

  long decoded(SqlBlockRow row, int column, int descriptor) {
    return decoded(row, column, descriptor, descriptor);
  }

  long decoded(
      SqlBlockRow row, int column, int descriptor, int comparedDescriptor) {
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      long hash = OFFSET;
      if (row.hasUtf8(column)) {
        int length = row.utf8Length(column);
        for (int index = 0; index < length;) {
          int first = Byte.toUnsignedInt(row.utf8ByteAt(column, index));
          int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
          if (width > length - index) break;
          int scalar = width == 1 ? first : first & (0x7f >> width);
          for (int byteIndex = 1; byteIndex < width; byteIndex++) {
            scalar = scalar << 6
                | Byte.toUnsignedInt(row.utf8ByteAt(column, index + byteIndex)) & 0x3f;
          }
          hash = mix(hash, scalar);
          index += width;
        }
        return hash;
      }
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
    if (SqlNumericTypeRules.isNumeric(descriptor)) {
      return numeric(
          row.highValue(column), row.value(column), descriptor, comparedDescriptor);
    }
    return mix(OFFSET, row.value(column));
  }

  private long numeric(
      long high, long low, int descriptor, int comparedDescriptor) {
    if (SqlNumericTypeRules.isExact(descriptor)
        && SqlNumericTypeRules.isExact(comparedDescriptor)) {
      return exact(high, low, descriptor, comparedDescriptor);
    }
    double value = SqlTypeDescriptor.isWideDecimal(descriptor)
        || SqlTypeDescriptor.isWideDecimal(comparedDescriptor)
      ? SqlNumericComparison.doubleValue(high, low, descriptor, decimal)
      : SqlNumericValue.doubleValue(low, descriptor);
    long bits = value == 0.0d ? 0 : Double.doubleToLongBits(value);
    return mix(OFFSET, bits);
  }

  private long exact(
      long high, long low, int descriptor, int comparedDescriptor) {
    int sourceScale = scale(descriptor);
    int targetScale = Math.max(sourceScale, scale(comparedDescriptor));
    StatusCode status = ExactDecimal128.quantize(
        SqlTypeDescriptor.isWideDecimal(descriptor) ? high : low >> 63,
        low,
        precision(descriptor),
        sourceScale,
        ExactDecimal128.MAXIMUM_PRECISION,
        targetScale,
        ExactDecimal128.ROUND_TRUNCATE,
        true,
        normalized,
        decimal);
    if (!status.isOk()) {
      long hash = mix(OFFSET, high);
      return mix(mix(hash, low), sourceScale);
    }
    long hash = mix(OFFSET, normalized.high);
    return mix(mix(hash, normalized.low), targetScale);
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    return hash * PRIME;
  }
}
