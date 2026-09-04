package io.riverdb.engine.sql;

import io.riverdb.engine.runtime.RiverRuntimeConfig;

/** Derives an operator's resident sort run from configured pages and actual cardinality. */
final class SqlMaterializedSortRunSizing {
  private SqlMaterializedSortRunSizing() { }

  static int pages(int configuredPages, int pageBytes, long rows) {
    if (configuredPages < RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES
        || pageBytes <= 32 || rows < 0) {
      return -1;
    }
    long ordinalsPerPage = Math.max(1, (pageBytes - 32L) / Long.BYTES);
    long required = rows == 0 ? 1 : 1 + (rows - 1) / ordinalsPerPage;
    return (int) Math.max(
        RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES,
        Math.min(configuredPages, required));
  }
}
