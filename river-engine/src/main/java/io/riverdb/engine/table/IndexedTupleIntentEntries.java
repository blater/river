package io.riverdb.engine.table;

/** Stable tuple-intent entry contract over split storage and query responsibilities. */
final class IndexedTupleIntentEntries extends IndexedTupleIntentLog {
  IndexedTupleIntentEntries() { super(); }
  IndexedTupleIntentEntries(int maximumMutations, int maximumPayloadBytes) {
    super(maximumMutations, maximumPayloadBytes);
  }
}
