package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Reusable balanced builder for one associative Boolean chain. */
final class SqlBooleanChain {
  private static final int LEVELS = Integer.SIZE - Integer.numberOfLeadingZeros(
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES);
  private final int[] roots = new int[
      (SqlBooleanPredicateProgram.MAXIMUM_DEPTH + 1) * LEVELS];
  private int frames;

  void reset() {
    frames = 0;
  }

  int begin() {
    int frame = frames++;
    int offset = frame * LEVELS;
    for (int level = 0; level < LEVELS; level++) roots[offset + level] = -1;
    return frame;
  }

  void seed(int frame, int node, StatusCode status) {
    if (status.isOk()) roots[frame * LEVELS] = node;
  }

  boolean append(
      int frame, int node, SqlBooleanPredicateProgram target, int operator) {
    int offset = frame * LEVELS;
    for (int level = 0; level < LEVELS; level++) {
      int left = roots[offset + level];
      if (left < 0) {
        roots[offset + level] = node;
        return true;
      }
      node = target.appendBoolean(operator, left, node);
      roots[offset + level] = -1;
      if (node < 0) return false;
    }
    return false;
  }

  int finish(
      int frame, SqlBooleanPredicateProgram target, int operator, boolean valid) {
    int result = -1;
    int offset = frame * LEVELS;
    if (valid) {
      for (int level = LEVELS - 1; level >= 0; level--) {
        int next = roots[offset + level];
        if (next >= 0) {
          result = result < 0 ? next : target.appendBoolean(operator, result, next);
          if (result < 0) break;
        }
      }
    }
    frames--;
    return result;
  }

  static StatusCode status(StatusCode prior, int node) {
    return prior.isOk() && node < 0 ? StatusCode.RESOURCE_EXHAUSTED : prior;
  }
}
