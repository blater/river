package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlScalarExpression;

/** Root columns required to bind keys and evaluate admitted residual predicates. */
final class SqlKeyOrderedLookupRootColumns {
  private final boolean[] retained = new boolean[SqlShapeLimits.MAX_TABLE_COLUMNS];
  private final int[] sourceColumns = new int[SqlShapeLimits.MAX_TABLE_COLUMNS];
  private TableDefinition root;
  private int count;

  StatusCode prepare(
      TableDefinition table,
      SqlBoundBooleanPredicateProgram on,
      SqlBoundBooleanPredicateProgram where,
      int[] keyColumns,
      int keyCount) {
    reset();
    if (table == null || table.columnCount() > retained.length
        || keyColumns == null || keyCount < 0 || keyCount > keyColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    root = table;
    for (int key = 0; key < keyCount; key++) {
      int column = keyColumns[key];
      if (column < 0 || column >= root.columnCount()) return invalid();
      retained[column] = true;
    }
    if (!retain(on) || !retain(where)) return invalidAsFallback();
    for (int column = 0; column < root.columnCount(); column++) {
      if (retained[column]) sourceColumns[count++] = column;
    }
    return StatusCode.OK;
  }

  private boolean retain(SqlBoundBooleanPredicateProgram program) {
    if (program == null || !program.available()) return true;
    if (!SqlBoundPredicateConjunction.only(program, program.root())) return false;
    for (int leaf = 0; leaf < program.leafCount(); leaf++) {
      if (program.subqueryEdge(leaf) >= 0) return false;
      for (int operand = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          operand <= SqlBooleanPredicateProgram.PROGRAM_UPPER; operand++) {
        for (int node = 0; node < program.nodeCount(leaf, operand); node++) {
          if (!retain(program, leaf, operand, node)) return false;
        }
      }
    }
    return true;
  }

  private boolean retain(
      SqlBoundBooleanPredicateProgram program, int leaf, int operand, int node) {
    if (program.operator(leaf, operand, node) != SqlScalarExpression.COLUMN) return true;
    int scope = program.scope(leaf, operand, node);
    if (scope == SqlBoundBooleanPredicateProgram.SCOPE_RIGHT) return true;
    long column = program.operand(leaf, operand, node);
    if (scope != SqlBoundBooleanPredicateProgram.SCOPE_LEFT
        || column < 0 || column >= root.columnCount()) {
      return false;
    }
    retained[(int) column] = true;
    return true;
  }

  void reset() {
    for (int column = 0; column < retained.length; column++) retained[column] = false;
    root = null;
    count = 0;
  }

  int count() { return count; }
  int sourceColumn(int lane) { return sourceColumns[lane]; }

  int storedColumn(int sourceColumn) {
    for (int lane = 0; lane < count; lane++) {
      if (sourceColumns[lane] == sourceColumn) return lane;
    }
    return -1;
  }

  private StatusCode invalid() {
    reset();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode invalidAsFallback() {
    reset();
    return StatusCode.CONFLICT;
  }
}
