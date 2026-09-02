package io.riverdb.engine.table;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded chunked encoded-key payload for tuple intents. */
final class IndexedTupleIntentPayload {
  static final int SHIFT = 15;
  static final int SIZE = 1 << SHIFT;
  static final int MASK = SIZE - 1;
  private byte[][] chunks = new byte[0][];

  void reserve(int required) {
    int needed = chunksFor(required);
    if (needed <= chunks.length) return;
    byte[][] next = Arrays.copyOf(chunks, needed);
    for (int index = chunks.length; index < needed; index++) next[index] = new byte[SIZE];
    chunks = next;
  }
  int chunks() { return chunks.length; }
  byte get(int offset) { return chunks[offset >> SHIFT][offset & MASK]; }
  void put(int offset, byte value) { chunks[offset >> SHIFT][offset & MASK] = value; }
  void copyFrom(int target, ByteBuffer source, int offset, int length) {
    for (int index = 0; index < length; index++) put(target + index, source.get(offset + index));
  }
  void copyTo(int source, int length, ByteBuffer target, int targetOffset) {
    for (int index = 0; index < length; index++) target.put(targetOffset + index, get(source + index));
  }
  void copyTo(int source, int length, byte[] target) {
    for (int index = 0; index < length; index++) target[index] = get(source + index);
  }
  void clear(int from, int to) { for (int index = from; index < to; index++) put(index, (byte) 0); }
  void release() { chunks = new byte[0][]; }
  static int chunksFor(int bytes) {
    if (bytes < 0 || bytes > Integer.MAX_VALUE - MASK) return -1;
    return (bytes + MASK) >> SHIFT;
  }
}
