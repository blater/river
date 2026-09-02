package io.riverdb.engine;

/** Primitive value source used without wrapper allocation by transaction execution. */
interface TransactionValueReader {
  int descriptor(int slot);
  long high(int slot);
  long low(int slot);
  boolean isNull(int slot);
  int textLength(int slot);
  char textCharacter(int slot, int character);
}
