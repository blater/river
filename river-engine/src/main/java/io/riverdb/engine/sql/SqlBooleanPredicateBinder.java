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
        statement, null, null, -1);
  }

  StatusCode bindBlockWhere(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBlockSchema schema) {
    join = false;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, schema, null, -1);
  }

  StatusCode bindJoinWhere(SqlCommand command, BoundSqlStatement statement) {
    join = true;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, null, null, -1);
  }

  StatusCode bindJoinOn(SqlCommand command, BoundSqlStatement statement) {
    join = true;
    having = false;
    return bind(
        command, command.onPredicates(), statement.onBoolean(),
        statement, null, null, -1);
  }

  StatusCode bindHaving(SqlCommand command, BoundSqlStatement statement) {
    join = false;
    having = true;
    return bind(
        command, command.booleanHavingPredicates(), statement.havingBoolean,
        statement, null, null, -1);
  }

  StatusCode bindNested(
      SqlCommand command,
      BoundSqlStatement statement,
      BoundSqlQuery query,
      int block) {
    join = false;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.nestedBoolean(block),
        statement, null, query, block);
  }

  private StatusCode bind(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBlockSchema schema,
      BoundSqlQuery nested,
      int nestedBlock) {
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
            leaf, program, join, having, nested, nestedBlock);
        if (!status.isOk()) return status;
      }
      StatusCode status = SqlBooleanPredicateTypes.validate(source, target, leaf);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

}
