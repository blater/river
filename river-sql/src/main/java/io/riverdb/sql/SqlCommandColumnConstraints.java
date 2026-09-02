package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Mutates the bounded column-constraint metadata of a parsed command. */
final class SqlCommandColumnConstraints {
  private SqlCommandColumnConstraints() { }

  static void markNotNull(SqlCommand command) {
    if (command.columnCount > 0) {
      command.columnNotNull[command.columnCount - 1] = true;
    }
  }

  static void markIdentity(SqlCommand command) {
    command.primaryKeyIdentity = true;
    command.primaryKeyIdentityColumn = command.columnCount - 1;
  }

  static void markDefault(SqlCommand command, long high, long value) {
    if (command.columnCount > 0) {
      int column = command.columnCount - 1;
      command.columnDefaults[column] = true;
      command.columnDefaultHighs[column] = high;
      command.columnDefaultValues[column] = value;
      command.columnDefaultKinds[column] = SqlDefaultKind.LITERAL;
    }
  }

  static void markCurrentDefault(SqlCommand command, int kind) {
    if (command.columnCount > 0) {
      int column = command.columnCount - 1;
      command.columnDefaults[column] = true;
      command.columnDefaultHighs[column] = 0;
      command.columnDefaultValues[column] = 0;
      command.columnDefaultKinds[column] = (byte) kind;
    }
  }

  static void markVarchar(SqlCommand command, int maximumScalars) {
    markType(command, SqlTypeDescriptor.varchar(maximumScalars));
  }

  static void markType(SqlCommand command, int descriptor) {
    if (command.columnCount > 0 && SqlTypeDescriptor.isValid(descriptor)) {
      command.columnTypeDescriptors[command.columnCount - 1] = descriptor;
    }
  }

  static StatusCode markUnique(SqlCommand command) {
    return SqlColumnConstraintAdmission.unique(command);
  }

  static SqlIdentifier referenceTable(SqlCommand command) {
    return command.columnCount > 0
        ? command.columnReferenceTableNames[command.columnCount - 1] : null;
  }

  static SqlIdentifier referenceColumn(SqlCommand command) {
    return command.columnCount > 0
        ? command.columnReferenceColumnNames[command.columnCount - 1] : null;
  }

  static StatusCode markReference(SqlCommand command) {
    return SqlColumnConstraintAdmission.reference(command);
  }

  static void markCheck(
      SqlCommand command,
      SqlComparison comparison,
      long high,
      long value,
      int descriptor) {
    if (command.columnCount > 0) {
      int column = command.columnCount - 1;
      command.columnCheckComparisons[column] = comparison;
      command.columnCheckHighs[column] = high;
      command.columnCheckValues[column] = value;
      command.columnCheckTypeDescriptors[column] = descriptor;
    }
  }

  static boolean any(boolean[] constraints, int columns) {
    for (int index = 0; index < columns; index++) if (constraints[index]) return true;
    return false;
  }

}
