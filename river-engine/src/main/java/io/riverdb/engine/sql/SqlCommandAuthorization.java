package io.riverdb.engine.sql;

import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.sql.SqlCommandType;

/** Maps each admitted SQL command family to its session permission. */
final class SqlCommandAuthorization {
  private SqlCommandAuthorization() {}

  static int requiredPermission(SqlCommandType type) {
    return switch (type) {
      case SELECT, COUNT, COUNT_VALUE, SUM, AVG, SCALAR_EXPRESSION, MIN, MAX,
          GROUP_COUNT, GROUP_COUNT_VALUE, GROUP_SUM, GROUP_AVG, GROUP_MIN, GROUP_MAX,
          DISTINCT_SCAN, JOIN_SCAN, SCAN, SHOW_TABLES, SHOW_INDEXES, SHOW_COLUMNS ->
          SessionPermissions.READ;
      case INSERT, UPDATE, DELETE, NEXT_SEQUENCE_VALUE, ANALYZE_TABLE ->
          SessionPermissions.WRITE;
      case CREATE_TABLE, CREATE_VIEW, CREATE_INDEX, CREATE_UNIQUE_INDEX,
          DROP_INDEX, DROP_TABLE, DROP_VIEW, ALTER_TABLE_RENAME,
          ALTER_TABLE_RENAME_COLUMN, ALTER_INDEX_RENAME, CREATE_SEQUENCE,
          DROP_SEQUENCE -> SessionPermissions.SCHEMA;
      case CHECKPOINT -> SessionPermissions.ADMIN;
      case BEGIN, SAVEPOINT, COMMIT, ROLLBACK, ROLLBACK_TO_SAVEPOINT,
          RELEASE_SAVEPOINT, SET_TIME_ZONE -> SessionPermissions.READ;
    };
  }
}
