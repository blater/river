package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses and coerces one column DEFAULT without allocating parse carriers. */
final class SqlColumnDefaultParser {
  private final SqlParserInput input;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private final ExactDecimal.LongValue converted = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value converted128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch scratch128 = new ExactDecimal128.Scratch();

  SqlColumnDefaultParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    int descriptor = command.columnTypeDescriptor(command.columnCount() - 1);
    int kind = currentKind(sql);
    if (kind != SqlDefaultKind.NONE) {
      if (!SqlDefaultKind.compatible(kind, descriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      command.markLastColumnCurrentDefault(kind);
      return StatusCode.OK;
    }
    StatusCode status = input.literal(sql, literal);
    if (status.isOk()) status = coerce(descriptor);
    if (status.isOk()) command.markLastColumnDefault(literal.high, literal.value);
    return status;
  }

  private int currentKind(CharSequence sql) {
    if (input.consumeKeyword(sql, "CURRENT_DATE")) return SqlDefaultKind.CURRENT_DATE;
    if (input.consumeKeyword(sql, "CURRENT_TIMESTAMP")) {
      return SqlDefaultKind.CURRENT_TIMESTAMP;
    }
    if (input.consumeKeyword(sql, "LOCALTIME")) return SqlDefaultKind.LOCALTIME;
    return input.consumeKeyword(sql, "LOCALTIMESTAMP")
        ? SqlDefaultKind.LOCALTIMESTAMP : SqlDefaultKind.NONE;
  }

  private StatusCode coerce(int target) {
    if (literal.typeDescriptor == target || varcharCast(target)) return StatusCode.OK;
    if (SqlTypeDescriptor.isWideDecimal(literal.typeDescriptor)
        || SqlTypeDescriptor.isWideDecimal(target)) {
      return coerceWide(target);
    }
    if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && ExactDecimal.widenScale(literal.value, literal.typeDescriptor, target, converted)) {
      return setConverted(target);
    }
    if (SqlNumericTypeRules.canAssign(literal.typeDescriptor, target)) {
      StatusCode status = SqlNumericValue.assign(
          literal.value, literal.typeDescriptor, target, converted, scratch);
      return status.isOk() ? setConverted(target) : status;
    }
    if (sameTemporalType(literal.typeDescriptor, target)
        && SqlTypeDescriptor.canImplicitlyCast(literal.typeDescriptor, target)) {
      literal.typeDescriptor = target;
      return StatusCode.OK;
    }
    return SqlTypeDescriptor.canImplicitlyCast(literal.typeDescriptor, target)
        ? StatusCode.NUMERIC_VALUE_OUT_OF_RANGE : StatusCode.DATATYPE_MISMATCH;
  }

  private StatusCode coerceWide(int target) {
    int source = literal.typeDescriptor;
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (SqlTypeDescriptor.typeId(target) != SqlTypeDescriptor.TYPE_ID_DECIMAL
        || sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL
            && !SqlNumericTypeRules.isIntegral(source)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal128.quantize(
            literal.high, literal.value,
            SqlTypeDescriptor.parameterOne(source), SqlTypeDescriptor.parameterTwo(source),
            SqlTypeDescriptor.parameterOne(target), SqlTypeDescriptor.parameterTwo(target),
            ExactDecimal128.ROUND_HALF_EVEN, true, converted128, scratch128)
        : ExactDecimal128.fromLong(
            literal.value, SqlTypeDescriptor.parameterOne(target),
            SqlTypeDescriptor.parameterTwo(target), converted128, scratch128);
    if (!status.isOk()) return status;
    literal.high = converted128.high;
    literal.value = converted128.low;
    literal.typeDescriptor = target;
    return StatusCode.OK;
  }

  private boolean varcharCast(int target) {
    return SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.canImplicitlyCast(literal.typeDescriptor, target);
  }

  private StatusCode setConverted(int target) {
    literal.value = converted.value;
    literal.typeDescriptor = target;
    return StatusCode.OK;
  }

  private static boolean sameTemporalType(int source, int target) {
    int type = SqlTypeDescriptor.typeId(source);
    return type == SqlTypeDescriptor.typeId(target)
        && (type == SqlTypeDescriptor.TYPE_ID_TIME
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE);
  }
}
