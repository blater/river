package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Immutable packed UTF-8 names and primitive open-addressed ordinal lookup. */
final class ColumnNameTable {
  private final int[] offsets;
  private final int[] lengths;
  private final byte[] bytes;
  private final int[] slots;
  private final ByteBuffer view;

  ColumnNameTable(int[] nameOffsets, int[] nameLengths, byte[] packed, int[] nameSlots) {
    offsets = nameOffsets;
    lengths = nameLengths;
    bytes = packed;
    slots = nameSlots;
    view = ByteBuffer.wrap(packed);
  }

  static final class Result {
    private ColumnNameTable value;

    void reset() {
      value = null;
    }

    ColumnNameTable value() {
      return value;
    }

    void set(ColumnNameTable published) {
      value = published;
    }
  }

  int length(int index) {
    return valid(index) ? lengths[index] : -1;
  }

  int byteAt(int index, int byteIndex) {
    return valid(index) && byteIndex >= 0 && byteIndex < lengths[index]
        ? Byte.toUnsignedInt(bytes[offsets[index] + byteIndex]) : -1;
  }

  StatusCode copyBytes(int index, byte[] destination, int destinationOffset) {
    if (!valid(index) || destination == null || destinationOffset < 0
        || destinationOffset > destination.length - lengths[index]) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(bytes, offsets[index], destination, destinationOffset, lengths[index]);
    return StatusCode.OK;
  }

  int copyChars(int index, char[] destination, int destinationOffset) {
    return !valid(index) || destination == null || destinationOffset < 0 ? -1
        : Utf8Text.decode(view, offsets[index], lengths[index], destination, destinationOffset);
  }

  int find(CharSequence name) {
    int length = Utf8NameCodec.length(name);
    if (length < 1 || slots.length == 0) return -1;
    long hash = Utf8NameCodec.hash(name);
    int slot = ((int) hash) & (slots.length - 1);
    while (slots[slot] != 0) {
      int ordinal = slots[slot] - 1;
      if (lengths[ordinal] == length
          && Utf8NameCodec.hash(bytes, offsets[ordinal], length) == hash
          && Utf8NameCodec.equals(bytes, offsets[ordinal], length, name)) return ordinal;
      slot = (slot + 1) & (slots.length - 1);
    }
    return -1;
  }

  long byteCharge() {
    return SchemaByteCharge.columnSet(lengths.length, bytes.length, slots.length);
  }

  int byteLength() {
    return bytes.length;
  }

  private boolean valid(int index) {
    return index >= 0 && index < lengths.length;
  }

}
