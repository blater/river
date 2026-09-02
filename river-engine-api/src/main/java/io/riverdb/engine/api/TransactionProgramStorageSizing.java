package io.riverdb.engine.api;

/** Authoritative rounded graph-storage and validation peak sizing. */
final class TransactionProgramStorageSizing {
  private TransactionProgramStorageSizing() { }

  static long maximumRetainedBytes(
      int steps, int parameters, int captures, int expressions, int nodes) {
    if (steps < 0 || parameters < 0 || captures < 0 || expressions < 0 || nodes < 0) {
      return -1;
    }
    int stepCapacity = capacity(steps);
    int parameterCapacity = capacity(parameters);
    int captureCapacity = capacity(captures);
    int expressionCapacity = capacity(expressions);
    int nodeCapacity = capacity(nodes);
    long bytes = TransactionProgramStorage.stepBytes(stepCapacity);
    bytes = add(bytes, (long) parameterCapacity * Integer.BYTES);
    bytes = add(bytes, (long) captureCapacity * Integer.BYTES);
    bytes = add(bytes, (long) expressionCapacity * 3 * Integer.BYTES);
    bytes = add(bytes, (long) nodeCapacity * 6 * Integer.BYTES);
    long edges = Math.min(Integer.MAX_VALUE, (long) steps * 3L);
    return add(bytes, TransactionProgramValidationWorkspace.retainedBytes(
        steps, (int) edges, nodes));
  }

  private static int capacity(int needed) {
    if (needed == 0) return 0;
    int capacity = 8;
    while (capacity < needed) {
      int next = capacity << 1;
      if (next <= capacity) return needed;
      capacity = next;
    }
    return capacity;
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
