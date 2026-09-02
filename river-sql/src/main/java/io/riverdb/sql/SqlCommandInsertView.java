package io.riverdb.sql;

/** Insert-row storage and accessors for a reusable parsed command. */
final class SqlCommandInsertView {
  private SqlCommandInsertView() { }

  static boolean append(
      SqlCommand command,
      long[] highs,
      long[] values,
      boolean[] nulls,
      boolean[] defaults,
      int[] typeDescriptors,
      int count) {
    if (command.inserts.append(
        highs, values, nulls, defaults, typeDescriptors, count)) {
      command.insertColumnCount = count;
      command.insertRowCount++;
      return true;
    }
    return false;
  }

  static void setInsert(SqlCommand command) {
    command.type = SqlCommandType.INSERT;
    command.key = command.inserts.value(0, 0);
    command.value = command.inserts.value(0, 1);
  }

  static long value(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        ? command.inserts.value(rowIndex, columnIndex) : 0;
  }

  static long high(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        ? command.inserts.high(rowIndex, columnIndex) : 0;
  }

  static boolean isNull(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        && command.inserts.isNull(rowIndex, columnIndex);
  }

  static boolean isDefault(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        && command.inserts.isDefault(rowIndex, columnIndex);
  }

  static int typeDescriptor(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        ? command.inserts.typeDescriptor(rowIndex, columnIndex) : 0;
  }

  private static boolean valid(SqlCommand command, int rowIndex, int columnIndex) {
    return rowIndex >= 0
        && rowIndex < command.insertRowCount
        && columnIndex >= 0
        && columnIndex < command.insertColumnCount;
  }
}
