package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Prepared Boolean callback consumed by the common JOIN row source. */
abstract class SqlJoinPredicateCallback {
  abstract StatusCode configureJoin(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where);

  abstract boolean matchesJoinOn(int stage, SqlJoinRoleRows rows);
  abstract boolean matchesJoinWhere(SqlJoinRoleRows rows);
  abstract StatusCode joinStatus();
}
