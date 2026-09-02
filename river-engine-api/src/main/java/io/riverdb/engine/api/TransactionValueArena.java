package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.util.Arrays;

/** Chunked, lease-accounted typed storage shared by transaction arguments and results. */
final class TransactionValueArena {
  static final long DIRECTORY_HEADER_BYTES = 24L;
  private final RetainedMemoryLease memory;
  private TransactionValueChunk[] values = new TransactionValueChunk[0];
  private TransactionTextChunk[] text = new TransactionTextChunk[0];
  private int valueChunkCount;
  private int textChunkCount;
  private int highSlot;
  private int textCharacters;
  private long retainedBytes;
  private final TextView textView = new TextView(this);

  TransactionValueArena(RetainedMemoryLease retainedMemory) {
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    memory = retainedMemory;
  }

  StatusCode setNull(int slot, int descriptor) {
    if (!validSlot(slot) || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepareSlot(slot);
    if (!status.isOk()) return status;
    publish(slot, descriptor, 0, 0, -1, 0, (byte) 2);
    return StatusCode.OK;
  }

  StatusCode setFixed(int slot, int descriptor, long high, long low) {
    if (!validSlot(slot) || !SqlValueDomain.validFixed(descriptor, low)
        || SqlTypeDescriptor.isWideDecimal(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepareSlot(slot);
    if (!status.isOk()) return status;
    publish(slot, descriptor, high, low, -1, 0, (byte) 1);
    return StatusCode.OK;
  }

  StatusCode setDecimal128(int slot, int descriptor, long high, long low) {
    if (!validSlot(slot) || !SqlValueDomain.validDecimal128(descriptor, high, low)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepareSlot(slot);
    if (!status.isOk()) return status;
    publish(slot, descriptor, high, low, -1, 0, (byte) 1);
    return StatusCode.OK;
  }

  StatusCode setText(int slot, int descriptor, CharSequence source) {
    if (!validSlot(slot) || source == null
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int scalars = Utf8Text.scalarCount(source);
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scalars > SqlTypeDescriptor.parameterOne(descriptor)) {
      return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    StatusCode status = prepareSlot(slot);
    if (status.isOk()) status = ensureText(textCharacters + source.length());
    if (!status.isOk()) return status;
    int offset = textCharacters;
    for (int index = 0; index < source.length(); index++) {
      textCharacter(textCharacters++, source.charAt(index));
    }
    publish(slot, descriptor, 0, 0, offset, source.length(), (byte) 1);
    return StatusCode.OK;
  }

  StatusCode copy(int targetSlot, TransactionValueArena source, int sourceSlot) {
    if (source == null || !source.isSet(sourceSlot)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.isNull(sourceSlot)) return setNull(targetSlot, source.descriptor(sourceSlot));
    if (source.isText(sourceSlot)) {
      return setText(targetSlot, source.descriptor(sourceSlot), source.textView(sourceSlot));
    }
    int descriptor = source.descriptor(sourceSlot);
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? setDecimal128(targetSlot, descriptor, source.high(sourceSlot), source.low(sourceSlot))
        : setFixed(targetSlot, descriptor, source.high(sourceSlot), source.low(sourceSlot));
  }

  void reset() {
    for (int slot = 0; slot < highSlot; slot++) {
      TransactionValueChunk chunk = values[slot >> TransactionValueChunk.SHIFT];
      int offset = slot & TransactionValueChunk.MASK;
      chunk.descriptors[offset] = 0;
      chunk.highs[offset] = 0;
      chunk.lows[offset] = 0;
      chunk.textOffsets[offset] = 0;
      chunk.textLengths[offset] = 0;
      chunk.states[offset] = 0;
    }
    for (int index = 0; index < textCharacters; index++) textCharacter(index, (char) 0);
    highSlot = 0;
    textCharacters = 0;
  }

  StatusCode release() {
    reset();
    StatusCode status = memory.resize(0);
    if (!status.isOk()) return status;
    values = new TransactionValueChunk[0];
    text = new TransactionTextChunk[0];
    valueChunkCount = 0;
    textChunkCount = 0;
    retainedBytes = 0;
    return StatusCode.OK;
  }

  boolean isSet(int slot) { return state(slot) != 0; }
  boolean isNull(int slot) { return state(slot) == 2; }
  boolean isText(int slot) {
    return isSet(slot) && SqlTypeDescriptor.typeId(descriptor(slot))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
  int descriptor(int slot) { return validExisting(slot) ? valueChunk(slot).descriptors[valueOffset(slot)] : 0; }
  long high(int slot) { return validExisting(slot) ? valueChunk(slot).highs[valueOffset(slot)] : 0; }
  long low(int slot) { return validExisting(slot) ? valueChunk(slot).lows[valueOffset(slot)] : 0; }
  int textLength(int slot) {
    return isText(slot) ? valueChunk(slot).textLengths[valueOffset(slot)] : -1;
  }
  char textCharacterAt(int slot, int character) {
    int length = textLength(slot);
    if (character < 0 || character >= length) return 0;
    int offset = valueChunk(slot).textOffsets[valueOffset(slot)] + character;
    return textCharacter(offset);
  }
  CharSequence textView(int slot) {
    textView.pointTo(slot);
    return textView;
  }
  long retainedBytes() { return retainedBytes; }
  int highSlot() { return highSlot; }

  private StatusCode prepareSlot(int slot) {
    if (slot == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    if (isSet(slot)) return StatusCode.CONFLICT;
    StatusCode status = ensureValues(slot + 1);
    if (status.isOk() && slot >= highSlot) highSlot = slot + 1;
    return status;
  }

  private StatusCode ensureValues(int slots) {
    if (slots < 0 || slots > Integer.MAX_VALUE - TransactionValueChunk.SIZE + 1) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = (slots + TransactionValueChunk.SIZE - 1) >> TransactionValueChunk.SHIFT;
    while (valueChunkCount < required) {
      int capacity = directoryCapacity(values.length, valueChunkCount + 1);
      if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
      long nextBytes = retainedBytes + TransactionValueChunk.RETAINED_BYTES
          + directoryDelta(values.length, capacity);
      StatusCode status = memory.resize(nextBytes);
      if (!status.isOk()) return status;
      try {
        TransactionValueChunk[] next = capacity == values.length
            ? values : Arrays.copyOf(values, capacity);
        next[valueChunkCount] = new TransactionValueChunk();
        values = next;
        valueChunkCount++;
        retainedBytes = nextBytes;
      } catch (OutOfMemoryError failure) {
        memory.resize(retainedBytes);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode ensureText(int characters) {
    if (characters < 0 || characters > Integer.MAX_VALUE - TransactionTextChunk.SIZE + 1) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = (characters + TransactionTextChunk.SIZE - 1) >> TransactionTextChunk.SHIFT;
    while (textChunkCount < required) {
      int capacity = directoryCapacity(text.length, textChunkCount + 1);
      if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
      long nextBytes = retainedBytes + TransactionTextChunk.RETAINED_BYTES
          + directoryDelta(text.length, capacity);
      StatusCode status = memory.resize(nextBytes);
      if (!status.isOk()) return status;
      try {
        TransactionTextChunk[] next = capacity == text.length
            ? text : Arrays.copyOf(text, capacity);
        next[textChunkCount] = new TransactionTextChunk();
        text = next;
        textChunkCount++;
        retainedBytes = nextBytes;
      } catch (OutOfMemoryError failure) {
        memory.resize(retainedBytes);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return StatusCode.OK;
  }

  private void publish(
      int slot, int descriptor, long high, long low,
      int textOffset, int textLength, byte state) {
    TransactionValueChunk chunk = valueChunk(slot);
    int offset = valueOffset(slot);
    chunk.descriptors[offset] = descriptor;
    chunk.highs[offset] = high;
    chunk.lows[offset] = low;
    chunk.textOffsets[offset] = textOffset;
    chunk.textLengths[offset] = textLength;
    chunk.states[offset] = state;
  }

  private byte state(int slot) {
    return validExisting(slot) ? valueChunk(slot).states[valueOffset(slot)] : 0;
  }
  private boolean validExisting(int slot) {
    return slot >= 0 && slot < highSlot && slot >> TransactionValueChunk.SHIFT < valueChunkCount;
  }
  private TransactionValueChunk valueChunk(int slot) {
    return values[slot >> TransactionValueChunk.SHIFT];
  }
  private int valueOffset(int slot) { return slot & TransactionValueChunk.MASK; }
  private char textCharacter(int index) {
    return text[index >> TransactionTextChunk.SHIFT].characters[index & TransactionTextChunk.MASK];
  }
  private void textCharacter(int index, char value) {
    text[index >> TransactionTextChunk.SHIFT].characters[index & TransactionTextChunk.MASK] = value;
  }
  private static boolean validSlot(int slot) { return slot >= 0; }
  private static int directoryCapacity(int current, int required) {
    if (required <= current) return current;
    int next = current == 0 ? 4 : current << 1;
    return next > current && next >= required ? next : required;
  }
  private static long directoryDelta(int current, int next) {
    long before = current == 0 ? 0 : DIRECTORY_HEADER_BYTES + (long) current * Long.BYTES;
    long after = DIRECTORY_HEADER_BYTES + (long) next * Long.BYTES;
    return after - before;
  }

  private static final class TextView implements CharSequence {
    private final TransactionValueArena arena;
    private int slot;

    private TextView(TransactionValueArena owner) {
      arena = owner;
    }

    private void pointTo(int valueSlot) { slot = valueSlot; }

    @Override public int length() { return arena.textLength(slot); }
    @Override public char charAt(int index) { return arena.textCharacterAt(slot, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
