package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.util.Arrays;

/** Reusable primitive scalar stack and text arena for transaction expressions. */
final class TransactionScalarStack implements TransactionValueReader {
  private static final long HEADER_BYTES = 64L;
  private final RetainedMemoryLease memory;
  private int[] descriptors = new int[0];
  private long[] highs = new long[0];
  private long[] lows = new long[0];
  private int[] textOffsets = new int[0];
  private int[] textLengths = new int[0];
  private byte[] nulls = new byte[0];
  private char[] text = new char[0];
  private int depth;
  private int textCount;
  private long retainedBytes;

  TransactionScalarStack(RetainedMemoryLease retainedMemory) { memory = retainedMemory; }

  StatusCode prepare(int capacity) {
    if (capacity < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (capacity <= descriptors.length) return StatusCode.OK;
    int next = growth(descriptors.length, capacity);
    return resize(next, text.length);
  }

  void reset() {
    Arrays.fill(descriptors, 0, depth, 0);
    Arrays.fill(highs, 0, depth, 0);
    Arrays.fill(lows, 0, depth, 0);
    Arrays.fill(textOffsets, 0, depth, 0);
    Arrays.fill(textLengths, 0, depth, 0);
    Arrays.fill(nulls, 0, depth, (byte) 0);
    Arrays.fill(text, 0, textCount, (char) 0);
    depth = 0;
    textCount = 0;
  }

  StatusCode push(int descriptor, long high, long low, boolean isNull) {
    StatusCode status = prepare(depth + 1);
    if (!status.isOk()) return status;
    publish(depth++, descriptor, high, low, isNull, -1, -1);
    return StatusCode.OK;
  }

  StatusCode pushText(int descriptor, TransactionValueReader source, int slot) {
    int length = source.textLength(slot);
    if (length < 0) return StatusCode.DATATYPE_MISMATCH;
    StatusCode status = prepare(depth + 1);
    if (status.isOk()) status = prepareText(textCount + length);
    if (!status.isOk()) return status;
    int offset = textCount;
    for (int index = 0; index < length; index++) text[textCount++] = source.textCharacter(slot, index);
    publish(depth++, descriptor, 0, 0, false, offset, length);
    return StatusCode.OK;
  }

  StatusCode concatenate(int descriptor) {
    if (depth < 2) return StatusCode.INVARIANT_BROKEN;
    int left = depth - 2;
    int right = depth - 1;
    if (isNull(left) || isNull(right)) {
      depth--;
      publish(left, descriptor, 0, 0, true, -1, 0);
      return StatusCode.OK;
    }
    int total = textLengths[left] + textLengths[right];
    if (total < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = prepareText(textCount + total);
    if (!status.isOk()) return status;
    int offset = textCount;
    copyText(left);
    copyText(right);
    depth--;
    publish(left, descriptor, 0, 0, false, offset, total);
    return StatusCode.OK;
  }

  void unary(int descriptor, long high, long low, boolean isNull) {
    publish(depth - 1, descriptor, high, low, isNull, -1, -1);
  }
  void retagTop(int descriptor) { descriptors[depth - 1] = descriptor; }
  void binary(int descriptor, long high, long low, boolean isNull) {
    depth--;
    publish(depth - 1, descriptor, high, low, isNull, -1, -1);
  }
  void select(boolean condition, int descriptor, long convertedHigh, long convertedLow) {
    int chosen = condition ? depth - 2 : depth - 1;
    int target = depth - 3;
    if (textLengths[chosen] >= 0 && !isNull(chosen)) {
      publish(target, descriptor, 0, 0, false,
          textOffsets[chosen], textLengths[chosen]);
    } else {
      publish(target, descriptor, convertedHigh, convertedLow, isNull(chosen), -1, -1);
    }
    depth -= 2;
  }

  int depth() { return depth; }
  @Override public int descriptor(int slot) { return descriptors[slot]; }
  @Override public long high(int slot) { return highs[slot]; }
  @Override public long low(int slot) { return lows[slot]; }
  @Override public boolean isNull(int slot) { return nulls[slot] != 0; }
  @Override public int textLength(int slot) { return textLengths[slot]; }
  @Override public char textCharacter(int slot, int character) {
    return text[textOffsets[slot] + character];
  }

  StatusCode release() {
    reset();
    long charge = retainedBytes;
    descriptors = new int[0];
    highs = new long[0];
    lows = new long[0];
    textOffsets = new int[0];
    textLengths = new int[0];
    nulls = new byte[0];
    text = new char[0];
    StatusCode status = memory.resize(0);
    retainedBytes = status.isOk() ? 0 : charge;
    return status;
  }

  private void copyText(int slot) {
    int end = textOffsets[slot] + textLengths[slot];
    for (int index = textOffsets[slot]; index < end; index++) text[textCount++] = text[index];
  }
  private void publish(
      int slot, int descriptor, long high, long low,
      boolean isNull, int textOffset, int textLength) {
    descriptors[slot] = descriptor;
    highs[slot] = high;
    lows[slot] = low;
    nulls[slot] = isNull ? (byte) 1 : 0;
    textOffsets[slot] = textOffset;
    textLengths[slot] = textLength;
  }
  private StatusCode prepareText(int needed) {
    if (needed < 0) return StatusCode.RESOURCE_EXHAUSTED;
    return needed <= text.length ? StatusCode.OK : resize(descriptors.length, growth(text.length, needed));
  }
  private StatusCode resize(int slots, int characters) {
    long nextBytes = bytes(slots, characters);
    StatusCode status = memory.resize(nextBytes);
    if (!status.isOk()) return status;
    try {
      int[] nextDescriptors = Arrays.copyOf(descriptors, slots);
      long[] nextHighs = Arrays.copyOf(highs, slots);
      long[] nextLows = Arrays.copyOf(lows, slots);
      int[] nextOffsets = Arrays.copyOf(textOffsets, slots);
      int[] nextLengths = Arrays.copyOf(textLengths, slots);
      byte[] nextNulls = Arrays.copyOf(nulls, slots);
      char[] nextText = Arrays.copyOf(text, characters);
      descriptors = nextDescriptors;
      highs = nextHighs;
      lows = nextLows;
      textOffsets = nextOffsets;
      textLengths = nextLengths;
      nulls = nextNulls;
      text = nextText;
      retainedBytes = nextBytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
  private static int growth(int current, int needed) {
    int next = current == 0 ? 8 : current << 1;
    return next > current ? Math.max(next, needed) : needed;
  }
  private static long bytes(int slots, int characters) {
    return slots == 0 && characters == 0 ? 0 : HEADER_BYTES
        + (long) slots * (Integer.BYTES * 3 + Long.BYTES * 2 + Byte.BYTES)
        + (long) characters * Character.BYTES;
  }
}
