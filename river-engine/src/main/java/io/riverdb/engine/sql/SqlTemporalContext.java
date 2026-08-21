package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Session zone and one admitted statement's resolved temporal values. */
final class SqlTemporalContext {
  private final SqlTemporalZoneNames zoneNames = new SqlTemporalZoneNames();
  private final LongResult conversion = new LongResult();
  private final SqlTemporalZonePlan sessionZonePlan = new SqlTemporalZonePlan();
  private final SqlTemporalZonePlan conversionZonePlan = new SqlTemporalZonePlan();
  private final SqlTemporalRowOperations rowOperations =
      new SqlTemporalRowOperations(sessionZonePlan);
  private long currentDate;
  private long currentTimestamp;
  private long localTime;
  private long localTimestamp;
  private boolean statementActive;
  private boolean snapshotCaptured;

  SqlTemporalContext() {
    if (!sessionZonePlan.prepare(ZoneOffset.UTC).isOk()) {
      throw new IllegalStateException("UTC zone preparation failed");
    }
  }

  StatusCode beginStatement() {
    if (statementActive) {
      return StatusCode.CONFLICT;
    }
    statementActive = true;
    snapshotCaptured = false;
    return StatusCode.OK;
  }

  void finishStatement() {
    statementActive = false;
    snapshotCaptured = false;
  }

  boolean statementActive() {
    return statementActive;
  }

  StatusCode setTimeZone(SqlCommand command) {
    ZoneId parsed = zoneNames.parse(command, command.value());
    if (parsed == null) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    StatusCode status = sessionZonePlan.prepare(parsed);
    if (!status.isOk()) {
      return status;
    }
    return StatusCode.OK;
  }

  static String timeZoneDatabaseVersion() {
    return SqlTemporalZoneNames.databaseVersion();
  }

  StatusCode validateZones(SqlCommand command, SqlQuery query) {
    return SqlStoredViewZonePolicy.validate(command, query, zoneNames);
  }

  StatusCode resolveScalar(SqlCommand command) {
    SqlScalarExpression expression = command.scalarExpression();
    if (!statementActive || !expression.isAvailable()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = resolveCurrentLeaves(expression);
    if (!status.isOk()) {
      return status;
    }
    if (expression.nodeCount() != 2
        || expression.operator(0) != SqlScalarExpression.LITERAL
        || expression.operator(1) != SqlScalarExpression.AT_TIME_ZONE) {
      return StatusCode.OK;
    }
    ZoneId zone = zoneNames.parse(command, expression.operand(1));
    if (zone == null) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    status = conversionZonePlan.prepare(zone);
    return status.isOk() ? resolveAtTimeZone(expression, conversionZonePlan) : status;
  }

  StatusCode defaultValue(int kind, int descriptor, LongResult result) {
    if (!statementActive || result == null) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = captureIfNeeded();
    if (!status.isOk()) {
      return status;
    }
    long value = switch (kind) {
      case SqlDefaultKind.CURRENT_DATE -> currentDate;
      case SqlDefaultKind.CURRENT_TIMESTAMP -> currentTimestamp;
      case SqlDefaultKind.LOCALTIME -> localTime;
      case SqlDefaultKind.LOCALTIMESTAMP -> localTimestamp;
      default -> Long.MIN_VALUE;
    };
    int type = SqlTypeDescriptor.typeId(descriptor);
    result.value = type == SqlTypeDescriptor.TYPE_ID_TIME
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? LocalTemporal.truncateToPrecision(
            value, SqlTypeDescriptor.parameterOne(descriptor)) : value;
    return result.value == Long.MIN_VALUE
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.OK;
  }

  StatusCode currentValue(int operator, int descriptor, LongResult result) {
    if (!statementActive || result == null) return StatusCode.CONFLICT;
    StatusCode status = captureIfNeeded();
    if (!status.isOk()) return status;
    long value = switch (operator) {
      case SqlScalarExpression.CURRENT_DATE -> currentDate;
      case SqlScalarExpression.CURRENT_TIMESTAMP -> currentTimestamp;
      case SqlScalarExpression.LOCALTIME -> localTime;
      case SqlScalarExpression.LOCALTIMESTAMP -> localTimestamp;
      default -> Long.MIN_VALUE;
    };
    if (value == Long.MIN_VALUE) return StatusCode.DATATYPE_MISMATCH;
    int type = SqlTypeDescriptor.typeId(descriptor);
    result.value = type == SqlTypeDescriptor.TYPE_ID_TIME
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? LocalTemporal.truncateToPrecision(
            value, SqlTypeDescriptor.parameterOne(descriptor)) : value;
    return StatusCode.OK;
  }

  StatusCode castTemporal(
      long value, int source, int target, LongResult result) {
    return rowOperations.cast(value, source, target, result);
  }

  StatusCode prepareZone(
      SqlCommand command, long handle, SqlTemporalZonePlan plan) {
    if (plan == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    ZoneId parsed = zoneNames.parse(command, handle);
    return parsed == null
        ? StatusCode.INVALID_TIME_ZONE_DISPLACEMENT : plan.prepare(parsed);
  }

  StatusCode atTimeZone(
      long value, int source, SqlTemporalZonePlan plan, LongResult result) {
    return rowOperations.atTimeZone(value, source, plan, result);
  }

  StatusCode formatTemporal(
      long value,
      int source,
      int target,
      char[] characters,
      LocalTemporalCast.TextResult result) {
    return rowOperations.format(value, source, target, characters, result);
  }

  private StatusCode captureIfNeeded() {
    if (snapshotCaptured) {
      return StatusCode.OK;
    }
    StatusCode status = capture(Instant.now());
    snapshotCaptured = status.isOk();
    return status;
  }

  private StatusCode capture(Instant now) {
    long seconds = now.getEpochSecond();
    long micros;
    try {
      micros = Math.addExact(
          Math.multiplyExact(seconds, LocalTemporal.MICROSECONDS_PER_SECOND),
          now.getNano() / 1_000);
    } catch (ArithmeticException overflow) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    if (!LocalTemporal.validInstant(micros, 6)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    StatusCode offsetStatus = sessionZonePlan.offsetAtInstant(micros, conversion);
    if (!offsetStatus.isOk()) return offsetStatus;
    long offsetSeconds = conversion.value;
    long local;
    try {
      local = Math.addExact(
          micros, offsetSeconds * LocalTemporal.MICROSECONDS_PER_SECOND);
    } catch (ArithmeticException overflow) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    if (!LocalTemporal.validTimestamp(local, 6)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    currentTimestamp = micros;
    localTimestamp = local;
    currentDate = Math.floorDiv(local, LocalTemporal.MICROSECONDS_PER_DAY);
    localTime = Math.floorMod(local, LocalTemporal.MICROSECONDS_PER_DAY);
    return StatusCode.OK;
  }

  private StatusCode resolveCurrentLeaves(SqlScalarExpression expression) {
    for (int index = 0; index < expression.nodeCount(); index++) {
      int operator = expression.operator(index);
      if (operator < SqlScalarExpression.CURRENT_DATE
          || operator > SqlScalarExpression.LOCALTIMESTAMP) {
        continue;
      }
      StatusCode status = captureIfNeeded();
      if (!status.isOk()) {
        return status;
      }
      long value = switch (operator) {
        case SqlScalarExpression.CURRENT_DATE -> currentDate;
        case SqlScalarExpression.CURRENT_TIMESTAMP -> currentTimestamp;
        case SqlScalarExpression.LOCALTIME -> localTime;
        case SqlScalarExpression.LOCALTIMESTAMP -> localTimestamp;
        default -> Long.MIN_VALUE;
      };
      if (!expression.replaceNodeWithLiteral(
          index, value, expression.typeDescriptor(index))) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode resolveAtTimeZone(
      SqlScalarExpression expression, SqlTemporalZonePlan zone) {
    long source = expression.operand(0);
    int sourceDescriptor = expression.typeDescriptor(0);
    int sourceType = SqlTypeDescriptor.typeId(sourceDescriptor);
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? zone.localToInstant(source, conversion)
        : sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            ? zone.instantToLocal(source, conversion) : StatusCode.DATATYPE_MISMATCH;
    if (status.isOk()) {
      expression.replaceWithLiteral(conversion.value, expression.typeDescriptor(1));
    }
    return status;
  }

  static final class LongResult {
    long value;
  }
}
