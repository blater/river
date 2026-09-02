package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Materializes projection and DISTINCT block stages into owned row stores. */
final class SqlBlockProjectionStage {
  private final BoundSqlStatement bound;
  private final SqlBlockSource source;
  private final SqlBlockStageProjector projector;
  private final SqlBlockOutputOrder outputOrder;
  private final SqlBlockStageProjector.Projected projected =
      new SqlBlockStageProjector.Projected();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow projectedRow = new SqlBlockRow();
  private final SqlBlockRow distinctRow = new SqlBlockRow();

  SqlBlockProjectionStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlBlockStageProjector stageProjector,
      SqlBlockOutputOrder blockOutputOrder) {
    bound = statement;
    source = blockSource;
    projector = stageProjector;
    outputOrder = blockOutputOrder;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    StatusCode status = outputOrder.beginOperands(
        bound.command, bound.blockPlans().operandSchema(block), output);
    if (status.isOk()) status = source.begin(input, sourceRow);
    while (status.isOk()) {
      status = source.next(input, sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = projector.project(block, sourceRow, projectedRow, projected);
      if (status.isOk() && projected.available) status = output.append(projectedRow);
    }
    status = source.finish(input, status);
    return status.isOk() ? output.finish() : status;
  }

  StatusCode deduplicate(
      int block, SqlBlockRowStore sorted, SqlBlockRowStore output) {
    StatusCode status = output.begin(bound.blockPlans().schema(block), -1, false);
    boolean available = false;
    while (status.isOk()) {
      status = sorted.next(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      if (!available || !SqlBlockRowEquality.same(sourceRow, distinctRow, 0)) {
        status = SqlBlockDistinctPublisher.append(sourceRow, distinctRow, output);
        available = status.isOk();
      }
    }
    StatusCode closed = sorted.close();
    if (status.isOk()) status = closed;
    return status.isOk() ? output.finish() : status;
  }

  void reset() {
    sourceRow.reset(0);
    projectedRow.reset(0);
    distinctRow.reset(0);
  }
}
