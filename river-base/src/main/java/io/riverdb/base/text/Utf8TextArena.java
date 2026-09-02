package io.riverdb.base.text;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/**
 * Reusable, append-only UTF-8 storage for a bounded statement or result.
 *
 * <p>The byte array is owned by this arena and is never returned to callers. Reads either return
 * one byte or copy into caller-owned storage. Capacity is retained by {@link #reset()} so an
 * owner can warm the arena once and use it without per-value allocation.</p>
 */
public final class Utf8TextArena {
  private static final int INITIAL_GROWTH = 8;
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final ByteBuffer EMPTY_VIEW = ByteBuffer.wrap(EMPTY_BYTES);

  private byte[] bytes = EMPTY_BYTES;
  private ByteBuffer byteView = EMPTY_VIEW;
  private int used;
  private int maximumBytes;
  private int lastOffset = -1;
  private int lastLength;

  /** Logical bytes currently occupied by appended values. */
  public int used() {
    return used;
  }

  /** Retained byte-array capacity. */
  public int capacity() {
    return bytes.length;
  }

  /** Configured maximum logical bytes. */
  public int maximumBytes() {
    return maximumBytes;
  }

  /** Offset of the most recently appended value, or {@code -1} when empty. */
  public int lastOffset() {
    return lastOffset;
  }

  /** Length in bytes of the most recently appended value. */
  public int lastLength() {
    return lastLength;
  }

  /**
   * Sets the byte bound and reserves at least the requested capacity.
   *
   * <p>Growth is geometric and retains all previously published bytes. A failed allocation does
   * not publish the replacement array or alter the old bound.</p>
   */
  public StatusCode reserve(int requestedCapacity, int maximumBytes) {
    if (requestedCapacity < 0 || maximumBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (requestedCapacity > maximumBytes || used > maximumBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (requestedCapacity > bytes.length) {
      StatusCode status = grow(requestedCapacity, maximumBytes);
      if (status != StatusCode.OK) {
        return status;
      }
    }
    this.maximumBytes = maximumBytes;
    return StatusCode.OK;
  }

  /** Appends one strictly validated UTF-16 value subject to a scalar-count bound. */
  public StatusCode append(CharSequence value, int maximumScalars) {
    if (value == null || maximumScalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    int scalars = Utf8Text.scalarCount(value);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > maximumScalars) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }

    int encodedLength = encodedLength(value);
    if (encodedLength < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (encodedLength > maximumBytes - used) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int offset = used;
    int required = used + encodedLength;
    StatusCode status = ensureCapacity(required);
    if (status != StatusCode.OK) {
      return status;
    }
    encode(value, bytes, offset);
    used = required;
    lastOffset = offset;
    lastLength = encodedLength;
    return StatusCode.OK;
  }

  /** Appends one strictly validated caller-owned UTF-16 slice. */
  public StatusCode append(
      char[] value, int offset, int length, int maximumScalars) {
    if (maximumScalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalars = Utf8Text.scalarCount(value, offset, length);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > maximumScalars) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (length == 0) {
      lastOffset = used;
      lastLength = 0;
      return StatusCode.OK;
    }
    int encodedLength = Utf8Text.encodedLength(value, offset, length, maximumScalars);
    if (encodedLength < 0 || encodedLength > maximumBytes - used) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int targetOffset = used;
    int required = used + encodedLength;
    StatusCode status = ensureCapacity(required);
    if (!status.isOk()) {
      return status;
    }
    if (Utf8Text.encode(value, offset, length, maximumScalars, bytes, targetOffset)
        != encodedLength) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    used = required;
    lastOffset = targetOffset;
    lastLength = encodedLength;
    return StatusCode.OK;
  }

  /** Appends one canonical UTF-8 slice without changing the source buffer state. */
  public StatusCode append(
      ByteBuffer source, int offset, int length, int maximumScalars) {
    int scalars = Utf8Text.validate(source, offset, length, maximumScalars);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (length > maximumBytes - used) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int targetOffset = used;
    StatusCode status = ensureCapacity(used + length);
    if (!status.isOk()) {
      return status;
    }
    for (int index = 0; index < length; index++) {
      bytes[targetOffset + index] = source.get(offset + index);
    }
    used += length;
    lastOffset = targetOffset;
    lastLength = length;
    return StatusCode.OK;
  }

  /** Returns an unsigned byte, or {@code -1} for an invalid logical offset. */
  public int byteAt(int offset) {
    if (offset < 0 || offset >= used) {
      return -1;
    }
    return Byte.toUnsignedInt(bytes[offset]);
  }

  /** Copies raw UTF-8 bytes into caller-owned storage. */
  public StatusCode copyBytes(int offset, int length, byte[] destination, int destinationOffset) {
    if (!validRange(offset, length, used) || destination == null || destinationOffset < 0
        || destinationOffset > destination.length - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(bytes, offset, destination, destinationOffset, length);
    return StatusCode.OK;
  }

  /**
   * Decodes into caller-owned UTF-16 storage without allocating.
   *
   * @return the written UTF-16 code-unit count, or {@code -1} for invalid input or capacity
   */
  public int copyChars(int offset, int length, char[] destination, int destinationOffset) {
    if (!validRange(offset, length, used) || destination == null || destinationOffset < 0) {
      return -1;
    }
    return Utf8Text.decode(byteView, offset, length, destination, destinationOffset);
  }

  /** Clears logical use while retaining the high-water byte array. */
  public void reset() {
    used = 0;
    lastOffset = -1;
    lastLength = 0;
  }

  /** Clears logical state and returns all backing storage to its owner. */
  public void release() {
    bytes = EMPTY_BYTES;
    byteView = EMPTY_VIEW;
    used = 0;
    maximumBytes = 0;
    lastOffset = -1;
    lastLength = 0;
  }

  private StatusCode ensureCapacity(int required) {
    return grow(required, maximumBytes);
  }

  private StatusCode grow(int required, int maximumBytes) {
    if (required <= bytes.length) {
      return StatusCode.OK;
    }
    if (required < 0 || required > maximumBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int newCapacity = BoundedArrayGrowth.capacity(
        bytes.length, required, maximumBytes, INITIAL_GROWTH);
    byte[] replacement;
    ByteBuffer replacementView;
    try {
      replacement = new byte[newCapacity];
      replacementView = ByteBuffer.wrap(replacement);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    System.arraycopy(bytes, 0, replacement, 0, used);
    bytes = replacement;
    byteView = replacementView;
    return StatusCode.OK;
  }

  private static int encodedLength(CharSequence value) {
    return Utf8Text.encodedLength(value);
  }

  private static void encode(CharSequence value, byte[] target, int targetOffset) {
    int output = targetOffset;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, value.charAt(++index)) : first;
      output = putScalar(target, output, scalar);
    }
  }

  private static int putScalar(byte[] target, int offset, int scalar) {
    if (scalar <= 0x7f) {
      target[offset++] = (byte) scalar;
    } else if (scalar <= 0x7ff) {
      target[offset++] = (byte) (0xc0 | scalar >>> 6);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else if (scalar <= 0xffff) {
      target[offset++] = (byte) (0xe0 | scalar >>> 12);
      target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else {
      target[offset++] = (byte) (0xf0 | scalar >>> 18);
      target[offset++] = (byte) (0x80 | scalar >>> 12 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    }
    return offset;
  }

  private static boolean validRange(int offset, int length, int limit) {
    return offset >= 0 && length >= 0 && offset <= limit - length;
  }
}
