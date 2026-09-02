package io.riverdb.client;

import java.util.Arrays;

/** Array allocation boundary for atomic retained client-result growth. */
interface RiverClientResultAllocator {
  RiverClientResultAllocator STANDARD = new RiverClientResultAllocator() {
    @Override public long[] copy(long[] source, int capacity) {
      return Arrays.copyOf(source, capacity);
    }

    @Override public int[] copy(int[] source, int capacity) {
      return Arrays.copyOf(source, capacity);
    }
  };

  long[] copy(long[] source, int capacity);

  int[] copy(int[] source, int capacity);
}
