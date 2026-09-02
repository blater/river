package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Transactional primitive storage behind packed catalog-column assembly. */
final class PackedColumnDescriptorStorage {
  private static final int INITIAL_NAME_BYTES = 64;
  private final PackedColumnArrayAllocator allocator;
  private int[] types;
  private byte[] nullable;
  private int[] offsets;
  private int[] lengths;
  private byte[] names;
  private int capacity;
  private int maximumNameBytes;
  private int count;
  private int nameBytes;

  PackedColumnDescriptorStorage(PackedColumnArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
  }

  StatusCode begin(int columns, int maximumBytes) {
    if (columns <= 0 || columns > SqlShapeLimits.MAX_TABLE_COLUMNS
        || maximumBytes < columns || maximumBytes > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      int[] nextTypes = allocator.integers(columns);
      byte[] nextNullable = allocator.bytes(columns);
      int[] nextOffsets = allocator.integers(columns);
      int[] nextLengths = allocator.integers(columns);
      byte[] nextNames = allocator.bytes(Math.min(maximumBytes, INITIAL_NAME_BYTES));
      types = nextTypes;
      nullable = nextNullable;
      offsets = nextOffsets;
      lengths = nextLengths;
      names = nextNames;
      capacity = columns;
      maximumNameBytes = maximumBytes;
      count = 0;
      nameBytes = 0;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode reserve(int addedColumns, int addedBytes) {
    if (addedColumns <= 0 || addedBytes < addedColumns
        || count > capacity - addedColumns || nameBytes > maximumNameBytes - addedBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int required = nameBytes + addedBytes;
    if (required <= names.length) return StatusCode.OK;
    int grown = Math.max(required, Math.min(maximumNameBytes, Math.max(1, names.length) * 2));
    try {
      byte[] next = allocator.bytes(grown);
      System.arraycopy(names, 0, next, 0, nameBytes);
      names = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode put(
      int relativeColumn, int descriptor, boolean isNullable,
      ByteBuffer source, int sourceOffset, int bytes, int relativeNameOffset) {
    int column = count + relativeColumn;
    int target = nameBytes + relativeNameOffset;
    if (relativeColumn < 0 || column < count || column >= capacity
        || relativeNameOffset < 0 || bytes <= 0 || target < nameBytes
        || target > names.length - bytes || !SqlTypeDescriptor.isValid(descriptor)
        || Utf8Text.validate(
            source, sourceOffset, bytes, ColumnDescriptorSet.MAXIMUM_NAME_SCALARS) < 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < bytes; index++) names[target + index] = source.get(sourceOffset + index);
    types[column] = descriptor;
    nullable[column] = (byte) (isNullable ? 1 : 0);
    offsets[column] = target;
    lengths[column] = bytes;
    return StatusCode.OK;
  }

  StatusCode publish(int addedColumns, int addedBytes) {
    if (addedColumns <= 0 || addedBytes < addedColumns
        || count > capacity - addedColumns || nameBytes > names.length - addedBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    count += addedColumns;
    nameBytes += addedBytes;
    return StatusCode.OK;
  }

  void reset() {
    types = null;
    nullable = null;
    offsets = null;
    lengths = null;
    names = null;
    capacity = 0;
    maximumNameBytes = 0;
    count = 0;
    nameBytes = 0;
  }

  void release() { reset(); }
  int count() { return count; }
  int capacity() { return capacity; }
  int nameBytes() { return nameBytes; }
  int[] types() { return types; }
  byte[] nullable() { return nullable; }
  int[] offsets() { return offsets; }
  int[] lengths() { return lengths; }
  byte[] names() { return names; }
  int[] allocateSlots(int size) { return allocator.integers(size); }
}
