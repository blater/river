package io.riverdb.protocol;

import java.nio.ByteBuffer;

/** Shared typed-value wire header with an unsigned-compatible nonnegative byte length. */
final class ProtocolValueHeader {
  static final int DESCRIPTOR_OFFSET = 0;
  static final int FLAGS_OFFSET = Integer.BYTES;
  static final int RESERVED_OFFSET = FLAGS_OFFSET + Byte.BYTES;
  static final int LENGTH_OFFSET = RESERVED_OFFSET + Byte.BYTES;
  static final int BYTES = LENGTH_OFFSET + Integer.BYTES;

  private ProtocolValueHeader() { }

  static int length(ByteBuffer source, int offset) {
    return source.getInt(offset + LENGTH_OFFSET);
  }

  static int write(
      ByteBuffer target, int offset, int descriptor, int flags, int length) {
    target.putInt(offset + DESCRIPTOR_OFFSET, descriptor);
    target.put(offset + FLAGS_OFFSET, (byte) flags);
    target.put(offset + RESERVED_OFFSET, (byte) 0);
    target.putInt(offset + LENGTH_OFFSET, length);
    return offset + BYTES;
  }
}
