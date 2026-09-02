package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Builds one checked packed-name snapshot and its primitive lookup table. */
final class ColumnNameTableBuilder {
  private static final int INITIAL_SLOTS = 2;

  private ColumnNameTableBuilder() {
  }

  static StatusCode build(
      CharSequence[] names,
      int offset,
      int count,
      int maximumBytes,
      ColumnNameTable.Result result,
      StatusDetail detail) {
    try {
      String[] snapshot = new String[count];
      int packedLength = snapshot(names, offset, count, maximumBytes, snapshot, detail);
      if (packedLength < 0) return detailCode(detail, packedLength);
      int[] offsets = new int[count];
      int[] lengths = new int[count];
      byte[] bytes = new byte[packedLength];
      int[] slots = new int[slotCount(count)];
      int packed = 0;
      for (int index = 0; index < count; index++) {
        int expected = Utf8NameCodec.length(snapshot[index]);
        int encoded = Utf8NameCodec.encode(snapshot[index], bytes, packed);
        if (expected != encoded) {
          return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "name changed during encoding");
        }
        offsets[index] = packed;
        lengths[index] = encoded;
        packed += encoded;
        if (!insert(slots, bytes, offsets, lengths, index)) {
          return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "duplicate column name");
        }
      }
      if (packed != packedLength) {
        return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "name bytes changed during encoding");
      }
      result.set(new ColumnNameTable(offsets, lengths, bytes, slots));
      if (detail != null) detail.set(StatusCode.OK);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "name capacity unavailable");
    }
  }

  private static int snapshot(
      CharSequence[] names, int offset, int count, int maximumBytes,
      String[] snapshot, StatusDetail detail) {
    int packed = 0;
    for (int index = 0; index < count; index++) {
      CharSequence source = names[offset + index];
      if (source == null) return failLength(detail, "invalid column name");
      snapshot[index] = source.toString();
      int length = Utf8NameCodec.length(snapshot[index]);
      if (length < 1) return failLength(detail, "invalid column name");
      if (packed > maximumBytes - length) {
        fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column names exceed allowed bytes");
        if (detail != null) detail.append(" requested=").append(packed + length)
            .append(" allowed=").append(maximumBytes);
        return -2;
      }
      packed += length;
    }
    return packed;
  }

  private static int failLength(StatusDetail detail, CharSequence message) {
    fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, message);
    return -1;
  }

  private static StatusCode detailCode(StatusDetail detail, int code) {
    if (detail != null) return detail.code();
    return code == -2 ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean insert(
      int[] slots, byte[] bytes, int[] offsets, int[] lengths, int ordinal) {
    long hash = Utf8NameCodec.hash(bytes, offsets[ordinal], lengths[ordinal]);
    int slot = ((int) hash) & (slots.length - 1);
    while (slots[slot] != 0) {
      int existing = slots[slot] - 1;
      if (lengths[existing] == lengths[ordinal]
          && Utf8NameCodec.hash(bytes, offsets[existing], lengths[existing]) == hash
          && same(bytes, offsets[existing], offsets[ordinal], lengths[ordinal])) return false;
      slot = (slot + 1) & (slots.length - 1);
    }
    slots[slot] = ordinal + 1;
    return true;
  }

  private static boolean same(byte[] bytes, int left, int right, int length) {
    for (int index = 0; index < length; index++) {
      if (bytes[left + index] != bytes[right + index]) return false;
    }
    return true;
  }

  private static int slotCount(int count) {
    int slots = count == 0 ? 0 : INITIAL_SLOTS;
    while (slots != 0 && slots < count * 2) slots <<= 1;
    return slots;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
