package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Fail-closed admission for the descriptor shapes supported by physical SQL DDL. */
final class SqlDescriptorLifecycleAdmission {
  private SqlDescriptorLifecycleAdmission() { }

  static boolean ready(SqlCommand command, TableDescriptor descriptor) {
    return primaryReady(command, descriptor)
        && columnsReady(command)
        && constraintsReady(command);
  }

  private static boolean primaryReady(
      SqlCommand command, TableDescriptor descriptor) {
    KeyDescriptor primary = descriptor == null ? null : descriptor.primaryKey();
    if (descriptor == null || command.hasPrimaryKeyIdentity()) return false;
    if (primary == null) return true;
    if (primary.kind() != KeyDescriptor.KIND_PRIMARY || !primary.isUnique()
        || primary.partCount() <= 0) return false;
    for (int part = 0; part < primary.partCount(); part++) {
      int column = primary.columnOrdinalAt(part);
      if (column < 0 || column >= descriptor.columnCount()
          || descriptor.isNullable(column)
          || primary.typeDescriptorAt(part) != descriptor.typeDescriptorAt(column)) {
        return false;
      }
    }
    return true;
  }

  private static boolean columnsReady(SqlCommand command) {
    for (int index = 0; index < command.columnCount(); index++) {
      if (command.columnHasDefault(index)
          && (command.columnDefaultKind(index) != SqlDefaultKind.LITERAL
              || SqlTypeDescriptor.typeId(command.columnTypeDescriptor(index))
                  == SqlTypeDescriptor.TYPE_ID_VARCHAR)) return false;
      if (command.columnHasCheck(index) && !directCheck(command, index)) return false;
    }
    return true;
  }

  private static boolean constraintsReady(SqlCommand command) {
    for (int index = 0; index < command.tableConstraintCount(); index++) {
      int kind = command.tableConstraintKind(index);
      if (kind == SqlCommand.CONSTRAINT_CHECK && !retainedColumnCheck(command, index)) {
        return false;
      }
    }
    return true;
  }

  static boolean constraintShapesReady(SqlCommand command) {
    return columnsReady(command) && constraintsReady(command);
  }

  private static boolean directCheck(SqlCommand command, int owner) {
    SqlScalarExpression expression = command.projectionExpression(owner);
    if (expression == null || expression.nodeCount() != 1
        || expression.operator(0) != SqlScalarExpression.COLUMN) return false;
    int symbol = (int) expression.operand(0);
    CharSequence table = command.projectionSymbolTable(symbol);
    CharSequence name = command.projectionSymbolName(symbol);
    return table != null && table.length() == 0 && name != null
        && SqlDescriptorPrimaryPredicate.same(name, command.columnName(owner))
        && SqlTypeDescriptor.typeId(command.columnCheckTypeDescriptor(owner))
            != SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.canCompare(
            command.columnTypeDescriptor(owner), command.columnCheckTypeDescriptor(owner));
  }

  private static boolean retainedColumnCheck(SqlCommand command, int constraint) {
    if (command.tableConstraintPartCount(constraint) != 1) return false;
    CharSequence name = command.tableConstraintPartName(constraint, 0);
    for (int column = 0; column < command.columnCount(); column++) {
      if (command.columnHasCheck(column)
          && SqlDescriptorPrimaryPredicate.same(name, command.columnName(column))) return true;
    }
    return false;
  }
}
