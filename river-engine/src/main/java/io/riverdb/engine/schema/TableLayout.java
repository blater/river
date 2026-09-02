package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Recomputes physical slots and exact admitted maximum row bytes. */
final class TableLayout {
  private static final int ROW_HEADER_BYTES = 32;

  private TableLayout() {
  }

  static final class Result {
    int[] offsets;
    byte[] widths;
    int nullBytes;
    int maximumRowBytes;
  }

  static StatusCode create(ColumnDescriptorSet columns, Result result, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (result != null) {
      result.offsets = null;
      result.widths = null;
      result.nullBytes = 0;
      result.maximumRowBytes = 0;
    }
    if (columns == null || result == null) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid table columns");
    }
    int count = columns.count();
    if (count == 0) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "table requires columns");
    }
    int nullBytes = (count + Byte.SIZE - 1) / Byte.SIZE;
    int[] offsets;
    byte[] widths;
    try {
      offsets = new int[count];
      widths = new byte[count];
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "layout capacity unavailable");
    }
    long offset = ROW_HEADER_BYTES + nullBytes;
    long maximum = offset;
    for (int index = 0; index < count; index++) {
      int descriptor = columns.typeDescriptorAt(index);
      int width = fixedWidth(descriptor);
      offsets[index] = (int) offset;
      widths[index] = (byte) width;
      offset += width;
      maximum += width;
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        maximum += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
      }
    }
    if (maximum > SqlShapeLimits.MAX_STORED_ROW_BYTES) {
      return append(fail(detail, StatusCode.RESOURCE_EXHAUSTED,
          "row bytes exceed allowed bytes"), detail, maximum,
          SqlShapeLimits.MAX_STORED_ROW_BYTES);
    }
    result.offsets = offsets;
    result.widths = widths;
    result.nullBytes = nullBytes;
    result.maximumRowBytes = (int) maximum;
    return StatusCode.OK;
  }

  private static int fixedWidth(int descriptor) {
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) return Long.BYTES * 2;
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> 1;
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> Short.BYTES;
      case SqlTypeDescriptor.TYPE_ID_INTEGER,
          SqlTypeDescriptor.TYPE_ID_REAL, SqlTypeDescriptor.TYPE_ID_DATE -> Integer.BYTES;
      default -> Long.BYTES;
    };
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }

  private static StatusCode append(
      StatusCode status, StatusDetail detail, long actual, long allowed) {
    if (detail != null) detail.append(" requested=").append(actual).append(" allowed=").append(allowed);
    return status;
  }
}
