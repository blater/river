package io.riverdb.base.text;

/** Borrowed byte view whose owner keeps its contents stable for a synchronous copy. */
public interface BoundedByteSource {
  int length();
  byte getByte(int offset);
}
