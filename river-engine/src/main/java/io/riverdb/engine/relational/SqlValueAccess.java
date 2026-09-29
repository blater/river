package io.riverdb.engine.relational;

import io.riverdb.base.text.BoundedByteSource;

/** Read-only values whose text bytes remain valid for the source owner's stated lifetime. */
public interface SqlValueAccess {
  int count();
  int descriptorAt(int column);
  boolean isNull(int column);
  long valueAt(int column);
  long highValueAt(int column);
  int textByteLengthAt(int column);
  int textByteOffsetAt(int column);
  BoundedByteSource textSource(int column);
  /** Decodes only for a consumer that requires UTF-16 characters. */
  int copyTextChars(int column, char[] destination, int destinationOffset);
}
