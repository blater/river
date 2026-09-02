package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Lazily instantiated temporal-zone plans for actual projection count. */
final class SqlProjectionZoneSet {
  private final SqlTemporalZoneSet zones;

  SqlProjectionZoneSet() {
    this(new SqlSessionShapeBudget(null));
  }

  SqlProjectionZoneSet(SqlSessionShapeBudget shapeBudget) {
    zones = new SqlTemporalZoneSet(shapeBudget, SqlShapeLimits.MAX_RESULT_COLUMNS);
  }

  StatusCode reserve(int projections) {
    return zones.reserve(projections);
  }

  SqlTemporalZonePlan get(int index) {
    return zones.get(index);
  }

  StatusCode ensure(int index) {
    return zones.ensure(index);
  }

  StatusCode prepareProjection(
      SqlTemporalContext temporal, SqlCommand command, int index, long operand) {
    StatusCode status = ensure(index);
    return status.isOk()
        ? temporal.prepareZone(command, operand, zones.get(index)) : status;
  }

  StatusCode prepareMutation(
      SqlTemporalContext temporal,
      SqlCommand command,
      SqlTemporalZonePlan insertZone,
      int index,
      long operand) {
    if (command.type() == SqlCommandType.INSERT) {
      return temporal.prepareZone(command, operand, insertZone);
    }
    return prepareProjection(temporal, command, index, operand);
  }

  int retainedPlanCount() {
    return zones.retainedPlanCount();
  }

  void reset() {
    zones.reset();
  }
}
