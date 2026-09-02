package io.riverdb.engine.sql;

/** Allocation-free logical null-word view over caller-owned row state. */
interface SqlNullWords {
  int nullWordCount();
  long nullWord(int word);

  default boolean nullAt(int column) {
    return column >= 0 && column >>> 6 < nullWordCount()
        && (nullWord(column >>> 6) & 1L << (column & 63)) != 0;
  }
}
