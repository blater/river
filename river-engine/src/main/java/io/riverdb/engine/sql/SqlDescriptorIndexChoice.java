package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;

/** Primitive winning descriptor-index shape selected without candidate allocation. */
final class SqlDescriptorIndexChoice {
  KeyDescriptor key;
  int equalityParts;
  int lowerLeaf = -1;
  int upperLeaf = -1;
  int direction = 1;
  int score = -1;
  boolean orderCovered;

  void reset() {
    key = null;
    equalityParts = 0;
    lowerLeaf = -1;
    upperLeaf = -1;
    direction = 1;
    score = -1;
    orderCovered = false;
  }

  void set(
      KeyDescriptor selected, int equal, int lower, int upper,
      int scanDirection, boolean ordered, int candidateScore) {
    key = selected;
    equalityParts = equal;
    lowerLeaf = lower;
    upperLeaf = upper;
    direction = scanDirection;
    orderCovered = ordered;
    score = candidateScore;
  }
}
