package io.riverdb.sql;

import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Owns bounded UTF-8 text handles stored inside a reusable SQL command. */
final class SqlCommandTextStore {
  private SqlCommandTextStore() { }

  static long store(SqlCommand command, char[] source, int offset, int length) {
    int bytes = Utf8Text.encode(
        source,
        offset,
        length,
        Utf8Text.MAXIMUM_SCALARS,
        command.textBytes,
        command.textBytesUsed);
    if (bytes < 0) return SqlCommand.INVALID_TEXT_HANDLE;
    long handle = (long) command.textBytesUsed << 32 | Integer.toUnsignedLong(bytes);
    command.textBytesUsed += bytes;
    return handle;
  }

  static long copyFrom(SqlCommand command, SqlCommand source, long handle) {
    int length = source == null ? -1 : length(source, handle);
    if (length < 0 || length > command.textBytes.length - command.textBytesUsed) {
      return SqlCommand.INVALID_TEXT_HANDLE;
    }
    int sourceOffset = (int) (handle >>> 32);
    int destinationOffset = command.textBytesUsed;
    System.arraycopy(source.textBytes, sourceOffset, command.textBytes, destinationOffset, length);
    command.textBytesUsed += length;
    return (long) destinationOffset << 32 | Integer.toUnsignedLong(length);
  }

  static long successor(SqlCommand command, long handle) {
    int length = length(command, handle);
    if (length < 0 || length >= command.textBytes.length - command.textBytesUsed) {
      return SqlCommand.INVALID_TEXT_HANDLE;
    }
    int sourceOffset = (int) (handle >>> 32);
    int destinationOffset = command.textBytesUsed;
    System.arraycopy(command.textBytes, sourceOffset, command.textBytes, destinationOffset, length);
    command.textBytes[destinationOffset + length] = 0;
    command.textBytesUsed += length + 1;
    return (long) destinationOffset << 32 | Integer.toUnsignedLong(length + 1);
  }

  static int compare(SqlCommand command, long left, long right) {
    int leftLength = length(command, left);
    int rightLength = length(command, right);
    if (leftLength < 0 || rightLength < 0) return Integer.MIN_VALUE;
    int leftOffset = (int) (left >>> 32);
    int rightOffset = (int) (right >>> 32);
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Byte.toUnsignedInt(command.textBytes[leftOffset + index])
          - Byte.toUnsignedInt(command.textBytes[rightOffset + index]);
      if (compared != 0) return compared;
    }
    return leftLength - rightLength;
  }

  static int length(SqlCommand command, long handle) {
    int textOffset = (int) (handle >>> 32);
    int length = (int) handle;
    return textOffset >= 0 && length >= 0 && textOffset <= command.textBytesUsed - length
        ? length : -1;
  }

  static int copy(SqlCommand command, long handle, ByteBuffer target) {
    int textOffset = (int) (handle >>> 32);
    int length = length(command, handle);
    if (length < 0 || target == null || target.remaining() < length) return -1;
    target.put(command.textBytes, textOffset, length);
    return length;
  }

  static byte byteAt(SqlCommand command, long handle, int index) {
    int textOffset = (int) (handle >>> 32);
    int length = length(command, handle);
    return index >= 0 && index < length ? command.textBytes[textOffset + index] : 0;
  }
}
