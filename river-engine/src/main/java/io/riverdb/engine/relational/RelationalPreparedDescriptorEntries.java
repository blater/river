package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Small geometric retained storage for the actual prepared-DDL high-water mark. */
final class RelationalPreparedDescriptorEntries {
  static final int MAXIMUM = 128;
  private static final int INITIAL = 4;
  private static final RelationalPreparedDescriptorEntry[] EMPTY =
      new RelationalPreparedDescriptorEntry[0];
  private final RelationalPreparedDescriptorAllocator allocator;
  private RelationalPreparedDescriptorEntry[] entries = EMPTY;

  RelationalPreparedDescriptorEntries() {
    this(RelationalPreparedDescriptorAllocator.STANDARD);
  }

  RelationalPreparedDescriptorEntries(RelationalPreparedDescriptorAllocator entryAllocator) {
    allocator = entryAllocator;
  }

  StatusCode reserve(int index) {
    if (index < 0 || index >= MAXIMUM) return StatusCode.RESOURCE_EXHAUSTED;
    if (index < entries.length && entries[index] != null) return StatusCode.OK;
    try {
      RelationalPreparedDescriptorEntry entry = allocator.entry();
      if (index < entries.length) {
        entries[index] = entry;
        return StatusCode.OK;
      }
      int capacity = Math.min(MAXIMUM,
          Math.max(index + 1, Math.max(INITIAL, entries.length * 2)));
      RelationalPreparedDescriptorEntry[] grown = allocator.entries(capacity);
      System.arraycopy(entries, 0, grown, 0, index);
      grown[index] = entry;
      entries = grown;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  RelationalPreparedDescriptorEntry at(int index) {
    return index >= 0 && index < entries.length ? entries[index] : null;
  }

  int capacity() { return entries.length; }
}
