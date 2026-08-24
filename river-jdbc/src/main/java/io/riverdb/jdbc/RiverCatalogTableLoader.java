package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;

/** Loads, filters, and sorts catalog table rows into the bounded result set. */
final class RiverCatalogTableLoader {
  private static final String TABLE = "TABLE";
  private static final String VIEW = "VIEW";

  private RiverCatalogTableLoader() { }

  static void load(RiverCatalogResultSet result) throws java.sql.SQLException {
    while (result.query != null && !result.queryClosed) {
      result.source.reset();
      JdbcExceptions.require(result.query.next(result.source), "fetch table metadata");
      if (!result.source.isAvailable()) {
        result.closeQuery(false);
        break;
      }
      result.tableNameLength = result.source.copyTextAt(0, result.tableNameCharacters, 0);
      result.tableTypeLength = result.source.copyTextAt(1, result.tableType, 0);
      if (result.tableNameLength < 0 || result.tableTypeLength < 0) {
        throw JdbcExceptions.failure(StatusCode.INVARIANT_BROKEN, "decode table metadata");
      }
      boolean table = RiverCatalogResultSet.equals(
          result.tableType, result.tableTypeLength, TABLE);
      boolean view = RiverCatalogResultSet.equals(
          result.tableType, result.tableTypeLength, VIEW);
      if ((table && result.includeTables || view && result.includeViews)
          && RiverCatalogResultSet.matches(
              result.tableNameCharacters, result.tableNameLength, result.pattern)) {
        result.appendTable(table ? (byte) 0 : (byte) 1);
      }
    }
    result.sortTables();
    result.tablesLoaded = true;
    if (result.tableCount == 0) {
      result.finishLocal();
    }
  }
}
