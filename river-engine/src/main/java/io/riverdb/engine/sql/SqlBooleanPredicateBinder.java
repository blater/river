package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves every scalar program and validates one Boolean predicate tree. */
final class SqlBooleanPredicateBinder {
  private final SqlBooleanScalarBinder scalars = new SqlBooleanScalarBinder();
  private boolean join;
  private boolean having;

  StatusCode bindWhere(SqlCommand command, BoundSqlStatement statement) {
    join = false;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, null);
  }

  StatusCode bindBlockWhere(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBlockSchema schema) {
    join = false;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, schema);
  }

  StatusCode bindJoinWhere(SqlCommand command, BoundSqlStatement statement) {
    if (!rawJoinShape(command.wherePredicates())) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    join = true;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, null);
  }

  StatusCode bindHaving(SqlCommand command, BoundSqlStatement statement) {
    join = false;
    having = true;
    return bind(
        command, command.booleanHavingPredicates(), statement.havingBoolean,
        statement, null);
  }

  private StatusCode bind(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBlockSchema schema) {
    if (!source.isAvailable()) {
      target.reset();
      return StatusCode.OK;
    }
    target.begin(source);
    for (int leaf = 0; leaf < source.leafCount(); leaf++) {
      for (int program = 0; program < 4; program++) {
        if (source.programNodeCount(leaf, program) == 0) continue;
        StatusCode status = scalars.bind(
            command, source, target, statement, schema,
            leaf, program, join, having);
        if (!status.isOk()) return status;
      }
      StatusCode status = SqlBooleanPredicateTypes.validate(source, target, leaf);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static boolean rawJoinShape(SqlBooleanPredicateProgram source) {
    if (!source.isAvailable()) return true;
    for (int node = 0; node < source.booleanNodeCount(); node++) {
      int operator = source.booleanOperator(node);
      if (operator != SqlBooleanPredicateProgram.BOOLEAN_LEAF
          && operator != SqlBooleanPredicateProgram.BOOLEAN_AND) return false;
    }
    for (int leaf = 0; leaf < source.leafCount(); leaf++) {
      if (!rawColumn(source, leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT)) {
        return false;
      }
      for (int program = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        int count = source.programNodeCount(leaf, program);
        if (count > 1 || count == 1
            && source.programOperator(leaf, program, 0)
                != SqlScalarExpression.LITERAL
            && source.programOperator(leaf, program, 0)
                != SqlScalarExpression.NULL) return false;
      }
    }
    return true;
  }

  private static boolean rawColumn(
      SqlBooleanPredicateProgram source, int leaf, int program) {
    return source.programNodeCount(leaf, program) == 1
        && source.programOperator(leaf, program, 0) == SqlScalarExpression.COLUMN;
  }
}
