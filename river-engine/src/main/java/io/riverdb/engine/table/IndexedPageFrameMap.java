package io.riverdb.engine.table;

/** Allocation-free open-addressed page-id to frame-slot map. */
final class IndexedPageFrameMap {
  private final int mask;
  private final int[] keys;
  private final int[] slots;

  IndexedPageFrameMap(int capacity) {
    mask = capacity - 1;
    keys = new int[capacity];
    slots = new int[capacity];
  }

  int find(int key) {
    if (key <= 0) return -1;
    int index = mix(key) & mask;
    while (keys[index] != 0) {
      if (keys[index] == key) return slots[index] - 1;
      index = (index + 1) & mask;
    }
    return -1;
  }

  void put(int key, int slot) {
    int index = mix(key) & mask;
    while (keys[index] != 0 && keys[index] != key) index = (index + 1) & mask;
    keys[index] = key;
    slots[index] = slot + 1;
  }

  void remove(int key) {
    if (key <= 0) return;
    int index = mix(key) & mask;
    while (keys[index] != 0 && keys[index] != key) index = (index + 1) & mask;
    if (keys[index] == 0) return;
    keys[index] = 0;
    slots[index] = 0;
    index = (index + 1) & mask;
    while (keys[index] != 0) {
      int movedKey = keys[index];
      int movedSlot = slots[index] - 1;
      keys[index] = 0;
      slots[index] = 0;
      put(movedKey, movedSlot);
      index = (index + 1) & mask;
    }
  }

  static int mix(int value) {
    value ^= value >>> 16;
    value *= 0x7feb352d;
    value ^= value >>> 15;
    value *= 0x846ca68b;
    return value ^ value >>> 16;
  }
}
