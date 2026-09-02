package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;

/** Copies one borrowed typed-parameter stream into command-owned literal storage. */
final class SqlParameterReader {
  private SqlParameterSource source;
  private int index;

  void begin(SqlParameterSource parameters) {
    source = parameters;
    index = 0;
  }

  StatusCode finish() {
    return source == null || index == source.count()
        ? StatusCode.OK : StatusCode.PARAMETER_COUNT_MISMATCH;
  }

  void reset() {
    source = null;
    index = 0;
  }

  StatusCode read(
      SqlCommand command,
      char[] text,
      SqlParser.LongResult result,
      int ordinal) {
    if (source == null || index >= source.count()) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    int parameter = ordinal < 0 ? index : ordinal;
    if (parameter >= source.count()) return StatusCode.PARAMETER_COUNT_MISMATCH;
    index++;
    int descriptor = source.typeDescriptorAt(parameter);
    if (source.isNull(parameter)) {
      return readNull(descriptor, result);
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return readText(command, text, parameter, descriptor, result);
    }
    long value = source.valueAt(parameter);
    long high = source.highValueAt(parameter);
    boolean valid = SqlTypeDescriptor.isWideDecimal(descriptor)
        ? SqlValueDomain.validDecimal128(descriptor, high, value)
        : SqlValueDomain.validFixed(descriptor, value);
    if (!valid) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.high = high;
    result.value = value;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = descriptor;
    return StatusCode.OK;
  }

  private static StatusCode readNull(
      int descriptor, SqlParser.LongResult result) {
    if (descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.value = 0;
    result.high = 0;
    result.varchar = descriptor != 0
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
    result.textScalars = 0;
    result.typeDescriptor = descriptor;
    result.nullValue = true;
    return StatusCode.OK;
  }

  private StatusCode readText(
      SqlCommand command,
      char[] text,
      int parameter,
      int descriptor,
      SqlParser.LongResult result) {
    int length = source.copyTextAt(parameter, text, 0);
    if (length < 0 || length > text.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalars = Utf8Text.scalarCount(text, 0, length);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > SqlTypeDescriptor.parameterOne(descriptor)) {
      return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    long handle = command.storeText(text, 0, length);
    if (handle == SqlCommand.INVALID_TEXT_HANDLE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.value = handle;
    result.high = 0;
    result.varchar = true;
    result.textScalars = scalars;
    result.typeDescriptor = descriptor;
    return StatusCode.OK;
  }
}
