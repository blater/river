package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Allocation-free persisted CHECK program validation over catalog bytes. */
final class CatalogCheckProgramValidator {
  private CatalogCheckProgramValidator() {
  }

  static boolean valid(
      ByteBuffer source,
      int offset,
      int nodes,
      int owner,
      int ownerDescriptor,
      int valueDescriptor,
      int[] stack) {
    if (source == null || stack == null || nodes <= 0 || nodes > stack.length) {
      return false;
    }
    int state = 0;
    for (int node = 0; node < nodes; node++, offset += 13) {
      int operator = Byte.toUnsignedInt(source.get(offset));
      int descriptor = source.getInt(offset + 1);
      long operand = source.getLong(offset + 5);
      state = TableCheckProgram.step(
          operator, operand, descriptor, owner, ownerDescriptor, state, stack);
      if (state < 0) return false;
    }
    return TableCheckProgram.validFinal(state, stack, valueDescriptor);
  }
}
