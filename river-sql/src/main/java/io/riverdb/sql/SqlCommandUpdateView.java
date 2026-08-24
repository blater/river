package io.riverdb.sql;

/** Update-row storage and accessors for a reusable parsed command. */
final class SqlCommandUpdateView {
  private SqlCommandUpdateView() { }

  static void append(
      SqlCommand command,
      long value,
      boolean isNull,
      boolean isDefault,
      int typeDescriptor,
      int operator) {
    command.updateValues[command.updateColumnCount] = value;
    command.nullUpdates[command.updateColumnCount] = isNull;
    command.defaultUpdates[command.updateColumnCount] = isDefault;
    command.updateTypeDescriptors[command.updateColumnCount] = typeDescriptor;
    command.updateOperators[command.updateColumnCount] = operator;
    command.updateColumnCount++;
  }

  static long value(SqlCommand command, int index) {
    return valid(command, index) ? command.updateValues[index] : 0;
  }

  static int operator(SqlCommand command, int index) {
    return valid(command, index) ? command.updateOperators[index] : SqlCommand.UPDATE_LITERAL;
  }

  static boolean isNull(SqlCommand command, int index) {
    return valid(command, index) && command.nullUpdates[index];
  }

  static boolean isDefault(SqlCommand command, int index) {
    return valid(command, index) && command.defaultUpdates[index];
  }

  static int typeDescriptor(SqlCommand command, int index) {
    return valid(command, index) ? command.updateTypeDescriptors[index] : 0;
  }

  private static boolean valid(SqlCommand command, int index) {
    return index >= 0 && index < command.updateColumnCount;
  }
}
