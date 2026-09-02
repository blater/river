package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Double-buffered query-block columns and join chain published only after complete capture. */
final class SqlBoundQueryBlockSnapshot {
  private final SqlSessionShapeBudget budget;
  private SqlBoundQueryColumns columns;
  private SqlBoundQueryColumns stagedColumns;
  private SqlJoinChain join;
  private SqlJoinChain stagedJoin;

  SqlBoundQueryBlockSnapshot(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
    columns = new SqlBoundQueryColumns(budget);
    stagedColumns = new SqlBoundQueryColumns(budget);
  }

  StatusCode capture(SqlCommand source) {
    StatusCode status = stagedColumns.capture(source);
    boolean joined = source.joinChain() != null;
    if (status.isOk() && joined) status = stageJoin(source.joinChain());
    if (!status.isOk()) return status;
    SqlBoundQueryColumns priorColumns = columns;
    columns = stagedColumns;
    stagedColumns = priorColumns;
    stagedColumns.clear();
    if (joined) publishJoin();
    else if (join != null) join.reset();
    return StatusCode.OK;
  }

  void reset() {
    columns.clear();
    if (join != null) join.reset();
  }

  SqlJoinChain join() { return join; }
  CharSequence name(int index) { return columns.name(index); }
  CharSequence table(int index) { return columns.table(index); }
  CharSequence output(int index) { return columns.output(index); }
  CharSequence alias(int index) { return columns.alias(index); }
  boolean isNull(int index) { return columns.isNull(index); }

  private StatusCode stageJoin(SqlJoinChain source) {
    if (stagedJoin == null) {
      StatusCode admission = budget.reserve(4_096);
      if (!admission.isOk()) return admission;
      try {
        stagedJoin = new SqlJoinChain();
      } catch (OutOfMemoryError error) {
        budget.rollback(4_096);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return stagedJoin.copyFrom(source);
  }

  private void publishJoin() {
    SqlJoinChain prior = join;
    join = stagedJoin;
    stagedJoin = prior;
    if (stagedJoin != null) stagedJoin.reset();
  }
}
