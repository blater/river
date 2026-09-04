package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Retained row-source boundary shared by legacy and universal block JOIN execution. */
interface SqlBlockJoinRows {
  StatusCode begin();
  StatusCode next(SqlBlockRow row);
  StatusCode finish(StatusCode body);
  StatusCode skip();
  StatusCode close();
  boolean hasResources();
}
