package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves every scalar program and validates one Boolean predicate tree. */
final class SqlBooleanPredicateBinder {
  private final SqlBooleanScalarBinder scalars = new SqlBooleanScalarBinder();
  private int joinRoles;
  private boolean having;

  StatusCode bindWhere(SqlCommand command, BoundSqlStatement statement) {
    joinRoles = 0;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, null, null, null, -1);
  }

  StatusCode bindBlockWhere(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBlockSchema schema) {
    joinRoles = 0;
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, null, schema, null, -1);
  }

  StatusCode bindJoinWhere(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBoundJoinContext context) {
    joinRoles = command.joinChain().roleCount();
    having = false;
    return bind(
        command, command.wherePredicates(), statement.whereBoolean,
        statement, context, null, null, -1);
  }

  StatusCode bindJoinOn(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      int stage) {
    joinRoles = stage + 2;
    having = false;
    return bind(
        command, command.joinChain().onPredicates(stage), context.onBoolean(stage),
        statement, context, null, null, -1);
  }

  StatusCode bindHaving(SqlCommand command, BoundSqlStatement statement) {
    joinRoles = 0;
    having = true;
    return bind(
        command, command.booleanHavingPredicates(), statement.havingBoolean,
        statement, null, null, null, -1);
  }

  StatusCode bindNested(
      SqlCommand command,
      BoundSqlStatement statement,
      BoundSqlQuery query,
      int block) {
    BoundSqlQuery.Block current = query.block(block);
    joinRoles = current == null ? 0 : current.roleCount();
    having = false;
    return bind(
        command, command.wherePredicates(), statement.nestedBoolean(block),
        statement, null, null, query, block);
  }

  StatusCode bindNestedJoinOn(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      BoundSqlQuery query,
      int block,
      int stage) {
    joinRoles = stage + 2;
    having = false;
    return bind(
        command, command.joinChain().onPredicates(stage), context.onBoolean(stage),
        statement, context, null, query, block);
  }

  private StatusCode bind(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      SqlBlockSchema schema,
      BoundSqlQuery nested,
      int nestedBlock) {
    if (!source.isAvailable()) {
      target.reset();
      return StatusCode.OK;
    }
    StatusCode admission = target.begin(source);
    if (!admission.isOk()) return admission;
    for (int leaf = 0; leaf < source.leafCount(); leaf++) {
      for (int program = 0; program < 4; program++) {
        if (source.programNodeCount(leaf, program) == 0) continue;
        StatusCode status = scalars.bind(
            command, source, target, statement, context, schema,
            leaf, program, joinRoles, having, nested, nestedBlock);
        if (!status.isOk()) return status;
      }
      StatusCode status = SqlBooleanPredicateTypes.validate(source, target, leaf);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

}
