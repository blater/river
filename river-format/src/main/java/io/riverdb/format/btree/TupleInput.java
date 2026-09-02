package io.riverdb.format.btree;

/** Borrowed encoded tuple bytes; implementations may span pages and must not allocate on reads. */
public interface TupleInput {
  int byteLength();

  /** Returns one unsigned byte, or {@code -1} outside the borrowed range. */
  int byteAt(int index);
}
