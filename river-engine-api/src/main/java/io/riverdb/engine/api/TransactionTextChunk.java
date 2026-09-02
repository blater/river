package io.riverdb.engine.api;

/** UTF-16 storage chunk; scalar validity is checked at the public input boundary. */
final class TransactionTextChunk {
  static final int SHIFT = 11;
  static final int SIZE = 1 << SHIFT;
  static final int MASK = SIZE - 1;
  static final long RETAINED_BYTES = 24L + (long) SIZE * Character.BYTES;

  final char[] characters = new char[SIZE];
}
