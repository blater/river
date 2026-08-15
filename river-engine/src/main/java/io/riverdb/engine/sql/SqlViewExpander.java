package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Resolves a table name or delegates one durable view expansion. */
final class SqlViewExpander {
  private final SqlPersistedViewCompiler views = new SqlPersistedViewCompiler();

  StatusCode resolve(
      RelationalSession session, BoundSqlStatement bound, SqlBinder binder) {
    StatusCode tableStatus = session.resolveTable(
        bound.command.tableName(), bound.table);
    return tableStatus.isOk()
        ? tableStatus : views.expand(session, bound, binder, tableStatus);
  }
}
