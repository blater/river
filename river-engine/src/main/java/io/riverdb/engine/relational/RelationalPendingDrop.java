package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;

/** Session-owned drop intent retained through commit and its cleanup transaction. */
final class RelationalPendingDrop {
  private static final int NONE = 0;
  private static final int INDEX = 1;
  private static final int TABLE = 2;
  private final TableSchema.ColumnName indexName = new TableSchema.ColumnName();
  private final TableSchema.ColumnName tableName = new TableSchema.ColumnName();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private int mutationStart;
  private int type;

  boolean active() { return type != NONE; }

  void index(CharSequence index, CharSequence table, int start) {
    indexName.set(index);
    tableName.set(table);
    mutationStart = start;
    type = INDEX;
  }

  void table(CharSequence table, int start) {
    tableName.set(table);
    mutationStart = start;
    type = TABLE;
  }

  void rollbackTo(int count) {
    if (active() && count <= mutationStart) clear();
  }

  StatusCode finish(RelationalSession session, RelationalSchemaLifecycle lifecycle) {
    int cleanupType = type;
    // Cleanup opens another transaction on this session; it must not see a pending drop.
    type = NONE;
    outcome.reset();
    return cleanupType == INDEX
        ? lifecycle.finishDroppingValueIndex(session, indexName, tableName, outcome)
        : lifecycle.finishDroppingTable(session, tableName, outcome);
  }

  void clear() {
    indexName.reset();
    tableName.reset();
    mutationStart = 0;
    type = NONE;
  }
}
