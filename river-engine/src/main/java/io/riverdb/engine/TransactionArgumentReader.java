package io.riverdb.engine;

import io.riverdb.engine.api.TransactionProgramArguments;

/** Mutable reader pointed at an argument or internal dataflow frame. */
final class TransactionArgumentReader implements TransactionValueReader {
  private TransactionProgramArguments values;

  void pointTo(TransactionProgramArguments source) { values = source; }
  boolean isSet(int slot) { return values != null && values.isSet(slot); }
  @Override public int descriptor(int slot) { return values.typeDescriptorAt(slot); }
  @Override public long high(int slot) { return values.highValueAt(slot); }
  @Override public long low(int slot) { return values.valueAt(slot); }
  @Override public boolean isNull(int slot) { return values.isNull(slot); }
  @Override public int textLength(int slot) { return values.textLengthAt(slot); }
  @Override public char textCharacter(int slot, int character) {
    return values.textCharacterAt(slot, character);
  }
}
