package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reusable UTF-8 view for copying one wire value into a program argument. */
final class ProtocolProgramTextDecoder {
  private static final int RETAINED_CHARACTER_FLOOR = 4 * 1024;
  private char[] characters = new char[0];
  private int length;
  private final RetainedMemoryLease memory;
  private final View view = new View();

  ProtocolProgramTextDecoder() {
    this(RetainedMemoryLease.unbounded());
  }

  ProtocolProgramTextDecoder(RetainedMemoryLease memory) {
    if (memory == null) throw new IllegalArgumentException("memory");
    this.memory = memory;
  }

  StatusCode decode(ByteBuffer source, int offset, int bytes) {
    int required = Utf8Text.decodedLength(source, offset, bytes);
    if (required < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (required > characters.length) {
      int capacity = required;
      StatusCode admitted = memory.resize((long) capacity * Character.BYTES);
      if (!admitted.isOk()) return admitted;
      try {
        characters = Arrays.copyOf(characters, capacity);
      } catch (OutOfMemoryError failure) {
        memory.resize((long) characters.length * Character.BYTES);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    int decoded = Utf8Text.decode(source, offset, bytes, characters, 0);
    if (decoded < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    length = decoded;
    return StatusCode.OK;
  }

  CharSequence view() {
    view.length = length;
    return view;
  }

  void reset() {
    Arrays.fill(characters, 0, length, '\0');
    length = 0;
  }

  StatusCode releaseHighWater() {
    reset();
    int capacity = Math.min(characters.length, RETAINED_CHARACTER_FLOOR);
    if (capacity == characters.length) return StatusCode.OK;
    try {
      characters = new char[capacity];
      return memory.resize((long) capacity * Character.BYTES);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode release() {
    reset();
    characters = new char[0];
    return memory.resize(0);
  }

  static long maximumRetainedBytes(int characters) {
    return characters < 0 ? -1 : (long) characters * Character.BYTES;
  }

  private final class View implements CharSequence {
    private int length;

    @Override public int length() { return length; }
    @Override public char charAt(int index) {
      return index >= 0 && index < length ? characters[index] : 0;
    }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
