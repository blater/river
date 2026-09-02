package io.riverdb.protocol;

import java.nio.ByteBuffer;

/** Canonical little-bit-order null bitmap used inside v4 envelopes. */
final class ProtocolV4Bitmap {
  private ProtocolV4Bitmap() { }

  static int bytes(int elements) {
    return elements < 0 || elements > ProtocolV4EnvelopeCodec.MAXIMUM_PARAMETERS
        ? -1 : (elements + Byte.SIZE - 1) >>> 3;
  }

  static boolean validWords(int elements, long[] words, int count) {
    int required = (elements + Long.SIZE - 1) >>> 6;
    if (words == null) return count == 0;
    if (count != required || count > words.length) return false;
    int used = elements & 63;
    return required == 0 || used == 0 || (words[required - 1] & (-1L << used)) == 0;
  }

  static boolean validBytes(ByteBuffer source, int offset, int length, int elements) {
    if (offset < 0 || length < 0 || offset > source.limit() - length) return false;
    int trailing = elements & 7;
    return length == 0 || trailing == 0
        || ((source.get(offset + length - 1) & 0xff) & ~((1 << trailing) - 1)) == 0;
  }

  static void write(ByteBuffer target, int offset, int bytes, long[] words) {
    for (int index = 0; index < bytes; index++) {
      target.put(offset + index, (byte) (words[index >>> 3] >>> ((index & 7) << 3)));
    }
  }
}
