package io.riverdb.protocol;

/** Caller-owned decoded protocol v4 envelope metadata. */
public final class ProtocolV4Envelope {
  private long[] maskWords = new long[0];
  private int kind;
  private int totalBytes;
  private int elementCount;
  private int prefixBytes;

  boolean set(
      int envelopeKind,
      int bytes,
      int elements,
      int prefix,
      java.nio.ByteBuffer source,
      int bitmapOffset,
      int bitmapBytes) {
    int words = (elements + Long.SIZE - 1) >>> 6;
    if (!reserveWords(words)) return false;
    for (int word = 0; word < maskWords.length; word++) maskWords[word] = 0;
    for (int index = 0; index < bitmapBytes; index++) {
      maskWords[index >>> 3] |=
          (long) (source.get(bitmapOffset + index) & 0xff) << ((index & 7) << 3);
    }
    kind = envelopeKind;
    totalBytes = bytes;
    elementCount = elements;
    prefixBytes = prefix;
    return true;
  }

  public void reset() {
    kind = 0;
    totalBytes = 0;
    elementCount = 0;
    prefixBytes = 0;
    for (int word = 0; word < maskWords.length; word++) maskWords[word] = 0;
  }

  public int kind() { return kind; }
  public int totalBytes() { return totalBytes; }
  public int elementCount() { return elementCount; }
  public int prefixBytes() { return prefixBytes; }
  public long maskWord(int word) {
    int words = (elementCount + Long.SIZE - 1) >>> 6;
    return word >= 0 && word < words ? maskWords[word] : 0;
  }

  private boolean reserveWords(int required) {
    if (required <= maskWords.length) return true;
    int maximum = (ProtocolV4EnvelopeCodec.MAXIMUM_PARAMETERS + Long.SIZE - 1) >>> 6;
    int capacity = Math.min(maximum,
        Math.max(required, Math.max(1, maskWords.length << 1)));
    try {
      long[] grown = new long[capacity];
      System.arraycopy(maskWords, 0, grown, 0, maskWords.length);
      maskWords = grown;
      return true;
    } catch (OutOfMemoryError failure) {
      return false;
    }
  }
}
