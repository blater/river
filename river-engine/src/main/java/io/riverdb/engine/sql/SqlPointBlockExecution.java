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
    status = validate(status, pipeline, acceptFirstRow);
    if (status.isOk()) status = pipeline.next(result, commitSequence);
    StatusCode closed = pipeline == null ? StatusCode.OK : pipeline.close();
    return status.isOk() ? closed : status;
  }

  private static StatusCode validate(
      StatusCode status, SqlBlockPipelineExecution pipeline, boolean acceptFirstRow) {
    if (!status.isOk()) return status;
    if (pipeline == null || pipeline.rowCount() == 0) return StatusCode.CONFLICT;
    if (pipeline.rowCount() > 1 && !acceptFirstRow) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

}
