package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses strict local temporal descriptors and typed literals without allocating. */
final class SqlTemporalParser {
  private final SqlParserInput input;
  private final LocalTemporal.Value value = new LocalTemporal.Value();

  SqlTemporalParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  boolean starts(CharSequence sql) {
    int start = input.position();
    boolean temporal = input.consumeKeyword(sql, "TIMESTAMP")
        || input.consumeKeyword(sql, "TIME")
        || input.consumeKeyword(sql, "DATE");
    input.position(start);
    return temporal;
  }

  StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    int type = consumeLiteralType(sql);
    if (type == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = input.requireCharacter(sql, '\'');
    if (!status.isOk()) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    int contentStart = input.position();
    int end = contentStart;
    while (end < sql.length() && sql.charAt(end) != '\'') {
      end++;
    }
    if (end >= sql.length()) {
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    input.position(end + 1);
    status = parseValue(sql, contentStart, end, type);
    if (!status.isOk()) {
      return status;
    }
    result.value = value.value;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = literalDescriptor(type);
    return StatusCode.OK;
  }

  StatusCode typeDescriptor(CharSequence sql, SqlParser.LongResult result) {
    int type = consumeTypeName(sql);
    if (type == SqlTypeDescriptor.TYPE_ID_DATE) {
      return setDescriptor(result, SqlTypeDescriptor.DATE);
    }
    if (type == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = parsePrecision(sql, result);
    if (!status.isOk()) {
      return status;
    }
    type = consumeTimeZoneSuffix(sql, type);
    int descriptor = temporalDescriptor(type, value.precision);
    return descriptor == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : setDescriptor(result, descriptor);
  }

  private int consumeLiteralType(CharSequence sql) {
    int type = consumeTypeName(sql);
    return type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? timestampType(sql) : type;
  }

  private int consumeTypeName(CharSequence sql) {
    if (input.consumeKeyword(sql, "TIMESTAMP")) {
      return SqlTypeDescriptor.TYPE_ID_TIMESTAMP;
    }
    if (input.consumeKeyword(sql, "TIME")) {
      return SqlTypeDescriptor.TYPE_ID_TIME;
    }
    return input.consumeKeyword(sql, "DATE")
        ? SqlTypeDescriptor.TYPE_ID_DATE : 0;
  }

  private StatusCode parsePrecision(
      CharSequence sql, SqlParser.LongResult result) {
    value.precision = SqlTypeDescriptor.MAXIMUM_TEMPORAL_PRECISION;
    if (!input.consumeCharacter(sql, '(')) {
      return StatusCode.OK;
    }
    StatusCode status = input.number(sql, result);
    if (!status.isOk()) {
      return status;
    }
    if (result.value > Integer.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    value.precision = (int) result.value;
    return input.requireCharacter(sql, ')');
  }

  private int consumeTimeZoneSuffix(CharSequence sql, int type) {
    if (type != SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || !input.consumeKeyword(sql, "WITH")) {
      return type;
    }
    StatusCode status = input.requireKeyword(sql, "TIME");
    if (status.isOk()) {
      status = input.requireKeyword(sql, "ZONE");
    }
    return status.isOk() ? SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE : 0;
  }

  private StatusCode parseValue(CharSequence sql, int start, int end, int type) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_DATE ->
          LocalTemporal.parseDateStatus(sql, start, end, value);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          LocalTemporal.parseTimeStatus(sql, start, end, value);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.parseTimestampStatus(sql, start, end, value);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.parseTimestampWithOffsetStatus(sql, start, end, value);
      default -> StatusCode.INVALID_DATETIME_FORMAT;
    };
  }

  private int literalDescriptor(int type) {
    return type == SqlTypeDescriptor.TYPE_ID_DATE
        ? SqlTypeDescriptor.DATE : temporalDescriptor(type, value.precision);
  }

  private static int temporalDescriptor(int type, int precision) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_TIME -> SqlTypeDescriptor.time(precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> SqlTypeDescriptor.timestamp(precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          SqlTypeDescriptor.timestampWithTimeZone(precision);
      default -> 0;
    };
  }

  private int timestampType(CharSequence sql) {
    return consumeTimeZoneSuffix(sql, SqlTypeDescriptor.TYPE_ID_TIMESTAMP);
  }

  private static StatusCode setDescriptor(
      SqlParser.LongResult result, int descriptor) {
    result.value = descriptor;
    result.typeDescriptor = descriptor;
    return StatusCode.OK;
  }
}
