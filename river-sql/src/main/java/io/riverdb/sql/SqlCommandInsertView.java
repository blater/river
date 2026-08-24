package io.riverdb.sql;

/** Insert-row storage and accessors for a reusable parsed command. */
final class SqlCommandInsertView {
  private SqlCommandInsertView() { }

  static void append(
      SqlCommand command,
      long[] values,
      long nullMask,
      long defaultMask,
      int[] typeDescriptors,
      int count) {
    int destination = command.insertRowCount * SqlCommand.MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      command.insertValues[destination + index] = values[index];
      command.insertTypeDescriptors[destination + index] = typeDescriptors[index];
    }
    command.insertColumnCount = count;
    command.insertNullMasks[command.insertRowCount] = nullMask;
    command.insertDefaultMasks[command.insertRowCount] = defaultMask;
    command.insertRowCount++;
  }

  static void setInsert(SqlCommand command) {
    command.type = SqlCommandType.INSERT;
    command.key = command.insertValues[0];
    command.value = command.insertValues[1];
  }

  static long value(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        ? command.insertValues[rowIndex * SqlCommand.MAXIMUM_COLUMNS + columnIndex] : 0;
  }

  static boolean isNull(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        && (command.insertNullMasks[rowIndex] & 1L << columnIndex) != 0;
  }

  static boolean isDefault(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        && (command.insertDefaultMasks[rowIndex] & 1L << columnIndex) != 0;
  }

  static int typeDescriptor(SqlCommand command, int rowIndex, int columnIndex) {
    return valid(command, rowIndex, columnIndex)
        ? command.insertTypeDescriptors[
            rowIndex * SqlCommand.MAXIMUM_COLUMNS + columnIndex] : 0;
  }

  private static boolean valid(SqlCommand command, int rowIndex, int columnIndex) {
    return rowIndex >= 0
        && rowIndex < command.insertRowCount
        && columnIndex >= 0
        && columnIndex < command.insertColumnCount;
  }
}
