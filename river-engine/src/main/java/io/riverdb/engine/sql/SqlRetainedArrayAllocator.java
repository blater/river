package io.riverdb.engine.sql;

import io.riverdb.sql.SqlComparison;
import java.nio.ByteBuffer;

/** Injectable allocation boundary for transactionally published retained SQL arrays. */
class SqlRetainedArrayAllocator {
  static final SqlRetainedArrayAllocator STANDARD = new SqlRetainedArrayAllocator();

  byte[] bytes(int capacity) { return new byte[capacity]; }
  char[] characters(int capacity) { return new char[capacity]; }
  char[][] characterLanes(int capacity) { return new char[capacity][]; }
  short[] shorts(int capacity) { return new short[capacity]; }
  int[] integers(int capacity) { return new int[capacity]; }
  long[] longs(int capacity) { return new long[capacity]; }
  boolean[] booleans(int capacity) { return new boolean[capacity]; }
  ByteBuffer direct(int capacity) { return ByteBuffer.allocateDirect(capacity); }
  CharSequence[] names(int capacity) { return new CharSequence[capacity]; }
  SqlComparison[] comparisons(int capacity) { return new SqlComparison[capacity]; }
}
