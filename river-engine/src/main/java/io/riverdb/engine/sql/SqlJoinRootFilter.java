package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.StoredTableRowIntegerFilter;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Mandatory, root-local WHERE leaves safe to test before JOIN probes. */
final class SqlJoinRootFilter {
  private int[] leaves = new int[0];
  private int count;
  private final StoredTableRowIntegerFilter integerFilter =
      new StoredTableRowIntegerFilter();

  StatusCode configure(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    count = 0;
    integerFilter.reset();
    if (!where.available()
        || !SqlJoinPredicateClassifier.total(command.wherePredicates())
        || !SqlJoinPredicateClassifier.total(where)) {
      return StatusCode.OK;
    }
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      if (!SqlJoinPredicateClassifier.total(command.joinChain().onPredicates(stage))
          || !SqlJoinPredicateClassifier.total(context.onBoolean(stage))) {
        return StatusCode.OK;
      }
    }
    if (leaves.length < where.leafCount()) {
      try {
        leaves = new int[where.leafCount()];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    collect(where, context, where.root());
    return StatusCode.OK;
  }

  private void collect(
      SqlBoundBooleanPredicateProgram where,
      SqlBoundJoinContext context,
      int node) {
    int operator = where.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(where, context, where.booleanLeft(node));
      collect(where, context, where.booleanRight(node));
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      int leaf = where.booleanLeft(node);
      if (where.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_COMPARISON
          && rootOperand(where, context, leaf,
              SqlBooleanPredicateProgram.PROGRAM_LEFT)
          && rootOperand(where, context, leaf,
              SqlBooleanPredicateProgram.PROGRAM_RIGHT)
          && (where.operator(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0)
                  == SqlScalarExpression.COLUMN
              || where.operator(leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0)
                  == SqlScalarExpression.COLUMN)) {
        leaves[count++] = leaf;
      }
    }
  }

  private static boolean rootOperand(
      SqlBoundBooleanPredicateProgram where,
      SqlBoundJoinContext context,
      int leaf,
      int program) {
    return where.nodeCount(leaf, program) == 1
        && (where.operator(leaf, program, 0) != SqlScalarExpression.COLUMN
            || context.localRole(where.scope(leaf, program, 0)) == 0);
  }

  int[] leaves() { return leaves; }
  int count() { return count; }

  void configureIntegerFilter(
      SqlBoundBooleanPredicateProgram where, TableDescriptor table) {
    integerFilter.reset();
    for (int index = 0; index < count; index++) {
      int leaf = leaves[index];
      if (where.negated(leaf)) continue;
      int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
      int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
      if (where.operator(leaf, left, 0) != SqlScalarExpression.COLUMN) {
        int swap = left;
        left = right;
        right = swap;
      }
      if (where.operator(leaf, left, 0) != SqlScalarExpression.COLUMN
          || where.operator(leaf, right, 0) != SqlScalarExpression.LITERAL) continue;
      int column = where.rawColumn(leaf, left);
      if (column < 0 || column >= table.columnCount()
          || !integer(table.typeDescriptorAt(column))
          || !integer(where.resultDescriptor(leaf, right))) continue;
      SqlComparison comparison = left == SqlBooleanPredicateProgram.PROGRAM_LEFT
          ? where.comparison(leaf) : where.comparison(leaf).reverseOrder();
      if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN
          || comparison == SqlComparison.HALF_OPEN_RANGE) continue;
      integerFilter.configure(column, comparison, where.operand(leaf, right, 0));
      return;
    }
  }

  private static boolean integer(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_SMALLINT
        || type == SqlTypeDescriptor.TYPE_ID_INTEGER
        || type == SqlTypeDescriptor.TYPE_ID_BIGINT;
  }

  StoredTableRowIntegerFilter integerFilter() {
    return integerFilter.active() ? integerFilter : null;
  }
}
