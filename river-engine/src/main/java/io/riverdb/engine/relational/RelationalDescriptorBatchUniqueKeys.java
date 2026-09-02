package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import java.nio.ByteBuffer;

/** Exact retained user-key set used to reject duplicates before row-ID reservation. */
final class RelationalDescriptorBatchUniqueKeys {
  private static final int MAXIMUM_ENTRIES =
      SqlShapeLimits.MAX_INSERT_ROWS_PER_STATEMENT * SqlShapeLimits.MAX_TABLE_INDEXES;
  private static final int MAXIMUM_BYTES =
      MAXIMUM_ENTRIES * SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES;
  private static final int INITIAL_ENTRIES = 8;
  private static final int INITIAL_BYTES = 256;
  private static final int INITIAL_HASH_SLOTS = 16;
  private static final int MAXIMUM_HASH_SLOTS = maximumHashSlots();
  private final RelationalRetainedBudget budget;
  private final RelationalDescriptorBatchAllocator allocator;
  private long[] keyIds = new long[0];
  private int[] offsets = new int[0];
  private int[] lengths = new int[0];
  private int[] occupiedSlots = new int[0];
  private byte[] bytes = new byte[0];
  private int[] hashSlots = new int[0];
  private ByteBuffer byteView = ByteBuffer.wrap(bytes);
  private int count;
  private int used;

  RelationalDescriptorBatchUniqueKeys() {
    this(null, RelationalDescriptorBatchAllocator.STANDARD);
  }

  RelationalDescriptorBatchUniqueKeys(
      RelationalRetainedBudget retainedBudget,
      RelationalDescriptorBatchAllocator batchAllocator) {
    budget = retainedBudget;
    allocator = batchAllocator;
  }

  StatusCode add(long keyId, ByteBuffer source, int length) {
    if (keyId <= 0 || source == null || length < 0 || length > source.limit()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int hash = hash(keyId, source, length);
    if (find(keyId, source, length, hash) >= 0) return StatusCode.UNIQUE_VIOLATION;
    StatusCode status = reserve(count + 1, used + length);
    if (!status.isOk()) return status;
    int slot = emptySlot(hash);
    if (slot < 0) return StatusCode.INVARIANT_BROKEN;
    keyIds[count] = keyId;
    offsets[count] = used;
    lengths[count] = length;
    for (int index = 0; index < length; index++) bytes[used + index] = source.get(index);
    hashSlots[slot] = count + 1;
    occupiedSlots[count] = slot;
    used += length;
    count++;
    return StatusCode.OK;
  }

  void reset() {
    for (int entry = 0; entry < count; entry++) {
      hashSlots[occupiedSlots[entry]] = 0;
      keyIds[entry] = 0;
      offsets[entry] = 0;
      lengths[entry] = 0;
      occupiedSlots[entry] = 0;
    }
    for (int index = 0; index < used; index++) bytes[index] = 0;
    count = 0;
    used = 0;
  }

  StatusCode validate(IndexedTransactionSession session, TableDescriptor table) {
    for (int entry = 0; entry < count; entry++) {
      KeyDescriptor key = find(table, keyIds[entry]);
      if (key == null) return StatusCode.RETRY;
      StatusCode status = session.validateTupleUniquePrefix(
          table.tableId(), key.keyId(), key.keyId(), key.shape(),
          byteView, offsets[entry], lengths[entry], Long.MAX_VALUE);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private boolean same(int entry, ByteBuffer source, int length) {
    if (lengths[entry] != length) return false;
    int offset = offsets[entry];
    for (int index = 0; index < length; index++) {
      if (bytes[offset + index] != source.get(index)) return false;
    }
    return true;
  }

  long retainedBytes() {
    return keyIds.length * (long) Long.BYTES
        + offsets.length * (long) Integer.BYTES
        + lengths.length * (long) Integer.BYTES
        + occupiedSlots.length * (long) Integer.BYTES
        + bytes.length + hashSlots.length * (long) Integer.BYTES;
  }

  private StatusCode reserve(int requiredEntries, int requiredBytes) {
    if (requiredEntries <= 0 || requiredEntries > MAXIMUM_ENTRIES
        || requiredBytes < used || requiredBytes > MAXIMUM_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int entryCapacity = entryCapacity(requiredEntries);
    int byteCapacity = byteCapacity(requiredBytes);
    int slotCapacity = slotCapacity(requiredEntries);
    if (entryCapacity < 0 || byteCapacity < 0 || slotCapacity < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long replacement = replacementBytes(entryCapacity, byteCapacity, slotCapacity);
    if (replacement == 0) return StatusCode.OK;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(replacement);
    if (!status.isOk()) return status;
    try {
      long[] nextIds = keyIds;
      int[] nextOffsets = offsets;
      int[] nextLengths = lengths;
      int[] nextOccupiedSlots = occupiedSlots;
      byte[] nextBytes = bytes;
      ByteBuffer nextView = byteView;
      int[] nextSlots = hashSlots;
      if (entryCapacity != keyIds.length) {
        nextIds = allocator.longs(entryCapacity);
        nextOffsets = allocator.integers(entryCapacity);
        nextLengths = allocator.integers(entryCapacity);
        nextOccupiedSlots = allocator.integers(entryCapacity);
        System.arraycopy(keyIds, 0, nextIds, 0, count);
        System.arraycopy(offsets, 0, nextOffsets, 0, count);
        System.arraycopy(lengths, 0, nextLengths, 0, count);
        System.arraycopy(occupiedSlots, 0, nextOccupiedSlots, 0, count);
      }
      if (byteCapacity != bytes.length) {
        nextBytes = allocator.bytes(byteCapacity);
        System.arraycopy(bytes, 0, nextBytes, 0, used);
        nextView = allocator.view(nextBytes);
      }
      if (slotCapacity != hashSlots.length) {
        nextSlots = allocator.integers(slotCapacity);
        rehash(
            nextIds, nextOffsets, nextLengths, nextOccupiedSlots,
            nextBytes, nextSlots);
      }
      long retired = retiredBytes(entryCapacity, byteCapacity, slotCapacity);
      keyIds = nextIds;
      offsets = nextOffsets;
      lengths = nextLengths;
      occupiedSlots = nextOccupiedSlots;
      bytes = nextBytes;
      byteView = nextView;
      hashSlots = nextSlots;
      if (budget != null && retired > 0) budget.rollback(retired);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      if (budget != null) budget.rollback(replacement);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private int find(long keyId, ByteBuffer source, int length, int hash) {
    if (hashSlots.length == 0) return -1;
    int slot = hash & (hashSlots.length - 1);
    for (int probed = 0; probed < hashSlots.length; probed++) {
      int stored = hashSlots[slot];
      if (stored == 0) return -1;
      int entry = stored - 1;
      if (keyIds[entry] == keyId && same(entry, source, length)) return entry;
      slot = (slot + 1) & (hashSlots.length - 1);
    }
    return -1;
  }

  private int emptySlot(int hash) {
    int slot = hash & (hashSlots.length - 1);
    for (int probed = 0; probed < hashSlots.length; probed++) {
      if (hashSlots[slot] == 0) return slot;
      slot = (slot + 1) & (hashSlots.length - 1);
    }
    return -1;
  }

  private void rehash(
      long[] ids, int[] starts, int[] sizes, int[] occupied,
      byte[] retained, int[] slots) {
    for (int entry = 0; entry < count; entry++) {
      int hash = hash(ids[entry], retained, starts[entry], sizes[entry]);
      int slot = hash & (slots.length - 1);
      while (slots[slot] != 0) slot = (slot + 1) & (slots.length - 1);
      slots[slot] = entry + 1;
      occupied[entry] = slot;
    }
  }

  private int entryCapacity(int required) {
    return required <= keyIds.length ? keyIds.length : BoundedArrayGrowth.capacity(
        keyIds.length, required, MAXIMUM_ENTRIES, INITIAL_ENTRIES);
  }

  private int byteCapacity(int required) {
    return required <= bytes.length ? bytes.length : BoundedArrayGrowth.capacity(
        bytes.length, required, MAXIMUM_BYTES, INITIAL_BYTES);
  }

  private int slotCapacity(int required) {
    int capacity = hashSlots.length == 0 ? INITIAL_HASH_SLOTS : hashSlots.length;
    while ((long) required * 2 > capacity && capacity < MAXIMUM_HASH_SLOTS) {
      capacity <<= 1;
    }
    return (long) required * 2 <= capacity ? capacity : -1;
  }

  private long replacementBytes(int entryCapacity, int byteCapacity, int slotCapacity) {
    long retained = entryCapacity == keyIds.length ? 0
        : (long) entryCapacity * (Long.BYTES + 3L * Integer.BYTES);
    if (byteCapacity != bytes.length) retained += byteCapacity;
    if (slotCapacity != hashSlots.length) retained += (long) slotCapacity * Integer.BYTES;
    return retained;
  }

  private long retiredBytes(int entryCapacity, int byteCapacity, int slotCapacity) {
    long retired = entryCapacity == keyIds.length ? 0
        : (long) keyIds.length * (Long.BYTES + 3L * Integer.BYTES);
    if (byteCapacity != bytes.length) retired += bytes.length;
    if (slotCapacity != hashSlots.length) retired += (long) hashSlots.length * Integer.BYTES;
    return retired;
  }

  private static int hash(long keyId, ByteBuffer source, int length) {
    int hash = (int) (keyId ^ (keyId >>> 32));
    for (int index = 0; index < length; index++) {
      hash = 31 * hash + (source.get(index) & 0xff);
    }
    return spread(hash);
  }

  private static int hash(long keyId, byte[] source, int offset, int length) {
    int hash = (int) (keyId ^ (keyId >>> 32));
    for (int index = 0; index < length; index++) {
      hash = 31 * hash + (source[offset + index] & 0xff);
    }
    return spread(hash);
  }

  private static int spread(int hash) { return hash ^ (hash >>> 16); }

  private static int maximumHashSlots() {
    int capacity = 1;
    while ((long) capacity < (long) MAXIMUM_ENTRIES * 2) capacity <<= 1;
    return capacity;
  }

  private static KeyDescriptor find(TableDescriptor table, long keyId) {
    int keys = RelationalDescriptorKeySet.count(table);
    for (int index = 0; index < keys; index++) {
      KeyDescriptor key = RelationalDescriptorKeySet.at(table, index);
      if (key.keyId() == keyId) return key;
    }
    return null;
  }
}
