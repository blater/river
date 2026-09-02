package io.riverdb.engine;

/** Allocation-free mutable text view over a current transaction value. */
final class TransactionTextSource implements CharSequence {
  private TransactionValueReader reader;
  private int slot;

  void pointTo(TransactionValueReader source, int valueSlot) {
    reader = source;
    slot = valueSlot;
  }

  @Override public int length() { return reader.textLength(slot); }
  @Override public char charAt(int index) { return reader.textCharacter(slot, index); }
  @Override public CharSequence subSequence(int start, int end) {
    throw new UnsupportedOperationException();
  }
}
