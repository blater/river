package io.riverdb.engine.api;

/** Borrowed text view; the arena reuses this object when a different slot is selected. */
final class TransactionValueTextView implements CharSequence {
  private final TransactionValueArena arena;
  private int slot;

  TransactionValueTextView(TransactionValueArena owner) {
    arena = owner;
  }

  void pointTo(int valueSlot) { slot = valueSlot; }

  @Override public int length() { return arena.textLength(slot); }
  @Override public char charAt(int index) { return arena.textCharacterAt(slot, index); }
  @Override public CharSequence subSequence(int start, int end) {
    throw new UnsupportedOperationException();
  }
}
