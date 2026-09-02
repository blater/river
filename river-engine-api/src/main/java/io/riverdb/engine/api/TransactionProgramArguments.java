package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlApproximateNumeric;

/** Mutable invocation literals kept separate from a frozen transaction program. */
public final class TransactionProgramArguments {
  private final TransactionValueArena values;

  public TransactionProgramArguments() { this(RetainedMemoryLease.unbounded()); }

  public TransactionProgramArguments(RetainedMemoryLease memory) {
    values = new TransactionValueArena(memory);
  }

  public void reset() { values.reset(); }
  public boolean isSet(int slot) { return values.isSet(slot); }
  public StatusCode setNull(int slot, int descriptor) { return values.setNull(slot, descriptor); }
  public StatusCode setFixed(int slot, int descriptor, long value) {
    return values.setFixed(slot, descriptor, value >> Long.SIZE - 1, value);
  }
  public StatusCode setDecimal128(int slot, int descriptor, long high, long low) {
    return values.setDecimal128(slot, descriptor, high, low);
  }
  public StatusCode setReal(int slot, float value) {
    return values.setFixed(slot, io.riverdb.base.type.SqlTypeDescriptor.REAL, 0,
        SqlApproximateNumeric.realBits(value));
  }
  public StatusCode setDouble(int slot, double value) {
    return values.setFixed(slot, io.riverdb.base.type.SqlTypeDescriptor.DOUBLE, 0,
        SqlApproximateNumeric.doubleBits(value));
  }
  public StatusCode setText(int slot, int descriptor, CharSequence value) {
    return values.setText(slot, descriptor, value);
  }
  public StatusCode release() { return values.release(); }
  public long retainedBytes() { return values.retainedBytes(); }
  public static long maximumRetainedBytes(int slots, int textCharacters) {
    return TransactionValueArenaSizing.maximumRetainedBytes(slots, textCharacters);
  }
  /** Highest populated slot plus one; callers encode every slot in this dense prefix. */
  public int slotCount() { return values.highSlot(); }

  public int typeDescriptorAt(int slot) { return values.descriptor(slot); }
  public long highValueAt(int slot) { return values.high(slot); }
  public long valueAt(int slot) { return values.low(slot); }
  public boolean isNull(int slot) { return values.isNull(slot); }
  public int textLengthAt(int slot) { return values.textLength(slot); }
  public char textCharacterAt(int slot, int character) {
    return values.textCharacterAt(slot, character);
  }

  TransactionValueArena arena() { return values; }
}
