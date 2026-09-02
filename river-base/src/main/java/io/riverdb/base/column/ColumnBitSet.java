package io.riverdb.base.column;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Reusable caller-owned bit state whose storage grows only through {@link #reserve}. */
public final class ColumnBitSet {
  private long[] words;
  private int bitCount;
  private int wordCount;

  /**
   * Ensures backing storage for {@code requestedBits} without changing the logical size or bits.
   * The caller's semantic maximum bounds geometric growth.
   */
  public StatusCode reserve(int requestedBits, int maximumBits) {
    if (requestedBits < 0 || maximumBits < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (requestedBits > maximumBits) return StatusCode.RESOURCE_EXHAUSTED;
    if (bitCount > maximumBits) return StatusCode.RESOURCE_EXHAUSTED;

    int requiredWords = wordsFor(requestedBits);
    int currentWords = words == null ? 0 : words.length;
    if (requiredWords <= currentWords) return StatusCode.OK;

    int maximumWords = wordsFor(maximumBits);
    int grownWords = BoundedArrayGrowth.capacity(currentWords, requiredWords, maximumWords, 1);

    long[] grown;
    try {
      grown = new long[grownWords];
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (wordCount != 0) System.arraycopy(words, 0, grown, 0, wordCount);
    words = grown;
    return StatusCode.OK;
  }

  /** Changes the logical size and clears all words used by the prior logical state. */
  public StatusCode clearForSize(int bits) {
    if (bits < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int requiredWords = wordsFor(bits);
    if (requiredWords > backingWordCount()) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < wordCount; index++) words[index] = 0;
    bitCount = bits;
    wordCount = requiredWords;
    return StatusCode.OK;
  }

  /** Clears the logical state while retaining its high-water storage. */
  public void reset() {
    for (int index = 0; index < wordCount; index++) words[index] = 0;
    bitCount = 0;
    wordCount = 0;
  }

  /** Clears logical state and returns all backing storage to its owner. */
  public void release() {
    words = null;
    bitCount = 0;
    wordCount = 0;
  }

  public boolean get(int bit) {
    return validBit(bit) && (words[bit >>> 6] & mask(bit)) != 0;
  }

  public boolean set(int bit) {
    if (!validBit(bit)) return false;
    int word = bit >>> 6;
    words[word] |= mask(bit);
    return true;
  }

  public boolean clear(int bit) {
    if (!validBit(bit)) return false;
    int word = bit >>> 6;
    words[word] &= ~mask(bit);
    return true;
  }

  public long word(int index) {
    return index >= 0 && index < wordCount ? words[index] : 0;
  }

  /** Sets a logical word only when bits outside the logical size remain zero. */
  public boolean setWord(int index, long value) {
    if (index < 0 || index >= wordCount || !canonicalLastWord(index, value)) return false;
    words[index] = value;
    return true;
  }

  public boolean isEmpty() {
    for (int index = 0; index < wordCount; index++) {
      if (words[index] != 0) return false;
    }
    return true;
  }

  public boolean copyWords(long[] destination) {
    return copyWords(destination, 0);
  }

  /** Copies the logical words into caller-owned storage without exposing backing storage. */
  public boolean copyWords(long[] destination, int offset) {
    if (destination == null || offset < 0 || offset > destination.length - wordCount) return false;
    if (wordCount != 0) System.arraycopy(words, 0, destination, offset, wordCount);
    return true;
  }

  /** Returns the canonical bitmap length for the current logical size. */
  public int encodedByteCount() {
    return bytesFor(bitCount);
  }

  /**
   * Encodes exactly {@link #encodedByteCount()} bytes into caller-owned storage. Bit {@code n} is
   * encoded as {@code 1 << (n & 7)} in byte {@code n >>> 3}.
   */
  public StatusCode encode(byte[] destination, int offset, int length) {
    int encodedBytes = encodedByteCount();
    if (!validRange(destination, offset, length) || length != encodedBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int byteIndex = 0; byteIndex < encodedBytes; byteIndex++) {
      int shift = (byteIndex & 7) << 3;
      destination[offset + byteIndex] = (byte) (words[byteIndex >>> 3] >>> shift);
    }
    return StatusCode.OK;
  }

  /**
   * Decodes a canonical bitmap with the expected logical size. Validation and capacity admission
   * complete before the prior logical state is replaced.
   */
  public StatusCode decode(
      byte[] source, int offset, int length, int expectedBits, int maximumBits) {
    if (expectedBits < 0 || maximumBits < 0 || !validRange(source, offset, length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (expectedBits > maximumBits) return StatusCode.RESOURCE_EXHAUSTED;
    int encodedBytes = bytesFor(expectedBits);
    if (length != encodedBytes || !canonicalTrailingByte(source, offset, length, expectedBits)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    int decodedWords = wordsFor(expectedBits);
    if (decodedWords > backingWordCount()) {
      StatusCode reserved = reserve(expectedBits, maximumBits);
      if (!reserved.isOk()) return reserved;
    }

    for (int index = 0; index < wordCount; index++) words[index] = 0;
    for (int wordIndex = 0; wordIndex < decodedWords; wordIndex++) {
      int firstByte = wordIndex << 3;
      int wordBytes = Math.min(8, encodedBytes - firstByte);
      long value = 0;
      for (int byteIndex = 0; byteIndex < wordBytes; byteIndex++) {
        value |= (long) (source[offset + firstByte + byteIndex] & 0xff) << (byteIndex << 3);
      }
      words[wordIndex] = value;
    }
    bitCount = expectedBits;
    wordCount = decodedWords;
    return StatusCode.OK;
  }

  /** Replaces this state with {@code source} when it fits the caller's semantic maximum. */
  public StatusCode copyFrom(ColumnBitSet source, int maximumBits) {
    if (source == null || maximumBits < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.bitCount > maximumBits) return StatusCode.RESOURCE_EXHAUSTED;
    if (source == this) return StatusCode.OK;

    if (source.wordCount > backingWordCount()) {
      StatusCode reserved = reserve(source.bitCount, maximumBits);
      if (!reserved.isOk()) return reserved;
    }
    for (int index = 0; index < wordCount; index++) words[index] = 0;
    if (source.wordCount != 0) {
      System.arraycopy(source.words, 0, words, 0, source.wordCount);
    }
    bitCount = source.bitCount;
    wordCount = source.wordCount;
    return StatusCode.OK;
  }

  public int bitCount() {
    return bitCount;
  }

  public int wordCount() {
    return wordCount;
  }

  /** Returns usable backing capacity in bits, saturated to the {@code int} index domain. */
  public int capacity() {
    int backingWords = backingWordCount();
    return backingWords > Integer.MAX_VALUE / Long.SIZE
        ? Integer.MAX_VALUE
        : backingWords * Long.SIZE;
  }

  private int backingWordCount() {
    return words == null ? 0 : words.length;
  }

  private boolean validBit(int bit) {
    return bit >= 0 && bit < bitCount;
  }

  private boolean canonicalLastWord(int index, long value) {
    if (index != wordCount - 1) return true;
    int trailingBits = bitCount & 63;
    if (trailingBits == 0) return true;
    long allowed = (1L << trailingBits) - 1;
    return (value & ~allowed) == 0;
  }

  private static int wordsFor(int bits) {
    return (int) (((long) bits + Long.SIZE - 1) / Long.SIZE);
  }

  private static int bytesFor(int bits) {
    return (int) (((long) bits + Byte.SIZE - 1) / Byte.SIZE);
  }

  private static boolean canonicalTrailingByte(
      byte[] source, int offset, int length, int bits) {
    int trailingBits = bits & 7;
    if (length == 0 || trailingBits == 0) return true;
    int allowed = (1 << trailingBits) - 1;
    return ((source[offset + length - 1] & 0xff) & ~allowed) == 0;
  }

  private static boolean validRange(byte[] bytes, int offset, int length) {
    return bytes != null && offset >= 0 && length >= 0 && offset <= bytes.length - length;
  }

  private static long mask(int bit) {
    return 1L << (bit & 63);
  }
}
