package io.riverdb.engine.table;

/** In-place stable ordering of one cursor's bounded primitive intent ordinals. */
final class IndexedTupleIntentOrder {
  private IndexedTupleIntentOrder() { }

  static void sort(
      IndexedTupleIntentJournal intents, int[] ordinals, int count, int direction) {
    for (int index = 1; index < count; index++) {
      int value = ordinals[index];
      int position = index;
      while (position > 0
          && intents.compare(ordinals[position - 1], value) * direction > 0) {
        ordinals[position] = ordinals[position - 1];
        position--;
      }
      ordinals[position] = value;
    }
  }
}
