package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.PackedColumnDescriptorBuilder;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import java.nio.ByteBuffer;

/** Canonical payload encoding for at most 32 consecutive immutable column descriptors. */
public final class CatalogColumnPayloadCodec {
  public static final int VERSION = 2;
  private static final int HEADER_BYTES = 8;
  private static final int ENTRY_BYTES = 56;

  private CatalogColumnPayloadCodec() {
  }

  public static StatusCode payloadBytes(
      ColumnDescriptorSet columns, int first, int count, CatalogPayloadSize result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validRange(columns, first, count)) return StatusCode.INVALID_EXTERNAL_INPUT;
    long bytes = calculateBytes(columns, first, count);
    if (bytes > CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set((int) bytes);
    return StatusCode.OK;
  }

  public static StatusCode encode(
      ColumnDescriptorSet columns,
      int first,
      int count,
      ByteBuffer target,
      int start) {
    if (!validRange(columns, first, count)) return StatusCode.INVALID_EXTERNAL_INPUT;
    long calculated = calculateBytes(columns, first, count);
    if (calculated > CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int bytes = (int) calculated;
    if (target == null || target.isReadOnly() || start < 0
        || start > target.limit() - bytes) return StatusCode.INVALID_EXTERNAL_INPUT;
    FormatBytes.putInt(target, start, VERSION);
    FormatBytes.putInt(target, start + 4, count);
    int cursor = start + HEADER_BYTES;
    for (int index = 0; index < count; index++) {
      int ordinal = first + index;
      int nameBytes = columns.nameByteLength(ordinal);
      FormatBytes.putInt(target, cursor, columns.typeDescriptorAt(ordinal));
      FormatBytes.putInt(target, cursor + 4, columns.isNullable(ordinal) ? 1 : 0);
      FormatBytes.putInt(target, cursor + 8, columns.defaultKindAt(ordinal));
      FormatBytes.putInt(target, cursor + 12, columns.checkComparisonAt(ordinal));
      FormatBytes.putInt(target, cursor + 16, columns.checkTypeAt(ordinal));
      FormatBytes.putInt(target, cursor + 20, nameBytes);
      FormatBytes.putLong(target, cursor + 24, columns.defaultHighAt(ordinal));
      FormatBytes.putLong(target, cursor + 32, columns.defaultValueAt(ordinal));
      FormatBytes.putLong(target, cursor + 40, columns.checkHighAt(ordinal));
      FormatBytes.putLong(target, cursor + 48, columns.checkValueAt(ordinal));
      cursor += ENTRY_BYTES;
      for (int nameIndex = 0; nameIndex < nameBytes; nameIndex++) {
        target.put(cursor + nameIndex, (byte) columns.nameByteAt(ordinal, nameIndex));
      }
      cursor += nameBytes;
    }
    return StatusCode.OK;
  }

  static StatusCode decodeInto(
      ByteBuffer source,
      int start,
      int bytes,
      int expectedCount,
      int destinationStart,
      PackedColumnDescriptorBuilder columns,
      CatalogColumnConstraintAssembly constraints) {
    return CatalogColumnPayloadDecoder.decode(
        source, start, bytes, expectedCount, destinationStart, columns, constraints);
  }

  private static boolean validRange(ColumnDescriptorSet columns, int first, int count) {
    return columns != null && first >= 0 && count > 0
        && count <= CatalogDefinitionRecordCodec.MAX_COLUMN_RECORDS
        && first <= columns.count() - count;
  }

  private static long calculateBytes(ColumnDescriptorSet columns, int first, int count) {
    long bytes = HEADER_BYTES + (long) count * ENTRY_BYTES;
    for (int index = 0; index < count; index++) {
      bytes += columns.nameByteLength(first + index);
    }
    return bytes;
  }

  static boolean readable(ByteBuffer source, int start, int bytes) {
    return source != null && start >= 0 && bytes >= HEADER_BYTES
        && start <= source.limit() - bytes;
  }

  static int headerBytes() { return HEADER_BYTES; }
  static int entryBytes() { return ENTRY_BYTES; }
}
