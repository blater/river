package io.riverdb.engine.sql;

import java.nio.ByteBuffer;

/** Transactionally grown retained bytes for block sort keys. */
final class SqlBlockKeyBuffer {
  private static final int WARM_BYTES = 64 * 1_024;

  private final SqlBlockKeyBufferAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private ByteBuffer bytes;

  SqlBlockKeyBuffer(SqlBlockKeyBufferAllocator bufferAllocator) {
    this(bufferAllocator, null);
  }

  SqlBlockKeyBuffer(
      SqlBlockKeyBufferAllocator bufferAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = bufferAllocator;
    budget = shapeBudget;
  }

  boolean ensure(int required, long admittedMaximum) {
    long maximum = Math.max(capacity(), admittedMaximum);
    try {
      if (bytes == null) {
        if (required > maximum) return false;
        int capacity = (int) Math.min(WARM_BYTES, maximum);
        if (!reserve(capacity)) return false;
        try {
          bytes = allocator.allocate(capacity);
        } catch (OutOfMemoryError error) {
          releaseBudget(capacity);
          return false;
        }
      }
      if (bytes.remaining() >= required) return true;
      int needed = bytes.position() + required;
      if (needed > maximum) return false;
      int capacity = bytes.capacity();
      while (capacity < needed) capacity = (int) Math.min(maximum, capacity * 2L);
      int previousCapacity = bytes.capacity();
      int charged = capacity;
      if (!reserve(charged)) return false;
      ByteBuffer grown;
      try {
        grown = allocator.allocate(capacity);
      } catch (OutOfMemoryError error) {
        releaseBudget(charged);
        return false;
      }
      int used = bytes.position();
      for (int index = 0; index < used; index++) grown.put(index, bytes.get(index));
      grown.position(used);
      ByteBuffer previous = bytes;
      bytes = grown;
      erase(previous, used);
      releaseBudget(previousCapacity);
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  ByteBuffer bytes() { return bytes; }
  int capacity() { return bytes == null ? 0 : bytes.capacity(); }
  int position() { return bytes == null ? 0 : bytes.position(); }
  void clear() { if (bytes != null) bytes.clear(); }

  void rollback(int position) {
    if (bytes == null) return;
    int end = bytes.position();
    for (int index = position; index < end; index++) bytes.put(index, (byte) 0);
    bytes.position(position);
  }

  void close() {
    erase(bytes, position());
    if (bytes != null && bytes.capacity() > WARM_BYTES) {
      releaseBudget(bytes.capacity());
      bytes = null;
    }
  }

  private boolean reserve(long bytes) {
    return budget == null || budget.reserve(bytes).isOk();
  }

  private void releaseBudget(long bytes) {
    if (budget != null) budget.rollback(bytes);
  }

  private static void erase(ByteBuffer buffer, int length) {
    if (buffer == null) return;
    buffer.clear();
    for (int index = 0; index < Math.min(length, buffer.capacity()); index++) {
      buffer.put(index, (byte) 0);
    }
    buffer.clear();
  }
}
