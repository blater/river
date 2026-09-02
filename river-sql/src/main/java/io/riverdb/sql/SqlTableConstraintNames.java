package io.riverdb.sql;

/** Identifier and identity matching for CREATE TABLE constraints. */
final class SqlTableConstraintNames {
  private SqlTableConstraintNames() { }

  static boolean validIdentity(SqlCommand command) {
    if (!command.hasPrimaryKeyIdentity()) return true;
    int primary = primaryConstraint(command);
    return primary >= 0
        && command.tableConstraintPartCount(primary) == 1
        && find(command, command.tableConstraintPartName(primary, 0))
            == command.primaryKeyIdentityColumn();
  }

  static int find(SqlCommand command, CharSequence name) {
    for (int index = 0; index < command.columnCount(); index++) {
      if (same(command.columnName(index), name)) return index;
    }
    return -1;
  }

  static boolean duplicateColumns(SqlCommand command) {
    for (int index = 0; index < command.columnCount(); index++) {
      for (int previous = 0; previous < index; previous++) {
        if (same(command.columnName(index), command.columnName(previous))) return true;
      }
    }
    return false;
  }

  static boolean duplicateName(SqlCommand command, int constraint) {
    CharSequence name = command.tableConstraintName(constraint);
    if (name.length() == 0) return false;
    for (int index = 0; index < constraint; index++) {
      if (same(name, command.tableConstraintName(index))) return true;
    }
    return false;
  }

  static boolean duplicateParts(SqlCommand command, int constraint) {
    int kind = command.tableConstraintKind(constraint);
    for (int index = 0; index < constraint; index++) {
      if (command.tableConstraintKind(index) == kind
          && sameParts(command, index, constraint)) return true;
    }
    return false;
  }

  private static int primaryConstraint(SqlCommand command) {
    for (int constraint = 0; constraint < command.tableConstraintCount(); constraint++) {
      if (command.tableConstraintKind(constraint) == SqlTableConstraintSet.PRIMARY) {
        return constraint;
      }
    }
    return -1;
  }

  private static boolean sameParts(SqlCommand command, int left, int right) {
    int count = command.tableConstraintPartCount(left);
    if (count != command.tableConstraintPartCount(right)) return false;
    for (int part = 0; part < count; part++) {
      if (!same(command.tableConstraintPartName(left, part),
          command.tableConstraintPartName(right, part))) return false;
    }
    return true;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
