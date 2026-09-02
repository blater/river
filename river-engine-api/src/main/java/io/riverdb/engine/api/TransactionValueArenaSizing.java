package io.riverdb.engine.api;

/** Authoritative chunk and directory sizing for transaction values and text. */
final class TransactionValueArenaSizing {
  private TransactionValueArenaSizing() { }

  static long maximumRetainedBytes(int slots, int textCharacters) {
    if (slots < 0 || textCharacters < 0) return -1;
    long valueChunks = chunks(slots, TransactionValueChunk.SIZE);
    long textChunks = chunks(textCharacters, TransactionTextChunk.SIZE);
    if (valueChunks > Integer.MAX_VALUE || textChunks > Integer.MAX_VALUE) return -1;
    long bytes = multiply(valueChunks, TransactionValueChunk.RETAINED_BYTES);
    bytes = add(bytes, directoryBytes(capacity((int) valueChunks)));
    bytes = add(bytes, multiply(textChunks, TransactionTextChunk.RETAINED_BYTES));
    return add(bytes, directoryBytes(capacity((int) textChunks)));
  }

  private static int capacity(int needed) {
    if (needed == 0) return 0;
    int capacity = 4;
    while (capacity < needed) {
      int next = capacity << 1;
      if (next <= capacity) return needed;
      capacity = next;
    }
    return capacity;
  }

  private static long chunks(int values, int size) {
    return ((long) values + size - 1L) / size;
  }

  private static long directoryBytes(int capacity) {
    return capacity == 0 ? 0
        : TransactionValueArena.DIRECTORY_HEADER_BYTES + (long) capacity * Long.BYTES;
  }

  private static long multiply(long left, long right) {
    return left < 0 || right < 0 || left != 0 && right > Long.MAX_VALUE / left
        ? -1 : left * right;
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
