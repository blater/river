package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Walks and binds the conjunctive literal programs of one primary-key predicate. */
final class SqlDescriptorPrimaryPrograms {
  private SqlCommand command;
  private TableDescriptor table;
  private KeyDescriptor primary;
  private boolean[] assigned;
  private SqlDescriptorPrimaryValues values;
  private int assignedCount;

  StatusCode bind(
      SqlBooleanPredicateProgram where, SqlCommand sql, TableDescriptor descriptor,
      KeyDescriptor key, boolean[] parts, SqlDescriptorPrimaryValues target) {
    command = sql;
    table = descriptor;
    primary = key;
    assigned = parts;
    values = target;
    assignedCount = 0;
    StatusCode status = bindNode(where, where.root());
    return status.isOk() && assignedCount != primary.partCount()
        ? StatusCode.CONFLICT : status;
  }

  private StatusCode bindNode(SqlBooleanPredicateProgram where, int node) {
    int operator = where.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      return bindLeaf(where, where.booleanLeft(node));
    }
    if (operator != SqlBooleanPredicateProgram.BOOLEAN_AND) return StatusCode.CONFLICT;
    StatusCode status = bindNode(where, where.booleanLeft(node));
    return status.isOk() ? bindNode(where, where.booleanRight(node)) : status;
  }

  private StatusCode bindLeaf(SqlBooleanPredicateProgram where, int leaf) {
    if (where.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || where.comparison(leaf) != SqlComparison.EQUAL) return StatusCode.CONFLICT;
    StatusCode status = bindPrograms(where, leaf,
        SqlBooleanPredicateProgram.PROGRAM_LEFT, SqlBooleanPredicateProgram.PROGRAM_RIGHT);
    return status == StatusCode.CONFLICT ? bindPrograms(where, leaf,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT, SqlBooleanPredicateProgram.PROGRAM_LEFT) : status;
  }

  private StatusCode bindPrograms(
      SqlBooleanPredicateProgram where, int leaf, int columnProgram, int literalProgram) {
    if (where.programNodeCount(leaf, columnProgram) != 1
        || where.programOperator(leaf, columnProgram, 0) != SqlScalarExpression.COLUMN
        || where.programNodeCount(leaf, literalProgram) != 1
        || where.programOperator(leaf, literalProgram, 0) != SqlScalarExpression.LITERAL) {
      return StatusCode.CONFLICT;
    }
    int symbol = (int) where.programOperand(leaf, columnProgram, 0);
    CharSequence qualifier = command.predicateSymbolTable(symbol);
    if (qualifier.length() != 0
        && !SqlDescriptorPrimaryPredicate.same(qualifier, command.tableName())) {
      return StatusCode.CONFLICT;
    }
    int column = table.findColumn(command.predicateSymbolName(symbol));
    int part = partForColumn(column);
    if (part < 0 || assigned[part]) return StatusCode.CONFLICT;
    StatusCode status = values.assign(column,
        where.programDescriptor(leaf, literalProgram, 0), primary.typeDescriptorAt(part),
        where.programOperandHigh(leaf, literalProgram, 0),
        where.programOperand(leaf, literalProgram, 0));
    if (status.isOk()) {
      assigned[part] = true;
      assignedCount++;
    }
    return status;
  }

  private int partForColumn(int column) {
    for (int part = 0; part < primary.partCount(); part++) {
      if (primary.columnOrdinalAt(part) == column) return part;
    }
    return -1;
  }
}
