package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommandType;

/** Identifies command types handled without opening a row scan. */
final class SqlCommandDispatchTypes {
  private SqlCommandDispatchTypes() { }

  static boolean handles(SqlCommandType type) {
    return type == SqlCommandType.BEGIN
        || type == SqlCommandType.SAVEPOINT
        || type == SqlCommandType.ROLLBACK_TO_SAVEPOINT
        || type == SqlCommandType.RELEASE_SAVEPOINT
        || type == SqlCommandType.SET_TIME_ZONE
        || type == SqlCommandType.COMMIT
        || type == SqlCommandType.ROLLBACK
        || type == SqlCommandType.CREATE_VIEW
        || type == SqlCommandType.DROP_VIEW
        || type == SqlCommandType.CREATE_TABLE
        || type == SqlCommandType.CREATE_SEQUENCE
        || type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX
        || type == SqlCommandType.DROP_SEQUENCE
        || type == SqlCommandType.DROP_INDEX
        || type == SqlCommandType.DROP_TABLE
        || type == SqlCommandType.ALTER_TABLE_RENAME
        || type == SqlCommandType.ALTER_TABLE_RENAME_COLUMN
        || type == SqlCommandType.ALTER_INDEX_RENAME
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.ANALYZE_TABLE
        || type == SqlCommandType.SCALAR_EXPRESSION
        || type == SqlCommandType.CHECKPOINT;
  }
}
