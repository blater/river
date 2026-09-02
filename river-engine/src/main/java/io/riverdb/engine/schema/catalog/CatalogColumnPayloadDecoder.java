package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.PackedColumnDescriptorBuilder;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Allocation-free validation and packed publication of one column catalog chunk. */
final class CatalogColumnPayloadDecoder {
  private CatalogColumnPayloadDecoder() { }

  static StatusCode decode(
      ByteBuffer source, int start, int bytes, int expectedCount,
      int destinationStart, PackedColumnDescriptorBuilder columns,
      CatalogColumnConstraintAssembly constraints) {
    if (!CatalogColumnPayloadCodec.readable(source, start, bytes)
        || FormatBytes.getInt(source, start) != CatalogColumnPayloadCodec.VERSION
        || FormatBytes.getInt(source, start + 4) != expectedCount
        || columns == null || constraints == null
        || columns.count() != destinationStart) return StatusCode.CORRUPTION;
    int names = validate(source, start, bytes, expectedCount);
    if (names < 0) return StatusCode.CORRUPTION;
    StatusCode status = columns.reserve(expectedCount, names);
    return status.isOk()
        ? copy(source, start, expectedCount, names, destinationStart, columns, constraints)
        : status;
  }

  private static int validate(ByteBuffer source, int start, int bytes, int expectedCount) {
    int cursor = start + CatalogColumnPayloadCodec.headerBytes();
    int end = start + bytes;
    int names = 0;
    for (int index = 0; index < expectedCount; index++) {
      if (cursor > end - CatalogColumnPayloadCodec.entryBytes()) return -1;
      int descriptor = FormatBytes.getInt(source, cursor);
      int flags = FormatBytes.getInt(source, cursor + 4);
      int defaultKind = FormatBytes.getInt(source, cursor + 8);
      int comparison = FormatBytes.getInt(source, cursor + 12);
      int checkType = FormatBytes.getInt(source, cursor + 16);
      int length = FormatBytes.getInt(source, cursor + 20);
      cursor += CatalogColumnPayloadCodec.entryBytes();
      if (!SqlTypeDescriptor.isValid(descriptor) || (flags & ~1) != 0
          || defaultKind < 0 || defaultKind > 1 || comparison < 0 || comparison > 6
          || (comparison == 0) != (checkType == 0)
          || length <= 0 || cursor > end - length || names > Integer.MAX_VALUE - length) return -1;
      names += length;
      cursor += length;
    }
    return cursor == end ? names : -1;
  }

  private static StatusCode copy(
      ByteBuffer source, int start, int expectedCount, int names,
      int destinationStart, PackedColumnDescriptorBuilder columns,
      CatalogColumnConstraintAssembly constraints) {
    int cursor = start + CatalogColumnPayloadCodec.headerBytes();
    int nameOffset = 0;
    for (int index = 0; index < expectedCount; index++) {
      int descriptor = FormatBytes.getInt(source, cursor);
      boolean nullable = FormatBytes.getInt(source, cursor + 4) != 0;
      int defaultKind = FormatBytes.getInt(source, cursor + 8);
      int comparison = FormatBytes.getInt(source, cursor + 12);
      int checkType = FormatBytes.getInt(source, cursor + 16);
      int length = FormatBytes.getInt(source, cursor + 20);
      long defaultHigh = FormatBytes.getLong(source, cursor + 24);
      long defaultValue = FormatBytes.getLong(source, cursor + 32);
      long checkHigh = FormatBytes.getLong(source, cursor + 40);
      long checkValue = FormatBytes.getLong(source, cursor + 48);
      cursor += CatalogColumnPayloadCodec.entryBytes();
      StatusCode status = columns.putReserved(
          index, descriptor, nullable, source, cursor, length, nameOffset);
      if (!status.isOk()) return status == StatusCode.RESOURCE_EXHAUSTED
          ? status : StatusCode.CORRUPTION;
      constraints.put(
          destinationStart + index, descriptor, defaultKind, defaultHigh, defaultValue,
          comparison, checkType, checkHigh, checkValue);
      nameOffset += length;
      cursor += length;
    }
    StatusCode status = columns.publishReserved(expectedCount, names);
    return status.isOk() ? status : StatusCode.CORRUPTION;
  }
}
