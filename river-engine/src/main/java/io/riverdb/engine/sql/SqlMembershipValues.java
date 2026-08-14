package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns the bounded copied values and UTF-8 bytes used by nested membership predicates. */
final class SqlMembershipValues {
  static final int MAXIMUM_VALUES = 1_024;

  private final long[] values = new long[MAXIMUM_VALUES];
  private final long[] scratchValues = new long[MAXIMUM_VALUES];
  private final ByteBuffer text = ByteBuffer.allocateDirect(MAXIMUM_VALUES * Utf8Text.MAXIMUM_BYTES);
  private final ByteBuffer scratchText =
      ByteBuffer.allocateDirect(MAXIMUM_VALUES * Utf8Text.MAXIMUM_BYTES);
  private long[] recursiveValues;
  private ByteBuffer recursiveText;
  private int[] recursiveTextUsed;
  private int textUsed;
  private int scratchTextUsed;
  private int type;
  private int scratchType;

  void resetStatement() {
    textUsed = 0;
    scratchTextUsed = 0;
    type = 0;
    scratchType = 0;
  }

  void resetOutput(int bank) {
    if (bank == 1) {
      scratchTextUsed = 0;
      scratchType = 0;
    } else {
      textUsed = 0;
      type = 0;
    }
  }

  void resetRecursive(int depth) {
    if (recursiveTextUsed != null) {
      recursiveTextUsed[depth] = depth * MAXIMUM_VALUES * Utf8Text.MAXIMUM_BYTES;
    }
  }

  void setType(int bank, int descriptor) {
    if (bank == 1) {
      scratchType = descriptor;
    } else {
      type = descriptor;
    }
  }

  int type(int bank, int depth, int[] recursiveTypes) {
    return bank == 2 ? recursiveTypes[depth] : bank == 1 ? scratchType : type;
  }

  long value(int bank, int depth, int index) {
    if (bank == 2) {
      return recursiveValue(depth, index);
    }
    return bank == 1 ? scratchValues[index] : values[index];
  }

  long value(boolean scratch, int index) {
    return scratch ? scratchValues[index] : values[index];
  }

  void setValue(boolean scratch, int index, long value) {
    if (scratch) {
      scratchValues[index] = value;
    } else {
      values[index] = value;
    }
  }

  long recursiveValue(int depth, int index) {
    return recursiveValues[depth * MAXIMUM_VALUES + index];
  }

  void setRecursiveValue(int depth, int index, long value) {
    recursiveValues[depth * MAXIMUM_VALUES + index] = value;
  }

  StatusCode append(
      int bank,
      int depth,
      int index,
      long value,
      HeapRowResult source,
      boolean textValue) {
    if (!textValue) {
      setValue(bank, depth, index, value);
      return StatusCode.OK;
    }
    int sourceOffset = (int) (value >>> 32);
    int sourceLength = (int) value;
    ByteBuffer target = textBuffer(bank);
    int targetOffset = textUsed(bank, depth);
    if (!validText(source, sourceOffset, sourceLength)
        || targetOffset > target.capacity() - sourceLength) {
      return StatusCode.CORRUPTION;
    }
    for (int byteIndex = 0; byteIndex < sourceLength; byteIndex++) {
      target.put(targetOffset + byteIndex, source.getByte(sourceOffset + byteIndex));
    }
    setValue(bank, depth, index,
        (long) targetOffset << 32 | Integer.toUnsignedLong(sourceLength));
    setTextUsed(bank, depth, targetOffset + sourceLength);
    return StatusCode.OK;
  }

  boolean textEquals(
      HeapRowResult source,
      long sourceHandle,
      int bank,
      int depth,
      int index) {
    int sourceOffset = (int) (sourceHandle >>> 32);
    int sourceLength = (int) sourceHandle;
    long candidate = value(bank, depth, index);
    int candidateOffset = (int) (candidate >>> 32);
    int candidateLength = (int) candidate;
    ByteBuffer candidateBytes = textBuffer(bank);
    int used = textUsed(bank, depth);
    if (!validText(source, sourceOffset, sourceLength)
        || candidateOffset < 0
        || candidateLength < 0
        || candidateOffset > used - candidateLength
        || sourceLength != candidateLength) {
      return false;
    }
    for (int byteIndex = 0; byteIndex < sourceLength; byteIndex++) {
      if (source.getByte(sourceOffset + byteIndex)
          != candidateBytes.get(candidateOffset + byteIndex)) {
        return false;
      }
    }
    return true;
  }

  void ensureRecursiveState(int blocks) {
    if (recursiveValues != null) {
      return;
    }
    recursiveValues = new long[blocks * MAXIMUM_VALUES];
    recursiveText = ByteBuffer.allocateDirect(blocks * MAXIMUM_VALUES * Utf8Text.MAXIMUM_BYTES);
    recursiveTextUsed = new int[blocks];
  }

  private void setValue(int bank, int depth, int index, long value) {
    if (bank == 2) {
      setRecursiveValue(depth, index, value);
    } else {
      setValue(bank == 1, index, value);
    }
  }

  private ByteBuffer textBuffer(int bank) {
    return bank == 2 ? recursiveText : bank == 1 ? scratchText : text;
  }

  private int textUsed(int bank, int depth) {
    return bank == 2 ? recursiveTextUsed[depth] : bank == 1 ? scratchTextUsed : textUsed;
  }

  private void setTextUsed(int bank, int depth, int used) {
    if (bank == 2) {
      recursiveTextUsed[depth] = used;
    } else if (bank == 1) {
      scratchTextUsed = used;
    } else {
      textUsed = used;
    }
  }

  private static boolean validText(HeapRowResult source, int offset, int length) {
    return offset >= 0 && length >= 0 && offset <= source.length() - length;
  }
}
