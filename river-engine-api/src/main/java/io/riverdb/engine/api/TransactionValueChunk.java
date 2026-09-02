package io.riverdb.engine.api;

/** Primitive value lanes for one chunk of a reusable transaction arena. */
final class TransactionValueChunk {
  static final int SHIFT = 7;
  static final int SIZE = 1 << SHIFT;
  static final int MASK = SIZE - 1;
  static final long RETAINED_BYTES = 32L
      + (long) SIZE * (Integer.BYTES * 3 + Long.BYTES * 2 + Byte.BYTES);

  final int[] descriptors = new int[SIZE];
  final long[] highs = new long[SIZE];
  final long[] lows = new long[SIZE];
  final int[] textOffsets = new int[SIZE];
  final int[] textLengths = new int[SIZE];
  final byte[] states = new byte[SIZE];
}
