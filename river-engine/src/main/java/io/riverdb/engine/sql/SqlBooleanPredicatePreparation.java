package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Prepares temporal and time-zone expression state for predicate programs. */
final class SqlBooleanPredicatePreparation {
  private static final int PROGRAMS_PER_LEAF = 4;

  private final SqlTemporalContext temporal;
  private final SqlTemporalContext.LongResult current =
      new SqlTemporalContext.LongResult();
  private SqlTemporalZonePlan[] zones;

  SqlBooleanPredicatePreparation(SqlTemporalContext temporalContext) {
    temporal = temporalContext;
  }

  StatusCode prepare(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound) {
    reset();
    if (!bound.available()) {
      return StatusCode.OK;
    }
    for (int leaf = 0; leaf < bound.leafCount(); leaf++) {
      for (int program = 0; program < PROGRAMS_PER_LEAF; program++) {
        StatusCode status = prepareProgram(source, bound, leaf, program);
        if (!status.isOk()) {
          return status;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode prepareProgram(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      int leaf,
      int program) {
    int zoneNodes = 0;
    int slot = programSlot(leaf, program);
    for (int node = 0; node < bound.nodeCount(leaf, program); node++) {
      int operator = bound.operator(leaf, program, node);
      if (operator >= SqlScalarExpression.CURRENT_DATE
          && operator <= SqlScalarExpression.LOCALTIMESTAMP) {
        StatusCode status = temporal.currentValue(
            operator, bound.descriptor(leaf, program, node), current);
        if (!status.isOk()) {
          return status;
        }
      }
      if (operator != SqlScalarExpression.AT_TIME_ZONE) {
        continue;
      }
      if (++zoneNodes > 1) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      if (zones == null) {
        zones = new SqlTemporalZonePlan[
            SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
      }
      if (zones[slot] == null) {
        zones[slot] = new SqlTemporalZonePlan();
      }
      StatusCode status = temporal.prepareZone(
          source, bound.operand(leaf, program, node), zones[slot]);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  SqlTemporalZonePlan zone(int leaf, int program) {
    return zones == null ? null : zones[programSlot(leaf, program)];
  }

  void reset() {
    if (zones == null) {
      return;
    }
    for (int index = 0; index < zones.length; index++) {
      if (zones[index] != null) {
        zones[index].reset();
        zones[index] = null;
      }
    }
    zones = null;
  }

  private static int programSlot(int leaf, int program) {
    return leaf * PROGRAMS_PER_LEAF + program;
  }
}
