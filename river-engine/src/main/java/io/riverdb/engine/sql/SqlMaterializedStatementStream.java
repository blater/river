package io.riverdb.engine.sql;

import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;

/** One retained stream slot in a statement's bounded concurrent high-water list. */
final class SqlMaterializedStatementStream {
  private SqlMaterializedPagedByteStream stream;
  private final SqlMaterializedStatementStream next;

  SqlMaterializedStatementStream(SqlMaterializedStatementStream following) { next = following; }

  void attach(SqlMaterializedPagedByteStream retained) { stream = retained; }

  boolean reusable(SqlMaterializedScratchFileKind kind, int fixedBytes, int flags) {
    return stream.isClosed()
        && stream.kind() == kind
        && stream.fixedRecordBytes() == fixedBytes
        && stream.flags() == flags;
  }

  SqlMaterializedPagedByteStream stream() { return stream; }
  SqlMaterializedStatementStream next() { return next; }
}
