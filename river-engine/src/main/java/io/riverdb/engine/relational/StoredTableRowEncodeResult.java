package io.riverdb.engine.relational;

/** Caller-owned publication result for one encoded stored table row. */
final class StoredTableRowEncodeResult {
  private int length;

  void reset() {
    length = 0;
  }

  int length() {
    return length;
  }

  void setLength(int encodedLength) {
    length = encodedLength;
  }
}
