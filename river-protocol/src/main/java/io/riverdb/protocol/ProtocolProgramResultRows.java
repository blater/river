package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Validates and publishes the row/cell tail of a program result payload. */
final class ProtocolProgramResultRows {
  private ProtocolProgramResultRows() { }

  static int validate(ByteBuffer source, int input, int end, int rows, int cells) {
    int counted = 0;
    for (int row = 0; row < rows; row++) {
      if (input > end - ProtocolProgramResultEncoder.ROW_BYTES) return -1;
      int columns = source.getInt(input);
      if (columns < 0 || source.getInt(input + 4) != 0) return -1;
      input += ProtocolProgramResultEncoder.ROW_BYTES;
      if (columns > (end - input) / ProtocolProgramResultEncoder.VALUE_HEADER_BYTES) return -1;
      counted += columns;
      for (int column = 0; column < columns; column++) {
        int next = ProtocolProgramResultValueDecoder.validate(source, input, end);
        if (next < 0) return -1;
        input = next;
      }
    }
    return counted == cells ? input : -1;
  }

  static StatusCode populate(
      ByteBuffer source, int input, int rows, TransactionProgramResult result,
      ProtocolProgramTextDecoder text) {
    for (int row = 0; row < rows; row++) {
      int columns = source.getInt(input);
      input += ProtocolProgramResultEncoder.ROW_BYTES;
      StatusCode status = result.beginRow(columns);
      if (!status.isOk()) return status;
      for (int column = 0; column < columns; column++) {
        status = ProtocolProgramResultValueDecoder.append(source, input, result, text);
        if (!status.isOk()) return status;
        input = ProtocolProgramResultValueDecoder.next(source, input);
      }
    }
    return StatusCode.OK;
  }

  static int next(ByteBuffer source, int input, int rows) {
    for (int row = 0; row < rows; row++) {
      int columns = source.getInt(input);
      input += ProtocolProgramResultEncoder.ROW_BYTES;
      for (int column = 0; column < columns; column++) {
        input = ProtocolProgramResultValueDecoder.next(source, input);
      }
    }
    return input;
  }
}
