package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.protocol.ProtocolResponse;
import java.util.Arrays;

/** Owns the reusable metadata arrays for one remote query generation. */
final class RiverClientQueryMetadata implements QueryMetadata {
  private String[] columnNames = new String[8];
  private int[] typeDescriptors = new int[8];
  private long[] nullableWords = new long[1];
  private long generation;
  private int maximumTextBytes;
  private int columnCount;

  StatusCode prepare(ProtocolResponse response) {
    int columns = response.columnCount();
    if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (generation == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long textBytes = 0;
    for (int index = 0; index < columns; index++) {
      int descriptor = response.typeDescriptorAt(index);
      if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.CORRUPTION;
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        textBytes += (long) SqlTypeDescriptor.parameterOne(descriptor) * 4;
        if (textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }
    }
    StatusCode status = reserve(columns);
    if (!status.isOk()) return status;
    Arrays.fill(nullableWords, 0L);
    for (int index = 0; index < columns; index++) {
      if (response.columnIsNullable(index)) {
        nullableWords[index >>> 6] |= 1L << (index & 63);
      }
    }
    for (int index = 0; index < columns; index++) {
      columnNames[index] = response.columnName(index);
      typeDescriptors[index] = response.typeDescriptorAt(index);
    }
    columnCount = columns;
    maximumTextBytes = (int) textBytes;
    generation++;
    return StatusCode.OK;
  }

  boolean matches(ProtocolResponse response, int columns) {
    if (columns != columnCount) return false;
    for (int index = 0; index < columns; index++) {
      if (response.typeDescriptorAt(index) != typeDescriptors[index]) return false;
    }
    return true;
  }

  int[] descriptors() { return typeDescriptors; }

  void clear() {
    for (int index = 0; index < columnNames.length; index++) columnNames[index] = null;
    for (int index = 0; index < columnCount; index++) typeDescriptors[index] = 0;
    Arrays.fill(nullableWords, 0L);
    columnCount = 0;
    maximumTextBytes = 0;
  }

  @Override
  public int columnCount() { return columnCount; }

  @Override
  public int maximumEncodedTextBytes() { return maximumTextBytes; }

  @Override
  public long reservationGeneration() { return generation; }

  public CharSequence columnName(int index) {
    return index >= 0 && index < columnCount ? columnNames[index] : null;
  }

  public int columnTypeDescriptor(int index) {
    return index >= 0 && index < columnCount ? typeDescriptors[index] : 0;
  }

  public boolean columnIsNullable(int index) {
    return index >= 0 && index < columnCount
        && (nullableWords[index >>> 6] & 1L << (index & 63)) != 0;
  }

  private StatusCode reserve(int columns) {
    if (columns <= columnNames.length) return StatusCode.OK;
    int capacity = Math.min(
        SqlShapeLimits.MAX_RESULT_COLUMNS, Math.max(columns, columnNames.length << 1));
    try {
      String[] names = Arrays.copyOf(columnNames, capacity);
      int[] descriptors = Arrays.copyOf(typeDescriptors, capacity);
      long[] nullable = Arrays.copyOf(nullableWords, (capacity + 63) >>> 6);
      columnNames = names;
      typeDescriptors = descriptors;
      nullableWords = nullable;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
