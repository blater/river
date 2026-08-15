package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.io.PrintStream;

/** Prints one validated row value using its canonical SQL descriptor. */
final class RiverSqlRowFormatter {
  private final char[] characters =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];

  StatusCode print(RowResult row, int index, PrintStream output) {
    if (row.isNull(index)) {
      output.print("NULL");
      return StatusCode.OK;
    }
    int descriptor = row.typeDescriptorAt(index);
    if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.CORRUPTION;
    if (row.isVarchar(index)) return printText(row, index, output);
    long value = row.valueAt(index);
    if (!SqlValueDomain.validFixed(descriptor, value)) return StatusCode.CORRUPTION;
    return printFixed(value, descriptor, output);
  }

  private StatusCode printText(RowResult row, int index, PrintStream output) {
    int length = row.copyTextAt(index, characters, 0);
    if (length < 0) return StatusCode.CORRUPTION;
    printCharacters(output, length);
    return StatusCode.OK;
  }

  private StatusCode printFixed(long value, int descriptor, PrintStream output) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_BIGINT) {
      output.print(value);
      return StatusCode.OK;
    }
    if (type == SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
      output.print(value == 0 ? "FALSE" : "TRUE");
      return StatusCode.OK;
    }
    if (type == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      printDecimal(value, SqlTypeDescriptor.parameterTwo(descriptor), output);
      return StatusCode.OK;
    }
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    int length = switch (type) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.formatDate(value, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          LocalTemporal.formatTime(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.formatTimestamp(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.formatTimestampWithOffset(value, precision, 0, characters, 0);
      default -> -1;
    };
    if (length < 0) return StatusCode.CORRUPTION;
    printCharacters(output, length);
    return StatusCode.OK;
  }

  private void printCharacters(PrintStream output, int length) {
    for (int index = 0; index < length; index++) {
      output.print(characters[index]);
    }
  }

  private static void printDecimal(long value, int scale, PrintStream output) {
    if (scale == 0) {
      output.print(value);
      return;
    }
    long magnitude = Math.abs(value);
    long divisor = ExactDecimal.powerOfTen(scale);
    if (value < 0) output.print('-');
    output.print(magnitude / divisor);
    output.print('.');
    long fraction = magnitude % divisor;
    for (long place = divisor / 10; place > 0; place /= 10) {
      output.print((char) ('0' + fraction / place));
      fraction %= place;
    }
  }
}
