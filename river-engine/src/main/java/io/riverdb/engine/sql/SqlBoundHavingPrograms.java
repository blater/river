package io.riverdb.engine.sql;

import io.riverdb.sql.SqlScalarExpression;

/** Bound post-aggregate predicate programs and typed literal metadata. */
final class SqlBoundHavingPrograms {
  private static final int MAXIMUM_PREDICATES = 8;
  private final byte[] operators = new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] operands = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final byte[] nodeOffsets = new byte[MAXIMUM_PREDICATES];
  private final byte[] nodeCounts = new byte[MAXIMUM_PREDICATES];
  private final int[] resultDescriptors = new int[MAXIMUM_PREDICATES];
  private int count;
  private int nodes;

  void reset() {
    for (int predicate = 0; predicate < count; predicate++) {
      nodeOffsets[predicate] = 0;
      nodeCounts[predicate] = 0;
      resultDescriptors[predicate] = 0;
    }
    for (int node = 0; node < nodes; node++) {
      operators[node] = 0;
      operands[node] = 0;
      descriptors[node] = 0;
    }
    count = 0;
    nodes = 0;
  }

  void begin(int predicates) {
    reset();
    count = predicates;
  }

  void append(int predicate, int operator, long operand, int descriptor) {
    if (nodeCount(predicate) == 0) nodeOffsets[predicate] = (byte) nodes;
    operators[nodes] = (byte) operator;
    operands[nodes] = operand;
    descriptors[nodes++] = descriptor;
    nodeCounts[predicate]++;
  }

  void finish(int predicate, int descriptor) {
    resultDescriptors[predicate] = descriptor;
  }

  int count() { return count; }
  int nodeCount(int predicate) { return Byte.toUnsignedInt(nodeCounts[predicate]); }
  int operator(int predicate, int node) {
    return Byte.toUnsignedInt(operators[slot(predicate, node)]);
  }
  long operand(int predicate, int node) { return operands[slot(predicate, node)]; }
  int descriptor(int predicate, int node) { return descriptors[slot(predicate, node)]; }
  int resultDescriptor(int predicate) { return resultDescriptors[predicate]; }

  private int slot(int predicate, int node) {
    return Byte.toUnsignedInt(nodeOffsets[predicate]) + node;
  }
}
