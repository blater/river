package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Executes and closes a materialized block pipeline for one point result. */
final class SqlPointBlockExecution {
  private SqlPointBlockExecution() {}

  static StatusCode execute(
      SqlBlockPipelineExecution pipeline,
      StatusCode status,
      long commitSequence,
      SqlExecutionResult result,
      boolean acceptFirstRow) {
    if (status.isOk() && pipeline == null) status = StatusCode.CONFLICT;
    if (status.isOk() && pipeline.rowCount() == 0) status = StatusCode.CONFLICT;
    if (status.isOk() && pipeline.rowCount() > 1 && !acceptFirstRow) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) status = pipeline.next(result, commitSequence);
    StatusCode closed = pipeline == null ? StatusCode.OK : pipeline.close();
    return status.isOk() ? closed : status;
  }

}
