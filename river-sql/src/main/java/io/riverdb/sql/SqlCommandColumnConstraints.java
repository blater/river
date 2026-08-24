package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Mutates the bounded column-constraint metadata of a parsed command. */
final class SqlCommandColumnConstraints {
  private SqlCommandColumnConstraints() { }

  static void markNotNull(SqlCommand command) {
    if (command.columnCount > 0) {
      command.columnNotNullMask |= 1L << command.columnCount - 1;
    }
  }

  static void markIdentity(SqlCommand command) { command.primaryKeyIdentity = true; }

  static void markDefault(SqlCommand command, long value) {
    if (command.columnCount > 1) {
      int column = command.columnCount - 1;
      command.columnDefaultMask |= 1L << column;
      command.columnDefaultValues[column] = value;
      command.columnDefaultKinds[column] = SqlDefaultKind.LITERAL;
    }
  }

  static void markCurrentDefault(SqlCommand command, int kind) {
    if (command.columnCount > 1) {
      int column = command.columnCount - 1;
      command.columnDefaultMask |= 1L << column;
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
    long bit = command.columnCount <= 0 ? 0 : 1L << command.columnCount - 1;
    if (command.columnCount <= 1
        || Long.bitCount(command.columnUniqueMask | command.columnReferenceMask | bit)
            > SqlCommand.MAXIMUM_CONSTRAINT_INDEXES
        || (command.columnUniqueMask & bit) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    command.columnUniqueMask |= bit;
    return StatusCode.OK;
  }

  static SqlIdentifier referenceTable(SqlCommand command) {
    return command.columnCount > 1
        ? command.columnReferenceTableNames[command.columnCount - 1] : null;
  }

  static SqlIdentifier referenceColumn(SqlCommand command) {
    return command.columnCount > 1
        ? command.columnReferenceColumnNames[command.columnCount - 1] : null;
  }

  static StatusCode markReference(SqlCommand command) {
    long bit = command.columnCount <= 0 ? 0 : 1L << command.columnCount - 1;
    if (command.columnCount <= 1
        || command.columnIsVarchar(command.columnCount - 1)
        || Long.bitCount(command.columnUniqueMask | command.columnReferenceMask | bit)
            > SqlCommand.MAXIMUM_CONSTRAINT_INDEXES
        || (command.columnReferenceMask & bit) != 0
        || command.columnReferenceTableNames[command.columnCount - 1].length() == 0
        || command.columnReferenceColumnNames[command.columnCount - 1].length() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    command.columnReferenceMask |= bit;
    return StatusCode.OK;
  }

  static void markCheck(
      SqlCommand command,
      SqlComparison comparison,
      long value,
      int descriptor) {
    if (command.columnCount > 0) {
      int column = command.columnCount - 1;
      command.columnCheckComparisons[column] = comparison;
      command.columnCheckValues[column] = value;
      command.columnCheckTypeDescriptors[column] = descriptor;
    }
  }
}
