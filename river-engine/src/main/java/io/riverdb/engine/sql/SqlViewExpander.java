package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Resolves a table name or delegates one durable view expansion. */
final class SqlViewExpander {
  private final SqlPersistedViewCompiler views;

  SqlViewExpander(SqlBinder binder) {
    views = new SqlPersistedViewCompiler(binder);
  }

  StatusCode resolve(
      RelationalSession session, BoundSqlStatement bound) {
    StatusCode tableStatus = session.resolveTable(
        bound.command.tableName(), bound.table);
    if (tableStatus.isOk()) return tableStatus;
    StatusCode status = views.expand(session, bound, tableStatus);
    if (status.isOk()) bound.expandedView = true;
    return status;
  }
}
