package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Materializes projection and DISTINCT block stages into owned row stores. */
final class SqlBlockProjectionStage {
  private final BoundSqlStatement bound;
  private final SqlBlockSource source;
  private final SqlBlockStageProjector projector;
  private final SqlBlockStageProjector.Projected projected =
      new SqlBlockStageProjector.Projected();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow projectedRow = new SqlBlockRow();
  private final SqlBlockRow distinctRow = new SqlBlockRow();

  SqlBlockProjectionStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlBlockStageProjector stageProjector) {
    bound = statement;
    source = blockSource;
    projector = stageProjector;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore input, SqlBlockRowStore output, int sortKey) {
    StatusCode status = output.begin(
        bound.blockPlans().operandSchema(block), sortKey,
        bound.command.isDescendingOrder());
    if (status.isOk()) status = source.begin(input);
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
      if (!available || !same(sourceRow, distinctRow)) {
        distinctRow.copyFrom(sourceRow);
        status = output.append(sourceRow);
        available = true;
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

  private static boolean same(SqlBlockRow left, SqlBlockRow right) {
    if (left.nullValue(0) != right.nullValue(0)) return false;
    if (left.nullValue(0)) return true;
    if (left.textLength(0) != right.textLength(0)) return false;
    if (left.textLength(0) == 0) return left.value(0) == right.value(0);
    for (int index = 0; index < left.textLength(0); index++) {
      if (left.textCharacter(0, index) != right.textCharacter(0, index)) return false;
    }
    return true;
  }
}
