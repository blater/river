package io.riverdb.engine.table;

/** Reusable primitive result for bounded scans that return status separately. */
final class IndexedCountResult {
  private long value;

  long value() {
    return value;
  }

  void set(long newValue) {
    value = newValue;
  }

  void reset() {
    value = 0;
  }
}
