package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Adapter from one bound set leaf to reusable projected block rows. */
interface SqlUnionLeafSource {
  StatusCode describe(int block, SqlBlockSchema destination);
  StatusCode open(int block);
  SqlBlockSchema schema();
  boolean finalized();
  StatusCode next(SqlBlockRow destination);
  StatusCode close(StatusCode runtimeStatus);
}
