package io.riverdb.engine;

import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanRowResult;

/** Allocation-free reader over either SQL command projection or scan row. */
final class TransactionSqlRowReader implements TransactionValueReader {
  private SqlExecutionResult command;
  private SqlScanRowResult row;

  void pointTo(SqlExecutionResult source) {
    command = source;
    row = null;
  }

  void pointTo(SqlScanRowResult source) {
    row = source;
    command = null;
  }

  int columnCount() { return row == null ? command.columnCount() : row.columnCount(); }
  @Override public int descriptor(int slot) {
    return row == null ? command.typeDescriptorAt(slot) : row.typeDescriptorAt(slot);
  }
  @Override public long high(int slot) {
    return row == null ? command.highValueAt(slot) : row.highValueAt(slot);
  }
  @Override public long low(int slot) {
    return row == null ? command.valueAt(slot) : row.valueAt(slot);
  }
  @Override public boolean isNull(int slot) {
    return row == null ? command.isNull(slot) : row.isNull(slot);
  }
  @Override public int textLength(int slot) {
    return row == null ? command.textLengthAt(slot) : row.textLengthAt(slot);
  }
  @Override public char textCharacter(int slot, int character) {
    return row == null
        ? command.textCharacterAt(slot, character)
        : row.textCharacterAt(slot, character);
  }
}
