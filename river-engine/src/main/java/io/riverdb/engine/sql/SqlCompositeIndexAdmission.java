package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Fails closed until composite index catalog/storage publication is wired. */
final class SqlCompositeIndexAdmission {
  private SqlCompositeIndexAdmission() { }

  static StatusCode validate(SqlCommand command) {
    SqlCommandType type = command.type();
    return (type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX)
        && command.columnCount() != 1
            ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }
}
