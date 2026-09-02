package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Allocation-free structural validation of one canonical external sort key. */
final class SqlBlockRowSortKeyValidation {
  private SqlBlockRowSortKeyValidation() {}

  static StatusCode validate(ByteBuffer key, SqlBlockRowSortKeyCodec shape) {
    if (key == null || shape == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int at = key.position();
    int end = key.limit();
    for (int part = 0; part < shape.partCount(); part++) {
      if (at >= end) return StatusCode.CORRUPTION;
      int marker = Byte.toUnsignedInt(key.get(at++));
      if (marker > 1) return StatusCode.CORRUPTION;
      if (marker == 0) continue;
      int descriptor = shape.descriptor(part);
      if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        int bytes = SqlTypeDescriptor.isWideDecimal(descriptor) ? 16 : Long.BYTES;
        if (end - at < bytes) return StatusCode.CORRUPTION;
        at += bytes;
        continue;
      }
      if (end - at < Integer.BYTES) return StatusCode.CORRUPTION;
      int length = key.getInt(at);
      at += Integer.BYTES;
      if (length < 0 || length > end - at) return StatusCode.CORRUPTION;
      at += length;
    }
    return at == end ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
